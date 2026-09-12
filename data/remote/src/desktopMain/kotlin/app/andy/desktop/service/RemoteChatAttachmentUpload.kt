package app.andy.desktop.service

import app.andy.model.AgentAttachment
import app.andy.model.AgentAttachmentKind
import app.andy.model.ChatAttachmentLimits
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Result of streaming local staged attachments to a remote andyd. Locals are retained until the
 * caller confirms the final chat mutation succeeded ([discardLocalsAfterMutationSuccess]).
 */
internal data class RemoteAttachmentUploadResult(
    val remotes: List<AgentAttachment>,
    val localIds: List<String>,
)

/**
 * Streams locally staged managed attachments to a remote andyd via
 * `chat.attachment_begin` / `append` / `commit` in bounded chunks.
 *
 * Does **not** discard local sources — callers must call [discardLocalsAfterMutationSuccess]
 * only after chat.start / resume / queue_follow_up succeeds. On any upload failure, every
 * remote upload or committed staged id created by this attempt is cancelled/discarded and
 * locals are left intact for retry.
 */
internal suspend fun uploadLocalAttachmentsToRemote(
    attachments: List<AgentAttachment>,
    readChunk: suspend (attachmentId: String, offset: Long, maxBytes: Int) -> ByteArray?,
    callTool: suspend (name: String, arguments: Map<String, JsonElement>) -> String,
    chunkBytes: Int = ChatAttachmentLimits.UploadChunkBytes,
): RemoteAttachmentUploadResult {
    if (attachments.isEmpty()) return RemoteAttachmentUploadResult(emptyList(), emptyList())
    val committedRemoteIds = mutableListOf<String>()
    val remotes = mutableListOf<AgentAttachment>()
    try {
        for (local in attachments) {
            var uploadId: String? = null
            var committed = false
            try {
                val beginRaw = callTool(
                    "chat.attachment_begin",
                    buildMap {
                        put("displayName", JsonPrimitive(local.displayName))
                        put("byteCount", JsonPrimitive(local.byteCount))
                        put("sha256", JsonPrimitive(local.sha256))
                        put("mediaType", JsonPrimitive(local.mediaType))
                    },
                )
                uploadId = parseUploadId(beginRaw)
                    ?: error("chat.attachment_begin did not return uploadId")
                var offset = 0L
                var sequence = 0
                while (offset < local.byteCount) {
                    val chunk = readChunk(local.id, offset, chunkBytes)
                        ?: error("Staged attachment missing during upload: ${local.id}")
                    if (chunk.isEmpty()) break
                    callTool(
                        "chat.attachment_append",
                        mapOf(
                            "uploadId" to JsonPrimitive(uploadId),
                            "sequence" to JsonPrimitive(sequence),
                            "base64Chunk" to JsonPrimitive(Base64.getEncoder().encodeToString(chunk)),
                        ),
                    )
                    offset += chunk.size
                    sequence++
                }
                if (offset != local.byteCount) {
                    error("Uploaded $offset of ${local.byteCount} bytes for ${local.displayName}")
                }
                val commitRaw = callTool(
                    "chat.attachment_commit",
                    mapOf("uploadId" to JsonPrimitive(uploadId)),
                )
                val remote = parseAttachmentDescriptor(commitRaw)
                committed = true
                committedRemoteIds += remote.id
                remotes += remote
            } catch (error: Exception) {
                if (!committed) {
                    uploadId?.let { id -> cancelRemoteAttachment(callTool, id) }
                }
                throw IllegalStateException(
                    "Failed to upload attachment ${local.displayName}: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
        }
        return RemoteAttachmentUploadResult(
            remotes = remotes,
            localIds = attachments.map { it.id },
        )
    } catch (error: Exception) {
        committedRemoteIds.forEach { id -> cancelRemoteAttachment(callTool, id) }
        throw error
    }
}

/** Best-effort cancel for in-progress or committed-but-unsent remote staging. */
internal suspend fun cancelRemoteAttachment(
    callTool: suspend (name: String, arguments: Map<String, JsonElement>) -> String,
    attachmentOrUploadId: String,
) {
    runCatching {
        callTool(
            "chat.attachment_cancel",
            mapOf("uploadId" to JsonPrimitive(attachmentOrUploadId)),
        )
    }
}

/** After a successful chat mutation, drop GUI-local staging copies. */
internal suspend fun discardLocalsAfterMutationSuccess(
    localIds: List<String>,
    discardLocal: suspend (attachmentId: String) -> Unit,
) {
    localIds.forEach { id -> runCatching { discardLocal(id) } }
}

/** After a failed chat mutation, drop every remote staging id created for that attempt. */
internal suspend fun cleanupRemoteAttachmentsAfterMutationFailure(
    remotes: List<AgentAttachment>,
    callTool: suspend (name: String, arguments: Map<String, JsonElement>) -> String,
) {
    remotes.forEach { remote -> cancelRemoteAttachment(callTool, remote.id) }
}

private fun parseUploadId(raw: String): String? =
    runCatching {
        Json.parseToJsonElement(raw).jsonObject["uploadId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

internal fun parseAttachmentDescriptor(raw: String): AgentAttachment {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("attachment commit missing id")
    val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("attachment commit missing displayName")
    val byteCount = obj["byteCount"]?.jsonPrimitive?.longOrNull
        ?: error("attachment commit missing byteCount")
    val sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("attachment commit missing sha256")
    return AgentAttachment(
        id = id,
        displayName = displayName,
        kind = AgentAttachmentKind.entries.firstOrNull {
            it.name.equals(obj["kind"]?.jsonPrimitive?.contentOrNull, ignoreCase = true)
        } ?: AgentAttachmentKind.Text,
        mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull
            ?: "text/plain; charset=utf-8",
        byteCount = byteCount,
        lineCount = obj["lineCount"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
        sha256 = sha256.lowercase(),
        // Inbound remote descriptors never carry a GUI-relative path.
        relativePath = null,
    )
}
