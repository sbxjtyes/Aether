package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMarkdownImageExternalizerTest {
    @Test
    fun base64MarkdownImageBecomesLocalFileLink() = runBlocking {
        val writes = mutableListOf<Pair<String, ByteArray>>()

        val result = externalizeMarkdownDataImages(
            markdown = "Here:\n![Generated image](data:image/png;base64,YWJj)",
            workspaceDirectory = "/workspace/session",
            buildLocalFileLink = { path -> "local://$path" },
        ) { path, bytes ->
            writes += path to bytes
            true
        }

        assertEquals(1, result.replacementCount)
        assertFalse(result.markdown.contains("data:image/png", ignoreCase = true))
        assertTrue(result.markdown.contains("![Generated image](local:///workspace/session/generated/generated-"))
        assertTrue(writes.single().first.endsWith(".png"))
        assertArrayEquals("abc".toByteArray(), writes.single().second)
    }

    @Test
    fun duplicateImagesReuseHashFileName() = runBlocking {
        val writtenPaths = mutableListOf<String>()
        val markdown = "![one](data:image/png;base64,YWJj)\n![two](data:image/png;base64,YWJj)"

        val result = externalizeMarkdownDataImages(
            markdown = markdown,
            workspaceDirectory = "/workspace/session",
            buildLocalFileLink = { path -> "local://$path" },
        ) { path, _ ->
            writtenPaths += path
            true
        }

        assertEquals(2, result.replacementCount)
        assertEquals(1, writtenPaths.size)
        assertEquals(2, Regex("local:///workspace/session/generated/generated-").findAll(result.markdown).count())
    }

    @Test
    fun nonDataUrlImageIsUnchanged() = runBlocking {
        val markdown = "![remote](https://example.com/image.png)"

        val result = externalizeMarkdownDataImages(
            markdown = markdown,
            workspaceDirectory = "/workspace/session",
            buildLocalFileLink = { path -> "local://$path" },
        ) { _, _ -> error("Should not write") }

        assertEquals(0, result.replacementCount)
        assertEquals(markdown, result.markdown)
    }

    @Test
    fun failedWritePreservesOriginalMarkdown() = runBlocking {
        val markdown = "![Generated image](data:image/png;base64,YWJj)"

        val result = externalizeMarkdownDataImages(
            markdown = markdown,
            workspaceDirectory = "/workspace/session",
            buildLocalFileLink = { path -> "local://$path" },
        ) { _, _ -> false }

        assertEquals(0, result.replacementCount)
        assertEquals(markdown, result.markdown)
    }
}
