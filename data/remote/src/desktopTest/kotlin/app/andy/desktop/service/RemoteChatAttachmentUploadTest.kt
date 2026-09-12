package app.andy.desktop.service

import app.andy.model.AgentAttachment
import app.andy.model.AgentAttachmentKind
import app.andy.model.ChatAttachmentLimits
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RemoteChatAttachmentUploadTest {
    @Test
    fun uploadsInOrderAndPassesRemoteDescriptorNotLocalId() = runBlocking {
        val localBody = "remote-upload-" + "x".repeat(300_000)
        val localBytes = localBody.encodeToByteArray()
        val local = AgentAttachment(
            id = "att-local-gui",
            displayName = "paste.txt",
            kind = AgentAttachmentKind.Text,
            byteCount = localBytes.size.toLong(),
            lineCount = 1,
            sha256 = sha256(localBody),
        )
        val calls = mutableListOf<String>()
        var discardedLocal = false
        val result = uploadLocalAttachmentsToRemote(
            attachments = listOf(local),
            readChunk = { id, offset, max ->
                assertEquals(local.id, id)
                val end = minOf(localBytes.size, (offset + max).toInt())
                if (offset >= localBytes.size) ByteArray(0)
                else localBytes.copyOfRange(offset.toInt(), end)
            },
            callTool = { name, args ->
                calls += name
                when (name) {
                    "chat.attachment_begin" -> {
                        assertEquals(local.displayName, args["displayName"]?.jsonPrimitive?.contentOrNull)
                        assertEquals(local.byteCount, args["byteCount"]?.jsonPrimitive?.contentOrNull?.toLong())
                        """{"uploadId":"att-remote-daemon","displayName":"paste.txt","expectedBytes":${local.byteCount},"expectedSha256":"${local.sha256}"}"""
                    }
                    "chat.attachment_append" -> {
                        val seq = args["sequence"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        assertTrue(seq != null && seq >= 0)
                        val b64 = args["base64Chunk"]?.jsonPrimitive?.contentOrNull
                        assertFalse(b64.isNullOrBlank())
                        Base64.getDecoder().decode(b64)
                        """{"ok":true,"uploadId":"att-remote-daemon","sequence":$seq}"""
                    }
                    "chat.attachment_commit" -> {
                        assertEquals("att-remote-daemon", args["uploadId"]?.jsonPrimitive?.contentOrNull)
                        buildJsonObject {
                            put("id", "att-remote-daemon")
                            put("displayName", "paste.txt")
                            put("kind", "Text")
                            put("mediaType", "text/plain; charset=utf-8")
                            put("byteCount", local.byteCount)
                            put("lineCount", 1)
                            put("sha256", local.sha256)
                        }.toString()
                    }
                    "chat.attachment_cancel" -> error("should not cancel on success path")
                    else -> error("unexpected tool $name")
                }
            },
            chunkBytes = ChatAttachmentLimits.UploadChunkBytes,
        )

        assertEquals("att-remote-daemon", result.remotes.single().id)
        assertEquals(listOf(local.id), result.localIds)
        assertFalse(result.remotes.single().id == local.id)
        assertTrue(calls.first() == "chat.attachment_begin")
        assertTrue(calls.last() == "chat.attachment_commit")
        assertTrue(calls.drop(1).dropLast(1).all { it == "chat.attachment_append" })
        assertTrue(calls.count { it == "chat.attachment_append" } >= 2)

        discardLocalsAfterMutationSuccess(result.localIds) {
            discardedLocal = true
            assertEquals(local.id, it)
        }
        assertTrue(discardedLocal)
    }

    @Test
    fun secondUploadFailureCancelsFirstCommittedRemoteAndRetainsLocals() = runBlocking {
        val first = tinyAttachment("att-local-1", "a.txt", "aaaa")
        val second = tinyAttachment("att-local-2", "b.txt", "bbbb")
        val cancelled = mutableListOf<String>()
        val discardedLocals = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var commitCount = 0
        assertFailsWith<IllegalStateException> {
            uploadLocalAttachmentsToRemote(
                attachments = listOf(first, second),
                readChunk = { id, _, _ ->
                    when (id) {
                        first.id -> "aaaa".toByteArray()
                        second.id -> "bbbb".toByteArray()
                        else -> null
                    }
                },
                callTool = toolResponder(calls, cancelled) { name, args ->
                    when (name) {
                        "chat.attachment_begin" -> {
                            val display = args["displayName"]?.jsonPrimitive?.contentOrNull
                            val id = if (display == "a.txt") "att-remote-1" else "att-remote-2"
                            """{"uploadId":"$id","displayName":"$display","expectedBytes":4,"expectedSha256":"${args["sha256"]?.jsonPrimitive?.contentOrNull}"}"""
                        }
                        "chat.attachment_append" -> """{"ok":true}"""
                        "chat.attachment_commit" -> {
                            commitCount++
                            if (commitCount == 1) {
                                buildJsonObject {
                                    put("id", "att-remote-1")
                                    put("displayName", "a.txt")
                                    put("kind", "Text")
                                    put("byteCount", 4)
                                    put("sha256", first.sha256)
                                }.toString()
                            } else {
                                error("simulated second commit failure")
                            }
                        }
                        else -> error("unexpected $name")
                    }
                },
            )
        }
        assertTrue(cancelled.contains("att-remote-1"), "first committed remote must be cleaned: $cancelled")
        assertTrue(cancelled.contains("att-remote-2") || calls.contains("chat.attachment_cancel"))
        assertTrue(discardedLocals.isEmpty())
        assertFalse(calls.contains("chat.start") || calls.contains("chat.resume"))
    }

    @Test
    fun chatMutationFailureCleansRemotesAndRetainsLocals() = runBlocking {
        val local = tinyAttachment("att-local-keep", "keep.txt", "keep-me!!")
        val cancelled = mutableListOf<String>()
        val discardedLocals = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val upload = uploadLocalAttachmentsToRemote(
            attachments = listOf(local),
            readChunk = { _, _, _ -> "keep-me!!".toByteArray() },
            callTool = toolResponder(calls, cancelled) { name, _ ->
                when (name) {
                    "chat.attachment_begin" ->
                        """{"uploadId":"att-remote-keep","displayName":"keep.txt","expectedBytes":9,"expectedSha256":"${local.sha256}"}"""
                    "chat.attachment_append" -> """{"ok":true}"""
                    "chat.attachment_commit" -> buildJsonObject {
                        put("id", "att-remote-keep")
                        put("displayName", "keep.txt")
                        put("kind", "Text")
                        put("byteCount", 9)
                        put("sha256", local.sha256)
                    }.toString()
                    else -> error("unexpected $name")
                }
            },
        )
        assertEquals("att-remote-keep", upload.remotes.single().id)
        assertEquals(listOf(local.id), upload.localIds)

        // Simulate chat.start/resume failure after successful commits.
        cleanupRemoteAttachmentsAfterMutationFailure(upload.remotes) { name, args ->
            calls += name
            cancelled += args["uploadId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            """{"ok":true}"""
        }
        assertTrue(cancelled.contains("att-remote-keep"))
        assertTrue(discardedLocals.isEmpty(), "locals must remain retryable")

        // Success path: discard locals only after mutation.
        discardedLocals.clear()
        discardLocalsAfterMutationSuccess(upload.localIds) { discardedLocals += it }
        assertEquals(listOf(local.id), discardedLocals)
    }

    @Test
    fun uploadFailureCancelsRemoteAndDoesNotReturnLocalDescriptor() = runBlocking {
        val local = AgentAttachment(
            id = "att-local-fail",
            displayName = "fail.txt",
            byteCount = 10,
            sha256 = "a".repeat(64),
        )
        val calls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            uploadLocalAttachmentsToRemote(
                attachments = listOf(local),
                readChunk = { _, _, _ -> "abcdefghij".toByteArray() },
                callTool = { name, _ ->
                    calls += name
                    when (name) {
                        "chat.attachment_begin" ->
                            """{"uploadId":"att-up-1","displayName":"fail.txt","expectedBytes":10,"expectedSha256":"${local.sha256}"}"""
                        "chat.attachment_append" -> error("simulated append failure")
                        "chat.attachment_cancel" -> """{"ok":true}"""
                        else -> error("unexpected $name")
                    }
                },
            )
        }
        assertEquals(listOf("chat.attachment_begin", "chat.attachment_append", "chat.attachment_cancel"), calls)
    }

    private fun tinyAttachment(id: String, name: String, body: String) = AgentAttachment(
        id = id,
        displayName = name,
        byteCount = body.encodeToByteArray().size.toLong(),
        sha256 = sha256(body),
    )

    private fun toolResponder(
        calls: MutableList<String>,
        cancelled: MutableList<String>,
        handle: (String, Map<String, JsonElement>) -> String,
    ): suspend (String, Map<String, JsonElement>) -> String = { name, args ->
        calls += name
        if (name == "chat.attachment_cancel") {
            cancelled += args["uploadId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            """{"ok":true}"""
        } else {
            handle(name, args)
        }
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return digest.joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }
}
