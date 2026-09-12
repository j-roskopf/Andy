package app.andy.model

import kotlinx.serialization.Serializable

/** Extensible attachment kinds; start with text files staged from large pastes. */
@Serializable
enum class AgentAttachmentKind {
    Text,
}

/**
 * Managed, disk-backed attachment descriptor. Bodies never live in task JSON, transcripts,
 * MCP payloads, or provider argv — only this metadata plus a task-local relative path.
 */
@Serializable
data class AgentAttachment(
    val id: String,
    val displayName: String,
    val kind: AgentAttachmentKind = AgentAttachmentKind.Text,
    val mediaType: String = "text/plain; charset=utf-8",
    val byteCount: Long,
    val lineCount: Long? = null,
    val sha256: String,
    /**
     * Path relative to the task cwd once materialized, e.g.
     * `.andy/<taskId>/attachments/pasted-….txt`. Null while still staged pre-launch.
     */
    val relativePath: String? = null,
)

/** Safety and conversion limits for large pasted / uploaded text. */
object ChatAttachmentLimits {
    /** UTF-8 byte budget before paste becomes a managed attachment. */
    const val InlineMaxBytes: Long = 64L * 1024

    /** Line budget before paste becomes a managed attachment. */
    const val InlineMaxLines: Long = 2_000

    /** Hard cap for a single text attachment. */
    const val MaxAttachmentBytes: Long = 512L * 1024 * 1024

    /** Cap on total staged bytes owned by one composer draft. */
    const val MaxStagedBytesPerDraft: Long = 1024L * 1024 * 1024

    /** Approx Base64 chunk size for remote/Web uploads (~256 KiB decoded). */
    const val UploadChunkBytes: Int = 256 * 1024

    /** Compose layout guard for legacy huge inline user messages. */
    const val TranscriptPreviewMaxChars: Int = 4_096

    /** Fast path: if Kotlin string length exceeds this, UTF-8 must exceed [InlineMaxBytes]. */
    const val InlineMaxCharsFastPath: Int = InlineMaxBytes.toInt()
}

/**
 * True when [text] should become a managed text attachment instead of living in a TextField.
 * Uses char-length as a fast reject, then line count, then UTF-8 byte length.
 */
fun shouldAttachLargeText(text: String): Boolean {
    if (text.isEmpty()) return false
    if (text.length > ChatAttachmentLimits.InlineMaxCharsFastPath) return true
    val lineCount = countTextLines(text)
    if (lineCount > ChatAttachmentLimits.InlineMaxLines) return true
    return utf8ByteCount(text) > ChatAttachmentLimits.InlineMaxBytes
}

/** Line count matching common text-file conventions (empty → 0; otherwise newlines + 1). */
fun countTextLines(text: String): Long {
    if (text.isEmpty()) return 0L
    var lines = 1L
    for (ch in text) {
        if (ch == '\n') lines++
    }
    return lines
}

/** Exact UTF-8 byte length of [text]. */
fun utf8ByteCount(text: String): Long = text.encodeToByteArray().size.toLong()

/** Human-readable size for chips and transcript rows. */
fun formatAttachmentByteCount(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024 * 1024 -> {
        val tenths = (bytes * 10) / (1024L * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
    else -> {
        val hundredths = (bytes * 100) / (1024L * 1024 * 1024)
        "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
    }
}

/** Compact chip / row subtitle: size and optional line count. */
fun AgentAttachment.metadataLabel(): String = buildString {
    append(formatAttachmentByteCount(byteCount))
    lineCount?.takeIf { it > 0 }?.let { append(" · "); append(it); append(" lines") }
}

/**
 * Builds a short provider-facing hint so the agent inspects the file with bounded reads.
 * Never embeds attachment bodies.
 */
fun promptWithAttachmentHints(text: String, attachments: List<AgentAttachment>): String {
    if (attachments.isEmpty()) return text
    val body = text.trim()
    val instruction = buildString {
        if (body.isNotEmpty()) {
            append(body)
            append("\n\n")
        } else {
            append(
                "The user attached managed text file(s) with no additional prose. " +
                    "Inspect them with bounded reads/searches (never assume the full file fits in context).\n\n",
            )
        }
        append("Managed text attachment")
        if (attachments.size != 1) append('s')
        append(" (read incrementally with offset/limit or search — do not load entire files into one turn):\n")
        attachments.forEach { attachment ->
            val path = attachment.relativePath?.takeIf { it.isNotBlank() }
                ?: "(pending materialization; id=${attachment.id})"
            append("- ").append(attachment.displayName).append(": ").append(path)
            append(" (").append(formatAttachmentByteCount(attachment.byteCount))
            attachment.lineCount?.let { append(", ").append(it).append(" lines") }
            append(", sha256=").append(attachment.sha256).append(")\n")
        }
    }.trimEnd()
    return instruction
}

/** Compose-safe preview for legacy huge inline user messages. */
fun boundedUserMessagePreview(text: String, maxChars: Int = ChatAttachmentLimits.TranscriptPreviewMaxChars): String {
    if (text.length <= maxChars) return text
    val omitted = text.length - maxChars
    return text.take(maxChars) +
        "\n\n… [message truncated for display: $omitted more characters — use Copy to access the full text]"
}

/** True when the composer can send with only attachments (no prose / images / skills). */
fun hasSendableAttachments(attachments: List<AgentAttachment>): Boolean =
    attachments.any { it.kind == AgentAttachmentKind.Text && it.byteCount > 0 }
