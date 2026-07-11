package com.zhousl.aether.data

import com.zhousl.aether.ui.AttachmentKind
import com.zhousl.aether.ui.ChatAttachment
import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatToolInvocation
import com.zhousl.aether.ui.MessageAuthor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExecutionManagerRequestMessageTest {
    @Test
    fun emptyMessagesAreNotIncludedInLlmRequests() {
        assertFalse(
            shouldIncludeMessageInLlmRequest(
                ChatMessage(
                    id = "empty-user",
                    author = MessageAuthor.User,
                    text = "   ",
                )
            )
        )
        assertFalse(
            shouldIncludeMessageInLlmRequest(
                ChatMessage(
                    id = "tool-only-agent",
                    author = MessageAuthor.Agent,
                    text = "",
                    toolInvocations = listOf(
                        ChatToolInvocation(
                            id = "tool-1",
                            toolName = "stock_market_data",
                            argumentsJson = "{}",
                        )
                    ),
                )
            )
        )
    }

    @Test
    fun textOrAttachmentsAreIncludedInLlmRequests() {
        assertTrue(
            shouldIncludeMessageInLlmRequest(
                ChatMessage(
                    id = "text-user",
                    author = MessageAuthor.User,
                    text = "hello",
                )
            )
        )
        assertTrue(
            shouldIncludeMessageInLlmRequest(
                ChatMessage(
                    id = "attachment-user",
                    author = MessageAuthor.User,
                    text = "",
                    attachments = listOf(
                        ChatAttachment(
                            id = "attachment-1",
                            uri = "content://example/report.txt",
                            name = "report.txt",
                            mimeType = "text/plain",
                            sizeBytes = 12L,
                            kind = AttachmentKind.File,
                            workspacePath = "/tmp/report.txt",
                        )
                    ),
                )
            )
        )
    }
}
