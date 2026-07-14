package com.zhousl.aether.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

private const val SkillStorageDirectoryName = "agent-skills"
private const val SkillTempDirectoryName = "agent-skills-tmp"
private const val SkillFileName = "SKILL.md"
private const val MaxSkillArchiveBytes = 32L * 1024L * 1024L
private const val MaxSkillExtractedBytes = 128L * 1024L * 1024L
private const val MaxSkillEntryBytes = 16L * 1024L * 1024L
private const val MaxSkillZipEntries = 4096

internal fun requireSafeSkillDocumentName(rawName: String): String {
    val name = rawName.trim()
    require(
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name &&
            !File(name).isAbsolute
    ) { "Skill entry name must be a single safe file name." }
    return name
}

internal fun normalizeSkillRelativePath(
    rawPath: String,
    allowTrailingSlash: Boolean = false,
): String {
    val path = rawPath.replace('\\', '/')
    val normalized = if (allowTrailingSlash) path.trimEnd('/') else path
    val segments = normalized.split('/')
    require(
        normalized.isNotBlank() &&
            !path.startsWith('/') &&
            !Regex("^[A-Za-z]:/").containsMatchIn(path) &&
            segments.none { it.isBlank() || it == "." || it == ".." || '\u0000' in it } &&
            !File(normalized).isAbsolute
    ) { "Skill path must stay inside the skill directory." }
    return normalized
}

internal fun resolveConfinedSkillPath(root: File, relativePath: String): File {
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, relativePath).canonicalFile
    require(candidate != canonicalRoot && candidate.toPath().startsWith(canonicalRoot.toPath())) {
        "Skill path escaped the destination directory."
    }
    return candidate
}

internal suspend fun <T> replaceSkillDirectoryTransaction(
    stagingRoot: File,
    installRoot: File,
    afterReplace: suspend () -> T,
): T {
    val parent = installRoot.parentFile?.canonicalFile ?: error("Skill install directory has no parent.")
    require(stagingRoot.parentFile?.canonicalFile == parent && stagingRoot.isDirectory) {
        "Skill staging and install directories must be sibling directories."
    }
    val backupRoot = File(parent, ".backup-${installRoot.name}-${UUID.randomUUID()}")
    var movedExisting = false
    var movedStaging = false
    try {
        if (installRoot.exists()) {
            require(installRoot.renameTo(backupRoot)) { "Couldn't preserve the existing skill installation." }
            movedExisting = true
        }
        require(stagingRoot.renameTo(installRoot)) { "Couldn't commit the staged skill installation." }
        movedStaging = true
        val result = afterReplace()
        if (movedExisting) backupRoot.deleteRecursively()
        return result
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            runCatching {
                if (movedStaging && installRoot.exists()) {
                    require(installRoot.deleteRecursively()) { "Couldn't remove the failed skill installation." }
                }
                if (movedExisting) {
                    require(backupRoot.renameTo(installRoot)) { "Couldn't restore the previous skill installation." }
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}

internal suspend fun <T> removeSkillDirectoryTransaction(
    installRoot: File,
    afterMove: suspend () -> T,
): T {
    val parent = installRoot.parentFile?.canonicalFile ?: error("Skill install directory has no parent.")
    val quarantineRoot = File(parent, ".removing-${installRoot.name}-${UUID.randomUUID()}")
    require(installRoot.renameTo(quarantineRoot)) { "Couldn't stage the skill for removal." }
    try {
        val result = afterMove()
        quarantineRoot.deleteRecursively()
        return result
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            runCatching {
                require(quarantineRoot.renameTo(installRoot)) { "Couldn't restore the skill after removal failed." }
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}

private class SkillImportBudget {
    private var entries = 0
    private var bytes = 0L

    fun recordEntry() {
        entries += 1
        require(entries <= MaxSkillZipEntries) { "Skill folder contains too many entries." }
    }

    fun requireCapacity(additionalBytes: Long) {
        require(additionalBytes >= 0L && bytes <= MaxSkillExtractedBytes - additionalBytes) {
            "Skill folder contains too much data."
        }
    }

    fun recordBytes(count: Long) {
        requireCapacity(count)
        bytes += count
    }
}

class AgentSkillManager(
    private val context: Context,
    private val extensionsRepository: AgentExtensionsRepository,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun installSkillFromDirectory(
        treeUri: Uri,
        label: String = treeUri.toString(),
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDirectory = createTempDirectory()
            try {
                val root = copyDocumentTree(treeUri, workingDirectory)
                val skillRoot = locateSkillRoot(root = root)
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = SkillInstallKind.DocumentTree,
                        label = label,
                        uri = treeUri.toString(),
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun installSkillFromZipUri(
        zipUri: Uri,
        label: String = zipUri.toString(),
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDirectory = createTempDirectory()
            try {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    unzipIntoDirectory(input, workingDirectory)
                } ?: error("Couldn't open the selected zip file.")
                val skillRoot = locateSkillRoot(root = workingDirectory)
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = SkillInstallKind.ZipUri,
                        label = label,
                        uri = zipUri.toString(),
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun installSkillFromRemote(
        rawUrl: String,
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val plan = resolveRemoteDownloadPlan(rawUrl)
            val workingDirectory = createTempDirectory()
            try {
                downloadZipIntoDirectory(plan.downloadUrl, workingDirectory)
                val skillRoot = locateSkillRoot(
                    root = workingDirectory,
                    requestedSubpath = plan.subpath,
                )
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = plan.kind,
                        label = rawUrl,
                        uri = rawUrl,
                        ref = plan.ref,
                        subpath = plan.subpath,
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun uninstallSkill(skillId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val skill = extensionsRepository.extensionState.firstValue()
                .installedSkills
                .firstOrNull { it.id == skillId }
                ?: return@runCatching
            val root = validatedInstalledSkillRoot(skill)
            if (root == null) {
                extensionsRepository.removeInstalledSkill(skillId)
            } else {
                removeSkillDirectoryTransaction(root) {
                    extensionsRepository.removeInstalledSkill(skillId)
                }
            }
        }
    }

    suspend fun buildActiveSkillContext(
        skill: InstalledSkill,
    ): Result<ActiveSkillContext> = withContext(Dispatchers.IO) {
        runCatching {
            val root = validatedInstalledSkillRoot(skill)
                ?: error("Installed skill path is invalid.")
            val skillFile = validatedSkillMarkdownFile(skill, root)
                ?: error("Installed skill metadata does not point at its bundled SKILL.md.")
            val parsed = parseSkillDocument(skillFile)
            ActiveSkillContext(
                skillId = skill.id,
                name = parsed.name,
                description = parsed.description,
                compatibility = parsed.compatibility,
                allowedTools = parsed.allowedTools,
                skillRootPath = root.absolutePath,
                bodyMarkdown = parsed.bodyMarkdown,
                resourceEntries = skill.resourceEntries,
            )
        }
    }

    fun installedSkillsDirectory(): File = File(context.filesDir, SkillStorageDirectoryName).apply {
        mkdirs()
    }

    fun exportSkillBundles(skills: List<InstalledSkill>): JSONArray =
        JSONArray().apply {
            skills.sortedBy { it.name.lowercase(Locale.US) }
                .mapNotNull { skill -> runCatching { exportSkillBundle(skill) }.getOrNull() }
                .forEach(::put)
        }

    suspend fun importSkillBundles(bundles: JSONArray?): List<InstalledSkill> = withContext(Dispatchers.IO) {
        if (bundles == null) return@withContext emptyList()
        buildList {
            for (index in 0 until bundles.length()) {
                val bundle = bundles.optJSONObject(index) ?: continue
                val installedSkill = runCatching { importSkillBundle(bundle) }.getOrNull() ?: continue
                add(installedSkill)
            }
        }.sortedBy { it.name.lowercase(Locale.US) }
    }

    private suspend fun installParsedSkill(
        sourceRoot: File,
        source: SkillInstallSource,
    ): InstalledSkill {
        val skillFile = File(sourceRoot, SkillFileName)
        require(skillFile.isFile) { "The selected directory does not contain SKILL.md." }
        val parsed = parseSkillDocument(skillFile)
        val skillId = buildSkillId(parsed.name)
        val storageRoot = installedSkillsDirectory()
        val installRoot = File(storageRoot, skillId)
        val stagingRoot = File(storageRoot, ".staging-$skillId-${UUID.randomUUID()}")
        require(stagingRoot.mkdir()) { "Couldn't create the skill staging directory." }
        try {
            sourceRoot.copyRecursively(stagingRoot, overwrite = false)
            val checksum = sha256OfDirectory(stagingRoot)
            return replaceSkillDirectoryTransaction(
                stagingRoot = stagingRoot,
                installRoot = installRoot,
            ) {
                val installedSkill = InstalledSkill(
                    id = skillId,
                    name = parsed.name,
                    description = parsed.description,
                    actionLabel = generateQuickActionLabel(parsed.name, parsed.description),
                    license = parsed.license,
                    compatibility = parsed.compatibility,
                    metadataJson = parsed.metadataJson,
                    allowedTools = parsed.allowedTools,
                    skillRootPath = installRoot.absolutePath,
                    skillMdPath = File(installRoot, SkillFileName).absolutePath,
                    source = source,
                    checksumSha256 = checksum,
                    diagnostics = parsed.diagnostics,
                    resourceEntries = listSkillResources(installRoot),
                )
                extensionsRepository.upsertInstalledSkill(installedSkill)
                installedSkill
            }
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun locateSkillRoot(
        root: File,
        requestedSubpath: String = "",
    ): File {
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.name.equals(SkillFileName, ignoreCase = true) }
            .mapNotNull { it.parentFile }
            .toList()
        if (candidates.isEmpty()) {
            error("No SKILL.md file was found in the selected source.")
        }
        if (requestedSubpath.isBlank()) {
            return candidates.sortedBy { it.absolutePath.length }.first()
        }

        val normalizedSubpath = requestedSubpath.trim('/').replace('\\', '/')
        return candidates.firstOrNull { candidate ->
            candidate.relativeTo(root).invariantSeparatorsPath.endsWith(normalizedSubpath)
        } ?: candidates.sortedBy { it.absolutePath.length }.first()
    }

    private fun parseSkillDocument(skillFile: File): ParsedSkillDocument {
        val text = skillFile.readText()
        val (frontmatterText, bodyMarkdown) = splitFrontmatter(text)
        val diagnostics = mutableListOf<String>()
        val frontmatter = parseFrontmatter(frontmatterText, diagnostics)

        val name = readScalar(frontmatter, "name").ifBlank {
            extractFallbackScalar(frontmatterText, "name")
        }
        val description = readScalar(frontmatter, "description").ifBlank {
            extractFallbackScalar(frontmatterText, "description")
        }

        require(name.isNotBlank()) { "Skill frontmatter is missing 'name'." }
        require(description.isNotBlank()) { "Skill frontmatter is missing 'description'." }

        return ParsedSkillDocument(
            name = name,
            description = description,
            license = readScalar(frontmatter, "license"),
            compatibility = readFlexibleScalar(frontmatter, "compatibility"),
            metadataJson = readJsonValue(frontmatter["metadata"]),
            allowedTools = readStringList(frontmatter["allowed-tools"]),
            bodyMarkdown = bodyMarkdown.trim(),
            diagnostics = diagnostics,
        )
    }

    private fun splitFrontmatter(text: String): Pair<String, String> {
        if (!text.startsWith("---")) {
            return "" to text
        }
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return "" to text
        }
        val frontmatterLines = mutableListOf<String>()
        for (index in 1 until lines.size) {
            if (lines[index].trim() == "---") {
                val body = lines.drop(index + 1).joinToString("\n")
                return frontmatterLines.joinToString("\n") to body
            }
            frontmatterLines += lines[index]
        }
        return "" to text
    }

    private fun parseFrontmatter(
        rawFrontmatter: String,
        diagnostics: MutableList<String>,
    ): Map<String, Any?> {
        if (rawFrontmatter.isBlank()) return emptyMap()
        return runCatching {
            val loaderOptions = LoaderOptions().apply {
                isAllowDuplicateKeys = false
            }
            val yaml = Yaml(SafeConstructor(loaderOptions))
            @Suppress("UNCHECKED_CAST")
            yaml.load<Map<String, Any?>>(rawFrontmatter) ?: emptyMap()
        }.getOrElse { throwable ->
            diagnostics += throwable.message ?: "Couldn't parse SKILL.md frontmatter."
            emptyMap()
        }
    }

    private fun readScalar(
        frontmatter: Map<String, Any?>,
        key: String,
    ): String = frontmatter[key]?.toString()?.trim().orEmpty()

    private fun readFlexibleScalar(
        frontmatter: Map<String, Any?>,
        key: String,
    ): String {
        val value = frontmatter[key] ?: return ""
        return when (value) {
            is String -> value.trim()
            else -> readJsonValue(value)
        }
    }

    private fun readStringList(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim()).filter { it.isNotEmpty() }
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun extractFallbackScalar(
        frontmatterText: String,
        key: String,
    ): String {
        if (frontmatterText.isBlank()) return ""
        val prefix = "$key:"
        return frontmatterText.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.trim('"')
            ?.trim('\'')
            .orEmpty()
    }

    private fun readJsonValue(value: Any?): String = when (value) {
        null -> "{}"
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value).toString()
        is Iterable<*> -> JSONArray(value.toList()).toString()
        else -> JSONObject.wrap(value)?.toString() ?: value.toString()
    }

    private fun listSkillResources(skillRoot: File): List<SkillResourceEntry> =
        skillRoot.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(skillRoot).invariantSeparatorsPath
                SkillResourceEntry(
                    relativePath = relativePath,
                    kind = SkillResourceKind.fromRelativePath(relativePath),
                )
            }
            .sortedBy { it.relativePath }
            .toList()

    private fun createTempDirectory(): File =
        File(context.cacheDir, SkillTempDirectoryName)
            .apply { mkdirs() }
            .resolve(UUID.randomUUID().toString())
            .apply { mkdirs() }

    private fun copyDocumentTree(
        treeUri: Uri,
        destinationRoot: File,
    ): File {
        val rootDocument = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Couldn't open the selected folder.")
        val rootName = requireSafeSkillDocumentName(rootDocument.name ?: "imported-skill")
        val targetRoot = resolveConfinedSkillPath(destinationRoot, rootName)
        copyDocumentRecursively(
            document = rootDocument,
            destination = targetRoot,
            budget = SkillImportBudget(),
            visitedDirectories = mutableSetOf(),
        )
        return targetRoot
    }

    private fun copyDocumentRecursively(
        document: androidx.documentfile.provider.DocumentFile,
        destination: File,
        budget: SkillImportBudget,
        visitedDirectories: MutableSet<String>,
    ) {
        budget.recordEntry()
        if (document.isDirectory) {
            require(visitedDirectories.add(document.uri.toString())) {
                "The selected folder contains a repeated or cyclic directory reference."
            }
            require(destination.mkdir()) { "Couldn't create imported skill directory ${destination.name}." }
            val childNames = mutableSetOf<String>()
            document.listFiles().forEach { child ->
                val childName = requireSafeSkillDocumentName(
                    child.name ?: error("A selected skill entry has no file name."),
                )
                require(childNames.add(childName)) {
                    "The selected folder contains duplicate entry name: $childName"
                }
                copyDocumentRecursively(
                    document = child,
                    destination = resolveConfinedSkillPath(destination, childName),
                    budget = budget,
                    visitedDirectories = visitedDirectories,
                )
            }
            return
        }

        val declaredLength = document.length()
        if (declaredLength > 0L) {
            require(declaredLength <= MaxSkillEntryBytes) {
                "Skill file is too large: ${destination.name}"
            }
            budget.requireCapacity(declaredLength)
        }
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    entryBytes += read.toLong()
                    require(entryBytes <= MaxSkillEntryBytes) {
                        "Skill file is too large: ${destination.name}"
                    }
                    budget.requireCapacity(read.toLong())
                    output.write(buffer, 0, read)
                    budget.recordBytes(read.toLong())
                }
            }
        } ?: error("Couldn't read ${document.uri}.")
    }

    private fun unzipIntoDirectory(
        input: InputStream,
        destinationRoot: File,
    ) {
        val canonicalRoot = destinationRoot.canonicalPath + File.separator
        var entryCount = 0
        var totalExtractedBytes = 0L
        ZipInputStream(LimitedInputStream(input, MaxSkillArchiveBytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MaxSkillZipEntries) {
                    "Skill archive contains too many files."
                }
                val relativePath = normalizeSkillRelativePath(
                    rawPath = entry.name,
                    allowTrailingSlash = entry.isDirectory,
                )
                val output = resolveConfinedSkillPath(destinationRoot, relativePath)
                val canonicalOutput = output.canonicalPath
                require(canonicalOutput.startsWith(canonicalRoot)) {
                    "Zip entry escaped the destination directory: ${entry.name}"
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    totalExtractedBytes += copyZipEntryWithLimits(
                        zipInput = zipInput,
                        outputFile = output,
                        entryName = entry.name,
                        extractedBeforeEntry = totalExtractedBytes,
                    )
                }
                zipInput.closeEntry()
            }
        }
    }

    private fun resolveRemoteDownloadPlan(rawUrl: String): RemoteDownloadPlan {
        val url = rawUrl.trim().ifBlank { error("A remote skill URL is required.") }
        val httpUrl = url.toHttpUrlOrNull() ?: error("Skill URL is not a valid absolute URL.")
        if (httpUrl.host.equals("github.com", ignoreCase = true)) {
            val segments = httpUrl.pathSegments.filter { it.isNotBlank() }
            require(segments.size >= 2) { "GitHub URL must include owner and repository." }
            val owner = segments[0]
            val repository = segments[1].removeSuffix(".git")
            if (segments.size >= 4 && segments[2] == "tree") {
                val ref = segments[3]
                val subpath = segments.drop(4).joinToString("/")
                return RemoteDownloadPlan(
                    kind = SkillInstallKind.GitHub,
                    downloadUrl = "https://api.github.com/repos/$owner/$repository/zipball/$ref",
                    ref = ref,
                    subpath = subpath,
                )
            }
            return RemoteDownloadPlan(
                kind = SkillInstallKind.GitHub,
                downloadUrl = "https://api.github.com/repos/$owner/$repository/zipball",
            )
        }
        require(httpUrl.encodedPath.lowercase(Locale.US).endsWith(".zip")) {
            "Remote skill URL must be a GitHub repository/tree URL or a direct .zip file."
        }
        return RemoteDownloadPlan(
            kind = SkillInstallKind.RemoteZip,
            downloadUrl = httpUrl.toString(),
        )
    }

    private fun downloadZipIntoDirectory(
        url: String,
        destinationRoot: File,
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/zip, application/octet-stream")
            .addHeader("User-Agent", "Aether Android Agent")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Skill download failed with HTTP ${response.code}.")
            }
            val body = response.body ?: error("Skill download returned an empty body.")
            val contentLength = body.contentLength()
            if (contentLength > MaxSkillArchiveBytes) {
                error("Skill archive is too large.")
            }
            body.byteStream().use { input ->
                unzipIntoDirectory(input, destinationRoot)
            }
        }
    }

    private fun buildSkillId(name: String): String =
        name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "skill-${UUID.randomUUID()}" }

    private fun sha256OfDirectory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray())
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun exportSkillBundle(skill: InstalledSkill): JSONObject? {
        val root = validatedInstalledSkillRoot(skill) ?: return null
        validatedSkillMarkdownFile(skill, root) ?: return null
        var entryCount = 0
        var totalBytes = 0L
        val files = JSONArray()
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                entryCount += 1
                require(entryCount <= MaxSkillZipEntries) {
                    "Skill contains too many bundled files."
                }
                val length = file.length()
                require(length <= MaxSkillEntryBytes) {
                    "Skill file is too large: ${file.name}"
                }
                totalBytes += length
                require(totalBytes <= MaxSkillExtractedBytes) {
                    "Skill bundle is too large."
                }
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                files.put(
                    JSONObject().apply {
                        put("path", relativePath)
                        put("dataBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                    }
                )
            }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("id", skill.id)
            put("name", skill.name)
            put("actionLabel", skill.actionLabel)
            put("isEnabled", skill.isEnabled)
            put("installedAtMillis", skill.installedAtMillis)
            put("source", skill.source.toJson())
            put("files", files)
        }
    }

    private suspend fun importSkillBundle(bundle: JSONObject): InstalledSkill {
        val workingDirectory = createTempDirectory()
        return try {
            val sourceRoot = workingDirectory.resolve("skill").apply { mkdirs() }
            val files = bundle.optJSONArray("files") ?: error("Skill bundle did not contain files.")
            var totalBytes = 0L
            for (index in 0 until files.length()) {
                require(index < MaxSkillZipEntries) {
                    "Skill bundle contains too many files."
                }
                val fileJson = files.optJSONObject(index) ?: continue
                val relativePath = normalizeSkillRelativePath(fileJson.optString("path"))
                val data = Base64.decode(fileJson.optString("dataBase64"), Base64.DEFAULT)
                require(data.size.toLong() <= MaxSkillEntryBytes) {
                    "Skill file is too large: $relativePath"
                }
                totalBytes += data.size.toLong()
                require(totalBytes <= MaxSkillExtractedBytes) {
                    "Skill bundle is too large."
                }
                val output = sourceRoot.resolve(relativePath)
                val canonicalRoot = sourceRoot.canonicalPath + File.separator
                val canonicalOutput = output.canonicalPath
                require(canonicalOutput.startsWith(canonicalRoot)) {
                    "Skill bundle entry escaped the destination directory: $relativePath"
                }
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { it.write(data) }
            }
            val installed = installParsedSkill(
                sourceRoot = sourceRoot,
                source = parseBundleSkillInstallSource(bundle.optJSONObject("source")),
            )
            val restored = installed.copy(
                actionLabel = bundle.optString("actionLabel").ifBlank { installed.actionLabel },
                isEnabled = bundle.optBoolean("isEnabled", installed.isEnabled),
                installedAtMillis = bundle.optLong("installedAtMillis", installed.installedAtMillis),
                updatedAtMillis = System.currentTimeMillis(),
            )
            extensionsRepository.upsertInstalledSkill(restored)
            restored
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun validatedInstalledSkillRoot(skill: InstalledSkill): File? {
        val storageRoot = installedSkillsDirectory().canonicalFile
        val root = runCatching { File(skill.skillRootPath).canonicalFile }.getOrNull() ?: return null
        val parent = runCatching { root.parentFile?.canonicalFile }.getOrNull() ?: return null
        if (parent != storageRoot || !root.isDirectory) return null
        return root
    }

    private fun validatedSkillMarkdownFile(
        skill: InstalledSkill,
        root: File,
    ): File? {
        val expected = File(root, SkillFileName).canonicalFile
        val actual = runCatching { File(skill.skillMdPath).canonicalFile }.getOrNull() ?: return null
        if (actual != expected || !actual.isFile) return null
        return actual
    }

    private fun copyZipEntryWithLimits(
        zipInput: ZipInputStream,
        outputFile: File,
        entryName: String,
        extractedBeforeEntry: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        FileOutputStream(outputFile).use { output ->
            while (true) {
                val read = zipInput.read(buffer)
                if (read < 0) break
                entryBytes += read.toLong()
                require(entryBytes <= MaxSkillEntryBytes) {
                    "Skill archive entry is too large: $entryName"
                }
                require(extractedBeforeEntry + entryBytes <= MaxSkillExtractedBytes) {
                    "Skill archive expands to too much data."
                }
                output.write(buffer, 0, read)
            }
        }
        return entryBytes
    }

    private fun parseBundleSkillInstallSource(json: JSONObject?): SkillInstallSource =
        SkillInstallSource(
            kind = SkillInstallKind.fromStorage(json?.optString("kind")),
            label = json?.optString("label").orEmpty(),
            uri = json?.optString("uri").orEmpty(),
            ref = json?.optString("ref").orEmpty(),
            subpath = json?.optString("subpath").orEmpty(),
        )

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
}

private class LimitedInputStream(
    input: InputStream,
    private val limitBytes: Long,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            countBytes(1)
        }
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            countBytes(read.toLong())
        }
        return read
    }

    private fun countBytes(count: Long) {
        bytesRead += count
        require(bytesRead <= limitBytes) {
            "Skill archive is too large."
        }
    }
}

private data class ParsedSkillDocument(
    val name: String,
    val description: String,
    val license: String,
    val compatibility: String,
    val metadataJson: String,
    val allowedTools: List<String>,
    val bodyMarkdown: String,
    val diagnostics: List<String>,
)

private data class RemoteDownloadPlan(
    val kind: SkillInstallKind,
    val downloadUrl: String,
    val ref: String = "",
    val subpath: String = "",
)
