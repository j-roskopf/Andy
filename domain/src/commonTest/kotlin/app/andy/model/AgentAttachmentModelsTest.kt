package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentAttachmentModelsTest {
    @Test
    fun shouldAttachWhenOverByteBudget() {
        val text = "x".repeat(ChatAttachmentLimits.InlineMaxBytes.toInt() + 1)
        assertTrue(shouldAttachLargeText(text))
    }

    @Test
    fun shouldAttachWhenOverLineBudget() {
        val text = (1..(ChatAttachmentLimits.InlineMaxLines.toInt() + 1)).joinToString("\n") { "line" }
        assertTrue(shouldAttachLargeText(text))
        assertTrue(utf8ByteCount(text) < ChatAttachmentLimits.InlineMaxBytes)
    }

    @Test
    fun smallAsciiPasteStaysInline() {
        assertFalse(shouldAttachLargeText("hello world"))
        assertFalse(shouldAttachLargeText(""))
    }

    @Test
    fun unicodeByteCountExceedsCharLength() {
        val text = "你".repeat(30_000) // 3 bytes each in UTF-8
        assertTrue(utf8ByteCount(text) > text.length)
        assertTrue(shouldAttachLargeText(text))
    }

    @Test
    fun promptHintsNeverEmbedBody() {
        val body = "SECRET_BODY_" + "x".repeat(1000)
        val attachment = AgentAttachment(
            id = "att-1",
            displayName = "pasted.txt",
            byteCount = body.length.toLong(),
            lineCount = 1,
            sha256 = "a".repeat(64),
            relativePath = ".andy/task/attachments/pasted.txt",
        )
        val prompt = promptWithAttachmentHints("Please inspect", listOf(attachment))
        assertTrue(prompt.contains("Please inspect"))
        assertTrue(prompt.contains(".andy/task/attachments/pasted.txt"))
        assertTrue(prompt.contains(attachment.sha256))
        assertFalse(prompt.contains("SECRET_BODY_"))
        assertTrue(prompt.length < 2_000)
    }

    @Test
    fun attachmentOnlyPromptSynthesizesInstruction() {
        val attachment = AgentAttachment(
            id = "att-2",
            displayName = "dump.txt",
            byteCount = 100,
            lineCount = 4,
            sha256 = "b".repeat(64),
            relativePath = ".andy/t/attachments/dump.txt",
        )
        val prompt = promptWithAttachmentHints("", listOf(attachment))
        assertTrue(prompt.contains("no additional prose"))
        assertTrue(prompt.contains("dump.txt"))
    }

    @Test
    fun boundedPreviewKeepsComposeLayoutSafe() {
        val huge = "y".repeat(20_000)
        val preview = boundedUserMessagePreview(huge, maxChars = 100)
        assertTrue(preview.length < 300)
        assertTrue(preview.contains("truncated"))
        assertEquals(100, preview.indexOf('\n').let { if (it < 0) preview.length else it }.coerceAtMost(100))
    }

    @Test
    fun formatAttachmentByteCountReadable() {
        assertEquals("100 B", formatAttachmentByteCount(100))
        assertEquals("8.0 MB", formatAttachmentByteCount(8L * 1024 * 1024))
    }
}
