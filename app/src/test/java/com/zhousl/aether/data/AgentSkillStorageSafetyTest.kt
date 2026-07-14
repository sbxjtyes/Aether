package com.zhousl.aether.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillStorageSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `document names reject path separators and traversal segments`() {
        listOf("..", ".", "../escape", "folder/file", "folder\\file", "\u0000bad").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSafeSkillDocumentName(name)
            }
        }
        assertEquals("SKILL.md", requireSafeSkillDocumentName("SKILL.md"))
    }

    @Test
    fun `bundle paths reject absolute traversal and ambiguous segments`() {
        listOf("../escape", "a/../escape", "/absolute", "C:/absolute", "a//b", "a/./b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeSkillRelativePath(path)
            }
        }
        assertEquals("assets/icon.png", normalizeSkillRelativePath("assets\\icon.png"))
    }

    @Test
    fun `failed skill replacement restores previous directory`() = runBlocking {
        val parent = temporaryFolder.newFolder("skills")
        val installed = File(parent, "demo").apply { mkdir(); resolve("version").writeText("old") }
        val staging = File(parent, ".staging-demo").apply { mkdir(); resolve("version").writeText("new") }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                replaceSkillDirectoryTransaction(staging, installed) {
                    error("metadata write failed")
                }
            }
        }

        assertEquals("old", installed.resolve("version").readText())
        assertFalse(staging.exists())
        assertTrue(parent.listFiles().orEmpty().none { it.name.startsWith(".backup-demo-") })
    }

    @Test
    fun `failed skill removal restores quarantined directory`() = runBlocking {
        val parent = temporaryFolder.newFolder("remove")
        val installed = File(parent, "demo").apply { mkdir(); resolve("version").writeText("current") }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                removeSkillDirectoryTransaction(installed) {
                    error("metadata removal failed")
                }
            }
        }

        assertEquals("current", installed.resolve("version").readText())
        assertTrue(parent.listFiles().orEmpty().none { it.name.startsWith(".removing-demo-") })
    }
}
