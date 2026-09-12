package app.andy.desktop.service.agents

import app.andy.model.AgentAttachment
import app.andy.model.AgentAttachmentKind
import app.andy.model.ChatAttachmentLimits
import app.andy.model.countTextLines
import app.andy.model.shouldAttachLargeText
import app.andy.service.ChatAttachmentService
import app.andy.service.ChatAttachmentUploadSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed chat text attachments.
 *
 * Staging lives under `~/.andy/attachments-staging/<id>/`. Once a task has a cwd, the
 * authoritative readable copy is hard-linked (or copied) to
 * `<cwd>/.andy/<taskId>/attachments/<safe-name>` so ACP's cwd jail can read it.
 */
class DesktopChatAttachmentService(
    private val stagingRoot: File = File(System.getProperty("user.home"), ".andy/attachments-staging"),
    /** Injectable for tests; production uses [ChatAttachmentLimits.MaxStagedBytesPerDraft]. */
    private val maxStagedBytesPerDraft: Long = ChatAttachmentLimits.MaxStagedBytesPerDraft,
) : ChatAttachmentService {
    private val mutex = Mutex()
    /** Committed staged bytes + in-flight upload reservations, keyed by draft. */
    private val draftBytes = ConcurrentHashMap<String, Long>()
    private val uploads = ConcurrentHashMap<String, UploadState>()
    private val stagedMeta = ConcurrentHashMap<String, StagedMeta>()

    /** Test helper: current reserved/committed draft byte total. */
    internal fun draftByteTotal(draftKey: String): Long = draftBytes[draftKey] ?: 0L

    override suspend fun stageText(
        text: String,
        displayName: String,
        draftKey: String?,
    ): Result<AgentAttachment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val bytes = text.encodeToByteArray()
                if (bytes.size.toLong() > ChatAttachmentLimits.MaxAttachmentBytes) {
                    error(
                        "Attachment exceeds ${ChatAttachmentLimits.MaxAttachmentBytes / (1024 * 1024)} MiB limit " +
                            "(${bytes.size} bytes).",
                    )
                }
                draftKey?.let { key ->
                    val next = (draftBytes[key] ?: 0L) + bytes.size
                    if (next > maxStagedBytesPerDraft) {
                        error("Draft attachment budget exceeded (1 GiB). Remove an attachment and try again.")
                    }
                }
                ensureFreeSpace(bytes.size.toLong())
                val id = newAttachmentId()
                val safeName = sanitizeFileName(displayName)
                val dir = File(stagingRoot, id).also { it.mkdirs() }
                val target = File(dir, safeName)
                val tmp = File(dir, ".$safeName.tmp")
                val digest = MessageDigest.getInstance("SHA-256")
                var lineCount = 0L
                var prevWasNewline = true
                BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + 64 * 1024, bytes.size)
                        val slice = bytes.copyOfRange(offset, end)
                        digest.update(slice)
                        out.write(slice)
                        for (b in slice) {
                            when (b) {
                                '\n'.code.toByte() -> {
                                    lineCount++
                                    prevWasNewline = true
                                }
                                else -> prevWasNewline = false
                            }
                        }
                        offset = end
                    }
                }
                if (bytes.isNotEmpty() && !prevWasNewline) lineCount++
                if (bytes.isEmpty()) lineCount = 0
                if (!tmp.renameTo(target)) {
                    target.delete()
                    check(tmp.renameTo(target)) { "Failed to finalize staged attachment." }
                }
                val sha = digest.digest().toHex()
                val attachment = AgentAttachment(
                    id = id,
                    displayName = safeName,
                    kind = AgentAttachmentKind.Text,
                    mediaType = "text/plain; charset=utf-8",
                    byteCount = bytes.size.toLong(),
                    lineCount = lineCount,
                    sha256 = sha,
                    relativePath = null,
                )
                stagedMeta[id] = StagedMeta(draftKey = draftKey, file = target, byteCount = attachment.byteCount)
                draftKey?.let { draftBytes.merge(it, attachment.byteCount) { a, b -> a + b } }
                writeSidecar(dir, attachment)
                attachment
            }
        }
    }

    override suspend fun discardStaged(attachmentId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { discardStagedLocked(attachmentId) }
    }

    override suspend fun materializeForTask(
        taskId: String,
        cwd: String?,
        attachments: List<AgentAttachment>,
    ): List<AgentAttachment> = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext emptyList()
        mutex.withLock {
            val destRoot = taskAttachmentsDir(cwd, taskId).also { it.mkdirs() }
            val createdDests = mutableListOf<File>()
            val toRelease = mutableListOf<String>()
            try {
                val results = attachments.map { attachment ->
                    if (!isSafeId(attachment.id)) {
                        error("Invalid attachment id: ${attachment.id}")
                    }
                    // Prefer an already-materialized task-relative copy so retry/restart works
                    // without depending on in-memory stagedMeta. Only reuse paths that
                    // canonicalize inside this task's attachments directory.
                    val reusable = resolveReusableMaterializedFile(cwd, taskId, attachment)
                    if (reusable != null) {
                        verifyIntegrity(reusable, attachment)
                        val relative = ".andy/$taskId/attachments/${reusable.name}"
                        return@map attachment.copy(displayName = reusable.name, relativePath = relative)
                    }
                    val source = resolveStagedFile(attachment)
                        ?: error("Staged attachment not found: ${attachment.id}")
                    verifyIntegrity(source, attachment)
                    val safeName = uniqueSafeName(destRoot, attachment.displayName)
                    val dest = File(destRoot, safeName)
                    hardLinkOrCopy(source, dest)
                    createdDests += dest
                    verifyIntegrity(dest, attachment.copy(displayName = safeName))
                    val relative = ".andy/$taskId/attachments/$safeName"
                    toRelease += attachment.id
                    attachment.copy(displayName = safeName, relativePath = relative)
                }
                // Release staging only after every attachment materialized successfully.
                toRelease.forEach { releaseStagedAfterMaterialize(it) }
                results
            } catch (error: Exception) {
                createdDests.forEach { dest -> runCatching { dest.delete() } }
                throw error
            }
        }
    }

    override suspend fun discardDraft(draftKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = stagedMeta.filterValues { it.draftKey == draftKey }.keys.toList()
            ids.forEach { id ->
                stagedMeta.remove(id)
                File(stagingRoot, id).deleteRecursively()
            }
            val inFlight = uploads.filterValues { it.draftKey == draftKey }.keys.toList()
            inFlight.forEach { id ->
                uploads.remove(id)
                File(stagingRoot, id).deleteRecursively()
            }
            draftBytes.remove(draftKey)
            Unit
        }
    }

    override suspend fun expireAbandonedStaging(maxAgeMillis: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!stagingRoot.isDirectory) return@withLock
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            stagingRoot.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dir.lastModified() in 1 until cutoff) {
                    val id = dir.name
                    uploads.remove(id)?.let { state ->
                        releaseDraftBytes(state.draftKey, state.expectedBytes)
                    }
                    stagedMeta.remove(id)?.let { meta ->
                        releaseDraftBytes(meta.draftKey, meta.byteCount)
                    }
                    dir.deleteRecursively()
                }
            }
        }
    }

    override suspend fun beginUpload(
        displayName: String,
        byteCount: Long,
        sha256: String,
        mediaType: String,
        draftKey: String?,
    ): Result<ChatAttachmentUploadSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (byteCount <= 0L) error("byteCount must be positive")
                if (byteCount > ChatAttachmentLimits.MaxAttachmentBytes) {
                    error("Attachment exceeds ${ChatAttachmentLimits.MaxAttachmentBytes / (1024 * 1024)} MiB limit.")
                }
                if (!sha256.matches(SHA256_HEX)) error("Invalid sha256")
                // Reserve declared bytes immediately so concurrent begins cannot exceed the budget.
                draftKey?.let { key ->
                    val next = (draftBytes[key] ?: 0L) + byteCount
                    if (next > maxStagedBytesPerDraft) {
                        error("Draft attachment budget exceeded (1 GiB).")
                    }
                    draftBytes[key] = next
                }
                try {
                    ensureFreeSpace(byteCount)
                    val uploadId = newAttachmentId()
                    val safeName = sanitizeFileName(displayName)
                    val dir = File(stagingRoot, uploadId).also { it.mkdirs() }
                    val partial = File(dir, ".$safeName.partial")
                    partial.outputStream().close()
                    uploads[uploadId] = UploadState(
                        uploadId = uploadId,
                        displayName = safeName,
                        expectedBytes = byteCount,
                        expectedSha256 = sha256.lowercase(),
                        mediaType = mediaType,
                        draftKey = draftKey,
                        partialFile = partial,
                        nextSequence = 0,
                        writtenBytes = 0L,
                        digest = MessageDigest.getInstance("SHA-256"),
                        lineCount = 0L,
                        prevWasNewline = true,
                        reservationHeld = draftKey != null,
                    )
                    ChatAttachmentUploadSession(uploadId, safeName, byteCount, sha256.lowercase())
                } catch (error: Exception) {
                    draftKey?.let { releaseDraftBytes(it, byteCount) }
                    throw error
                }
            }
        }
    }

    override suspend fun appendUploadChunk(
        uploadId: String,
        sequence: Int,
        base64Chunk: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val state = uploads[uploadId] ?: error("Unknown upload id")
                if (sequence != state.nextSequence) {
                    error("Out-of-order chunk: expected ${state.nextSequence}, got $sequence")
                }
                val decoded = try {
                    Base64.getDecoder().decode(base64Chunk)
                } catch (_: IllegalArgumentException) {
                    error("Invalid Base64 chunk")
                }
                if (decoded.isEmpty()) error("Empty chunk")
                if (decoded.size > ChatAttachmentLimits.UploadChunkBytes + 4096) {
                    error("Chunk exceeds upload size limit")
                }
                val nextBytes = state.writtenBytes + decoded.size
                if (nextBytes > state.expectedBytes) {
                    error("Upload exceeds declared byteCount")
                }
                FileOutputStream(state.partialFile, true).use { it.write(decoded) }
                state.digest.update(decoded)
                for (b in decoded) {
                    when (b) {
                        '\n'.code.toByte() -> {
                            state.lineCount++
                            state.prevWasNewline = true
                        }
                        else -> state.prevWasNewline = false
                    }
                }
                state.writtenBytes = nextBytes
                state.nextSequence++
                Unit
            }
        }
    }

    override suspend fun commitUpload(uploadId: String): Result<AgentAttachment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val state = uploads.remove(uploadId) ?: error("Unknown upload id")
                fun fail(message: String): Nothing {
                    if (state.reservationHeld) {
                        releaseDraftBytes(state.draftKey, state.expectedBytes)
                    }
                    state.partialFile.parentFile?.deleteRecursively()
                    error(message)
                }
                if (state.writtenBytes != state.expectedBytes) {
                    fail("Upload incomplete: ${state.writtenBytes}/${state.expectedBytes} bytes")
                }
                val sha = state.digest.digest().toHex()
                if (sha != state.expectedSha256) {
                    fail("SHA-256 mismatch")
                }
                var lineCount = state.lineCount
                if (state.writtenBytes > 0 && !state.prevWasNewline) lineCount++
                if (state.writtenBytes == 0L) lineCount = 0
                val dir = state.partialFile.parentFile!!
                val target = File(dir, state.displayName)
                if (!state.partialFile.renameTo(target)) {
                    target.delete()
                    check(state.partialFile.renameTo(target)) { "Failed to finalize upload" }
                }
                val attachment = AgentAttachment(
                    id = uploadId,
                    displayName = state.displayName,
                    kind = AgentAttachmentKind.Text,
                    mediaType = state.mediaType,
                    byteCount = state.writtenBytes,
                    lineCount = lineCount,
                    sha256 = sha,
                )
                // Reservation already counted toward draftBytes — convert to staged ownership
                // without double-counting.
                stagedMeta[uploadId] = StagedMeta(state.draftKey, target, attachment.byteCount)
                writeSidecar(dir, attachment)
                attachment
            }
        }
    }

    override suspend fun cancelUpload(uploadId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            // In-progress upload: release reservation and delete partials.
            val state = uploads.remove(uploadId)
            if (state != null) {
                if (state.reservationHeld) {
                    releaseDraftBytes(state.draftKey, state.expectedBytes)
                }
                File(stagingRoot, uploadId).deleteRecursively()
                return@withLock true
            }
            // Committed-but-unsent staged attachment (remote cleanup after a failed chat
            // mutation): discard the staged body too.
            discardStagedLocked(uploadId)
        }
    }

    override suspend fun readStagedChunk(
        attachmentId: String,
        offset: Long,
        maxBytes: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!isSafeId(attachmentId) || offset < 0L || maxBytes <= 0) return@withContext null
        val file = resolveStagedFile(
            AgentAttachment(
                id = attachmentId,
                displayName = "x",
                byteCount = 0,
                sha256 = "",
            ),
        ) ?: return@withContext null
        val length = file.length()
        if (offset >= length) return@withContext ByteArray(0)
        val toRead = minOf(maxBytes.toLong(), length - offset).toInt()
        ByteArray(toRead).also { buf ->
            FileInputStream(file).use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0L) break
                    skipped += n
                }
                var read = 0
                while (read < toRead) {
                    val n = input.read(buf, read, toRead - read)
                    if (n < 0) break
                    read += n
                }
                if (read != toRead) {
                    return@withContext buf.copyOf(read)
                }
            }
        }
    }

    override suspend fun spilloverPromptIfNeeded(
        taskId: String,
        cwd: String?,
        prompt: String,
    ): String = withContext(Dispatchers.IO) {
        if (!shouldAttachLargeText(prompt)) return@withContext prompt
        mutex.withLock {
            val destRoot = taskAttachmentsDir(cwd, taskId).also { it.mkdirs() }
            val safeName = uniqueSafeName(destRoot, "prompt-spill.txt")
            val dest = File(destRoot, safeName)
            val tmp = File(destRoot, ".$safeName.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = prompt.encodeToByteArray()
            BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                digest.update(bytes)
                out.write(bytes)
            }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                check(tmp.renameTo(dest)) { "Failed to write prompt spillover" }
            }
            val sha = digest.digest().toHex()
            val lines = countTextLines(prompt)
            val relative = ".andy/$taskId/attachments/$safeName"
            buildString {
                append("The full prompt was too large to pass inline (")
                append(bytes.size)
                append(" bytes, ")
                append(lines)
                append(" lines). It was written to ")
                append(relative)
                append(" (sha256=")
                append(sha)
                append("). Inspect that file with bounded reads/searches before responding.")
            }
        }
    }

    /** Test helper: absolute staged file for [attachmentId]. */
    internal fun stagedFile(attachmentId: String): File? =
        stagedMeta[attachmentId]?.file ?: resolveStagedFile(
            AgentAttachment(
                id = attachmentId,
                displayName = "x",
                byteCount = 0,
                sha256 = "",
            ),
        )

    private fun discardStagedLocked(attachmentId: String): Boolean {
        if (!isSafeId(attachmentId)) return false
        val meta = stagedMeta.remove(attachmentId)
        meta?.let { releaseDraftBytes(it.draftKey, it.byteCount) }
        val dir = File(stagingRoot, attachmentId)
        if (!dir.isDirectory) return meta != null
        dir.deleteRecursively()
        return true
    }

    private fun releaseDraftBytes(draftKey: String?, bytes: Long) {
        if (draftKey == null || bytes <= 0L) return
        draftBytes.compute(draftKey) { _, total ->
            val next = (total ?: 0L) - bytes
            if (next <= 0L) null else next
        }
    }

    /**
     * Drops the staging directory after a successful materialize. Safe because the
     * task-owned copy (hard link or independent file) already verified.
     */
    private fun releaseStagedAfterMaterialize(attachmentId: String) {
        val meta = stagedMeta.remove(attachmentId)
        meta?.let { releaseDraftBytes(it.draftKey, it.byteCount) }
        File(stagingRoot, attachmentId).deleteRecursively()
    }

    private fun resolveStagedFile(attachment: AgentAttachment): File? {
        stagedMeta[attachment.id]?.file?.takeIf { it.isFile }?.let { return it }
        val dir = File(stagingRoot, attachment.id)
        if (!dir.isDirectory) return null
        val named = File(dir, sanitizeFileName(attachment.displayName))
        if (named.isFile) return named
        return dir.listFiles()?.firstOrNull { it.isFile && !it.name.startsWith('.') && !it.name.endsWith(".json") }
    }

    /**
     * Returns a reusable materialized file only when [AgentAttachment.relativePath] resolves
     * canonically inside `<cwd>/.andy/<taskId>/attachments/`. Rejects traversal, absolute, and
     * sibling-task paths.
     */
    private fun resolveReusableMaterializedFile(
        cwd: String?,
        taskId: String,
        attachment: AgentAttachment,
    ): File? {
        val relative = attachment.relativePath?.takeIf { it.isNotBlank() } ?: return null
        if (cwd.isNullOrBlank()) return null
        if (!isSafeTaskRelativeAttachmentPath(relative, taskId)) return null
        val attachmentsRoot = taskAttachmentsDir(cwd, taskId).canonicalFile
        val candidate = File(cwd, relative).canonicalFile
        val rootPath = attachmentsRoot.path
        val candidatePath = candidate.path
        val inside = candidatePath == rootPath ||
            candidatePath.startsWith(rootPath + File.separator)
        if (!inside) return null
        if (candidate.parentFile?.canonicalFile != attachmentsRoot) return null
        if (!candidate.isFile) return null
        return candidate
    }

    private fun verifyIntegrity(file: File, attachment: AgentAttachment) {
        if (file.length() != attachment.byteCount) {
            error("Attachment size mismatch for ${attachment.id}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().toHex()
        if (attachment.sha256.isNotBlank() && sha != attachment.sha256.lowercase()) {
            error("Attachment hash mismatch for ${attachment.id}")
        }
    }

    private fun hardLinkOrCopy(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        dest.delete()
        val linked = runCatching {
            Files.createLink(dest.toPath(), source.toPath())
            true
        }.getOrDefault(false)
        if (!linked) {
            source.copyTo(dest, overwrite = true)
        }
    }

    private fun writeSidecar(dir: File, attachment: AgentAttachment) {
        File(dir, "meta.json").writeText(
            buildString {
                append('{')
                append("\"id\":\"").append(attachment.id).append("\",")
                append("\"displayName\":\"").append(attachment.displayName).append("\",")
                append("\"byteCount\":").append(attachment.byteCount).append(',')
                append("\"lineCount\":").append(attachment.lineCount ?: 0).append(',')
                append("\"sha256\":\"").append(attachment.sha256).append('"')
                append('}')
            },
        )
    }

    private fun ensureFreeSpace(needed: Long) {
        val usable = stagingRoot.usableSpace.takeIf { stagingRoot.exists() || stagingRoot.mkdirs() }
            ?: File(System.getProperty("user.home")).usableSpace
        // Require some headroom beyond the file itself.
        if (usable in 1 until needed + (64L * 1024 * 1024)) {
            error("Insufficient disk space to stage attachment")
        }
    }

    private data class StagedMeta(
        val draftKey: String?,
        val file: File,
        val byteCount: Long,
    )

    private class UploadState(
        val uploadId: String,
        val displayName: String,
        val expectedBytes: Long,
        val expectedSha256: String,
        val mediaType: String,
        val draftKey: String?,
        val partialFile: File,
        var nextSequence: Int,
        var writtenBytes: Long,
        val digest: MessageDigest,
        var lineCount: Long,
        var prevWasNewline: Boolean,
        val reservationHeld: Boolean,
    )

    companion object {
        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SHA256_HEX = Regex("^[a-fA-F0-9]{64}$")

        fun taskAttachmentsDir(cwd: String?, taskId: String): File =
            File(AgentWorkflowArtifacts.dirFor(cwd?.let(::File), taskId), "attachments")

        fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

        /**
         * True when [relative] is exactly `.andy/<taskId>/attachments/<safe-file>` with no
         * traversal or absolute segments. Used before trusting a persisted relativePath.
         */
        fun isSafeTaskRelativeAttachmentPath(relative: String, taskId: String): Boolean {
            if (relative.isBlank() || relative.startsWith("/") || relative.startsWith("\\")) return false
            if (relative.contains('\\') || relative.contains('\u0000')) return false
            val parts = relative.split('/')
            if (parts.size != 4) return false
            if (parts[0] != ".andy") return false
            if (parts[1] != taskId || !isSafeId(taskId)) return false
            if (parts[2] != "attachments") return false
            val name = parts[3]
            return name.matches(Regex("^[A-Za-z0-9._-]{1,128}$")) && name != "." && name != ".."
        }

        fun sanitizeFileName(name: String): String {
            val base = name.trim().substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "pasted.txt" }
            val cleaned = buildString(base.length.coerceAtMost(120)) {
                for (ch in base.take(120)) {
                    append(
                        when {
                            ch.isLetterOrDigit() || ch in "._-" -> ch
                            ch == ' ' -> '-'
                            else -> '_'
                        },
                    )
                }
            }.trim('.').ifBlank { "pasted.txt" }
            return if (cleaned.contains('.')) cleaned else "$cleaned.txt"
        }

        fun uniqueSafeName(dir: File, desired: String): String {
            val safe = sanitizeFileName(desired)
            if (!File(dir, safe).exists()) return safe
            val stem = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', missingDelimiterValue = "").let {
                if (it.isEmpty() || it == safe) ".txt" else ".$it"
            }
            var i = 2
            while (true) {
                val candidate = "$stem-$i$ext"
                if (!File(dir, candidate).exists()) return candidate
                i++
            }
        }

        fun newAttachmentId(): String = "att-" + UUID.randomUUID().toString().replace("-", "")

        private fun ByteArray.toHex(): String = joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
    }
}
