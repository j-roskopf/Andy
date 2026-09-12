package app.andy.service

import app.andy.model.AgentAttachment
import app.andy.model.ChatAttachmentLimits

/**
 * Stages large pasted / uploaded text as managed disk-backed attachments.
 * Descriptors are safe to persist; bodies live only on disk under Andy-managed roots.
 */
interface ChatAttachmentService {
    /**
     * Writes [text] to a staging file (buffered, hashed in one pass) and returns a descriptor.
     * Rejects payloads over [ChatAttachmentLimits.MaxAttachmentBytes] or when the draft budget
     * would exceed [ChatAttachmentLimits.MaxStagedBytesPerDraft].
     */
    suspend fun stageText(
        text: String,
        displayName: String = "pasted.txt",
        draftKey: String? = null,
    ): Result<AgentAttachment>

    /** Removes a staged attachment that was never bound to a task (composer discard). */
    suspend fun discardStaged(attachmentId: String): Boolean

    /**
     * Makes [attachments] readable under `<cwd>/.andy/<taskId>/attachments/`, preferring a
     * hard link when possible. Returns descriptors with [AgentAttachment.relativePath] set.
     */
    suspend fun materializeForTask(
        taskId: String,
        cwd: String?,
        attachments: List<AgentAttachment>,
    ): List<AgentAttachment>

    /** Deletes staged files owned by [draftKey] (abandoned composer). */
    suspend fun discardDraft(draftKey: String)

    /** Best-effort cleanup of abandoned staging uploads older than [maxAgeMillis]. */
    suspend fun expireAbandonedStaging(maxAgeMillis: Long = 24L * 60 * 60 * 1000)

    /** Bounded authenticated upload: begin a new staging session. */
    suspend fun beginUpload(
        displayName: String,
        byteCount: Long,
        sha256: String,
        mediaType: String = "text/plain; charset=utf-8",
        draftKey: String? = null,
    ): Result<ChatAttachmentUploadSession>

    /** Appends a Base64 chunk; [sequence] must be monotonic from 0. */
    suspend fun appendUploadChunk(
        uploadId: String,
        sequence: Int,
        base64Chunk: String,
    ): Result<Unit>

    /** Verifies size/hash and finalizes into a staged [AgentAttachment]. */
    suspend fun commitUpload(uploadId: String): Result<AgentAttachment>

    /** Cancels an in-progress upload and deletes partial bytes. */
    suspend fun cancelUpload(uploadId: String): Boolean

    /**
     * Reads up to [maxBytes] from a locally staged attachment body starting at [offset].
     * Used by the GUI→daemon remote upload path so large bodies never enter a single RPC.
     * Returns null when the staged file is missing; empty array at/after EOF.
     */
    suspend fun readStagedChunk(attachmentId: String, offset: Long, maxBytes: Int): ByteArray?

    /**
     * When a final provider prompt exceeds the inline budget, spills it to the task attachment
     * directory and returns a short path hint. Otherwise returns [prompt] unchanged.
     */
    suspend fun spilloverPromptIfNeeded(
        taskId: String,
        cwd: String?,
        prompt: String,
    ): String
}

data class ChatAttachmentUploadSession(
    val uploadId: String,
    val displayName: String,
    val expectedBytes: Long,
    val expectedSha256: String,
)

object UnavailableChatAttachmentService : ChatAttachmentService {
    override suspend fun stageText(
        text: String,
        displayName: String,
        draftKey: String?,
    ): Result<AgentAttachment> = Result.failure(
        UnsupportedOperationException("Managed text attachments are unavailable on this platform."),
    )

    override suspend fun discardStaged(attachmentId: String): Boolean = false

    override suspend fun materializeForTask(
        taskId: String,
        cwd: String?,
        attachments: List<AgentAttachment>,
    ): List<AgentAttachment> = attachments

    override suspend fun discardDraft(draftKey: String) = Unit

    override suspend fun expireAbandonedStaging(maxAgeMillis: Long) = Unit

    override suspend fun beginUpload(
        displayName: String,
        byteCount: Long,
        sha256: String,
        mediaType: String,
        draftKey: String?,
    ): Result<ChatAttachmentUploadSession> = Result.failure(
        UnsupportedOperationException("Managed text attachments are unavailable on this platform."),
    )

    override suspend fun appendUploadChunk(
        uploadId: String,
        sequence: Int,
        base64Chunk: String,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Managed text attachments are unavailable on this platform."),
    )

    override suspend fun commitUpload(uploadId: String): Result<AgentAttachment> = Result.failure(
        UnsupportedOperationException("Managed text attachments are unavailable on this platform."),
    )

    override suspend fun cancelUpload(uploadId: String): Boolean = false

    override suspend fun readStagedChunk(attachmentId: String, offset: Long, maxBytes: Int): ByteArray? = null

    override suspend fun spilloverPromptIfNeeded(
        taskId: String,
        cwd: String?,
        prompt: String,
    ): String = prompt
}
