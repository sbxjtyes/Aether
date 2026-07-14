package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatSession
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryConcurrencyTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val directories = mutableListOf<File>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        directories.forEach(File::deleteRecursively)
    }

    @Test
    fun unchangedSaveRepairsSessionFileDeletedAfterPreviousWrite() = runBlocking {
        val fixture = fixture()
        val session = session("one", "Original")
        fixture.repository.chatState.first()
        fixture.repository.updateChatState(listOf(session), session.id)
        val sessionFile = fixture.directory.sessionFiles().single()
        assertTrue(sessionFile.delete())

        fixture.repository.updateChatState(listOf(session), session.id)

        assertTrue("An unchanged state must recreate a missing session file", sessionFile.isFile)
        assertEquals("Original", parseChatSessionObject(org.json.JSONObject(sessionFile.readText())).title)
    }

    @Test
    fun unchangedSaveRepairsCorruptSessionFileInsteadOfTrustingStaleCache() = runBlocking {
        val fixture = fixture()
        val session = session("one", "Original")
        fixture.repository.chatState.first()
        fixture.repository.updateChatState(listOf(session), session.id)
        val sessionFile = fixture.directory.sessionFiles().single()
        sessionFile.writeText("{not-json")

        fixture.repository.updateChatState(listOf(session), session.id)

        assertEquals("Original", parseChatSessionObject(org.json.JSONObject(sessionFile.readText())).title)
    }

    @Test
    fun concurrentWritesLeavePublishedStateEqualToDurableState() = runBlocking {
        val fixture = fixture()
        fixture.repository.chatState.first()
        val start = CompletableDeferred<Unit>()
        coroutineScope {
            repeat(100) { index ->
                launch(Dispatchers.Default) {
                    start.await()
                    val session = session("one", "Version $index")
                    fixture.repository.updateChatState(listOf(session), session.id)
                }
            }
            start.complete(Unit)
        }

        val published = fixture.repository.chatState.first()
        // Use the same durable directory with a fresh cache to compare against disk.
        val reloaded = ChatRepository(fixture.directory, newScope()).chatState.first()

        assertEquals(reloaded, published)
    }

    private fun fixture(): Fixture {
        val directory = kotlin.io.path.createTempDirectory("chat-repository-").toFile()
        directories += directory
        val scope = newScope()
        return Fixture(
            directory = directory,
            scope = scope,
            repository = ChatRepository(directory, scope),
        )
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also(scopes::add)

    private fun session(id: String, title: String) = ChatSession(
        id = id,
        title = title,
        preview = title,
        messages = emptyList(),
    )

    private fun File.sessionFiles(): List<File> =
        listFiles().orEmpty().filter { it.name.startsWith("s_") && it.name.endsWith(".json") }

    private data class Fixture(
        val directory: File,
        val scope: CoroutineScope,
        val repository: ChatRepository,
    )
}
