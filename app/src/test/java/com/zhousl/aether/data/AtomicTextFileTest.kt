package com.zhousl.aether.data

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTextFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `replacement receives a complete flushed temporary file`() {
        val target = temporaryFolder.newFile("session.json").apply { writeText("old") }
        var stagedContent = ""

        writeTextAtomically(target, "new complete state") { source, destination ->
            stagedContent = source.toFile().readText()
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }

        assertEquals("new complete state", stagedContent)
        assertEquals("new complete state", target.readText())
        assertFalse(target.parentFile.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `replacement failure preserves old file and removes temporary file`() {
        val target = temporaryFolder.newFile("index.json").apply { writeText("old valid index") }

        assertThrows(IOException::class.java) {
            writeTextAtomically(target, "new index") { _, _ ->
                throw IOException("injected replacement failure")
            }
        }

        assertEquals("old valid index", target.readText())
        assertFalse(target.parentFile.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `default replacement atomically replaces an existing file`() {
        val target = temporaryFolder.newFile("chat.json").apply { writeText("before") }

        writeTextAtomically(target, "after")

        assertEquals("after", target.readText())
    }
}
