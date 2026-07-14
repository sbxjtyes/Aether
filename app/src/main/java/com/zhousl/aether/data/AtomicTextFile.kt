package com.zhousl.aether.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [content] without ever opening [target] for an in-place overwrite.
 *
 * The complete payload is flushed to a temporary file in the target directory first. Replacing
 * the destination is therefore a same-filesystem rename, so a failed write or replacement leaves
 * the previous file intact instead of truncating it.
 */
internal fun writeTextAtomically(
    target: File,
    content: String,
    replace: (source: Path, target: Path) -> Unit = ::replacePathAtomically,
) {
    val absoluteTarget = target.absoluteFile
    val parent = requireNotNull(absoluteTarget.parentFile) {
        "Atomic file target must have a parent directory: $target"
    }
    check((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
        "Unable to create atomic file directory: $parent"
    }

    val prefix = ".${absoluteTarget.name}.".padEnd(3, '_')
    val temporary = File.createTempFile(prefix, ".tmp", parent)
    try {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        replace(temporary.toPath(), absoluteTarget.toPath())
    } finally {
        // A successful move makes this a no-op; failures must not leave an ever-growing temp set.
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun replacePathAtomically(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        // A same-directory provider rename is still safer than opening and truncating the target.
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
