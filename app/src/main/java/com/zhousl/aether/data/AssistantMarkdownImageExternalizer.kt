package com.zhousl.aether.data

import com.zhousl.aether.ui.buildAssistantLocalFileLink
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal data class ExternalizedMarkdownDataImagesResult(
    val markdown: String,
    val replacementCount: Int,
)

internal class AssistantMarkdownImageExternalizer(
    private val workspaceFileBridge: WorkspaceFileBridge,
) {
    suspend fun externalize(
        sessionId: String,
        markdown: String,
    ): ExternalizedMarkdownDataImagesResult =
        externalizeMarkdownDataImages(
            markdown = markdown,
            workspaceDirectory = workspaceFileBridge.workspaceDirectory(sessionId),
        ) { absolutePath, bytes ->
            workspaceFileBridge.writeWorkspaceBytes(
                absolutePath = absolutePath,
                bytes = bytes,
            ).isSuccess
        }
}

internal fun markdownMayContainDataImage(markdown: String): Boolean =
    markdown.contains("data:image/", ignoreCase = true) &&
        markdown.contains("![", ignoreCase = false)

internal suspend fun externalizeMarkdownDataImages(
    markdown: String,
    workspaceDirectory: String,
    buildLocalFileLink: (absolutePath: String) -> String = ::buildAssistantLocalFileLink,
    writeImage: suspend (absolutePath: String, bytes: ByteArray) -> Boolean,
): ExternalizedMarkdownDataImagesResult {
    if (!markdownMayContainDataImage(markdown)) {
        return ExternalizedMarkdownDataImagesResult(markdown = markdown, replacementCount = 0)
    }

    val replacements = mutableListOf<MarkdownImageReplacement>()
    val successfulLinksByFileName = mutableMapOf<String, String>()
    var searchIndex = 0
    while (searchIndex < markdown.length) {
        val imageStart = markdown.indexOf("![", startIndex = searchIndex)
        if (imageStart < 0) break
        val occurrence = findMarkdownDataImageOccurrence(markdown, imageStart)
        if (occurrence == null) {
            searchIndex = imageStart + 2
            continue
        }

        val hash = sha256Hex(occurrence.bytes)
        val extension = imageExtensionForMimeType(occurrence.mimeType)
        val fileName = "generated-$hash.$extension"
        var link = successfulLinksByFileName[fileName]
        if (link == null) {
            val absolutePath = "${workspaceDirectory.trimEnd('/', '\\')}/generated/$fileName"
            val didWrite = try {
                writeImage(absolutePath, occurrence.bytes)
            } catch (_: Throwable) {
                false
            }
            if (!didWrite) {
                searchIndex = occurrence.destinationEndExclusive
                continue
            }
            link = buildLocalFileLink(absolutePath)
            successfulLinksByFileName[fileName] = link
        }
        replacements += MarkdownImageReplacement(
            start = occurrence.destinationStart,
            endExclusive = occurrence.destinationEndExclusive,
            replacement = link,
        )
        searchIndex = occurrence.destinationEndExclusive
    }

    if (replacements.isEmpty()) {
        return ExternalizedMarkdownDataImagesResult(markdown = markdown, replacementCount = 0)
    }

    val builder = StringBuilder(markdown.length)
    var copiedUntil = 0
    replacements.forEach { replacement ->
        builder.append(markdown, copiedUntil, replacement.start)
        builder.append(replacement.replacement)
        copiedUntil = replacement.endExclusive
    }
    builder.append(markdown, copiedUntil, markdown.length)
    return ExternalizedMarkdownDataImagesResult(
        markdown = builder.toString(),
        replacementCount = replacements.size,
    )
}

private data class MarkdownDataImageOccurrence(
    val destinationStart: Int,
    val destinationEndExclusive: Int,
    val mimeType: String,
    val bytes: ByteArray,
)

private data class MarkdownImageReplacement(
    val start: Int,
    val endExclusive: Int,
    val replacement: String,
)

private data class MarkdownDestinationRange(
    val start: Int,
    val endExclusive: Int,
    val scanAfterDestination: Int,
)

private fun findMarkdownDataImageOccurrence(
    markdown: String,
    imageStart: Int,
): MarkdownDataImageOccurrence? {
    val altEnd = findUnescapedChar(markdown, ']', imageStart + 2) ?: return null
    if (altEnd + 1 >= markdown.length || markdown[altEnd + 1] != '(') return null
    val destination = findMarkdownImageDestination(markdown, altEnd + 2) ?: return null
    findUnescapedChar(markdown, ')', destination.scanAfterDestination) ?: return null

    val rawDestination = markdown.substring(destination.start, destination.endExclusive)
    val dataImage = parseDataImageUrl(rawDestination) ?: return null
    return MarkdownDataImageOccurrence(
        destinationStart = destination.start,
        destinationEndExclusive = destination.endExclusive,
        mimeType = dataImage.mimeType,
        bytes = dataImage.bytes,
    )
}

private fun findMarkdownImageDestination(
    markdown: String,
    start: Int,
): MarkdownDestinationRange? {
    var index = start
    while (index < markdown.length && markdown[index].isWhitespace()) index += 1
    if (index >= markdown.length) return null

    if (markdown[index] == '<') {
        val destinationStart = index + 1
        val destinationEnd = markdown.indexOf('>', startIndex = destinationStart)
        if (destinationEnd < 0 || destinationEnd == destinationStart) return null
        return MarkdownDestinationRange(
            start = destinationStart,
            endExclusive = destinationEnd,
            scanAfterDestination = destinationEnd + 1,
        )
    }

    val destinationStart = index
    while (
        index < markdown.length &&
        markdown[index] != ')' &&
        !markdown[index].isWhitespace()
    ) {
        index += 1
    }
    if (index == destinationStart) return null
    return MarkdownDestinationRange(
        start = destinationStart,
        endExclusive = index,
        scanAfterDestination = index,
    )
}

private data class ParsedDataImage(
    val mimeType: String,
    val bytes: ByteArray,
)

private fun parseDataImageUrl(rawDestination: String): ParsedDataImage? {
    if (!rawDestination.startsWith("data:image/", ignoreCase = true)) return null
    val commaIndex = rawDestination.indexOf(',')
    if (commaIndex <= "data:".length) return null
    val metadata = rawDestination.substring("data:".length, commaIndex)
    val metadataParts = metadata.split(';')
    val mimeType = metadataParts.firstOrNull()
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.startsWith("image/") }
        ?: return null
    if (metadataParts.none { it.equals("base64", ignoreCase = true) }) return null
    val payload = rawDestination.substring(commaIndex + 1).filterNot(Char::isWhitespace)
    val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    return ParsedDataImage(
        mimeType = mimeType,
        bytes = bytes,
    )
}

private fun findUnescapedChar(
    text: String,
    target: Char,
    start: Int,
): Int? {
    var index = start
    while (index < text.length) {
        if (text[index] == target && !text.isEscaped(index)) return index
        index += 1
    }
    return null
}

private fun String.isEscaped(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount += 1
        cursor -= 1
    }
    return slashCount % 2 == 1
}

private fun imageExtensionForMimeType(mimeType: String): String = when (
    mimeType.substringBefore(';').trim().lowercase(Locale.US)
) {
    "image/png" -> "png"
    "image/jpeg",
    "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    else -> "bin"
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
