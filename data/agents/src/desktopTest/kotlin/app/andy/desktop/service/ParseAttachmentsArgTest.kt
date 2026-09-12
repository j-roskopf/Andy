package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopChatAttachmentService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ParseAttachmentsArgTest {
    @Test
    fun ignoresClientProvidedRelativePathOnInboundDescriptors() {
        val args = mapOf(
            "attachments" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "att-abc123")
                        put("displayName", "paste.txt")
                        put("byteCount", 100)
                        put("sha256", "ab".repeat(32))
                        put("relativePath", "../../etc/passwd")
                    },
                ),
            ),
        )
        val parsed = parseAttachmentsArg(args)
        assertEquals(1, parsed.size)
        assertNull(parsed.single().relativePath)
        assertEquals("att-abc123", parsed.single().id)
    }

    @Test
    fun acceptsManagedDescriptorWithoutRelativePath() {
        val args = mapOf(
            "attachments" to JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "att-xyz789")
                        put("displayName", "big.txt")
                        put("byteCount", 2048)
                        put("sha256", "cd".repeat(32))
                        put("lineCount", 40)
                    },
                ),
            ),
        )
        val parsed = parseAttachmentsArg(args).single()
        assertNull(parsed.relativePath)
        assertEquals(2048L, parsed.byteCount)
        assertEquals(40L, parsed.lineCount)
        assertTrue(
            DesktopChatAttachmentService.isSafeTaskRelativeAttachmentPath(
                ".andy/task-1/attachments/big.txt",
                "task-1",
            ),
        )
        assertFalse(
            DesktopChatAttachmentService.isSafeTaskRelativeAttachmentPath(
                ".andy/other/attachments/big.txt",
                "task-1",
            ),
        )
        assertFalse(
            DesktopChatAttachmentService.isSafeTaskRelativeAttachmentPath(
                "/abs/path",
                "task-1",
            ),
        )
        assertFalse(
            DesktopChatAttachmentService.isSafeTaskRelativeAttachmentPath(
                ".andy/task-1/attachments/../secrets.txt",
                "task-1",
            ),
        )
    }
}
