package app.andy.desktop.service.agents

import app.andy.model.ChatAttachmentLimits
import app.andy.model.shouldAttachLargeText
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopChatAttachmentServiceTest {
    private lateinit var root: File
    private lateinit var service: DesktopChatAttachmentService

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("andy-attach-", ".dir").also {
            it.delete()
            it.mkdirs()
        }
        service = DesktopChatAttachmentService(stagingRoot = root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun stagesTextAtomicallyWithHashAndLineCount() = runBlocking {
        val text = (1..2_500).joinToString("\n") { "line-$it" }
        assertTrue(shouldAttachLargeText(text))
        val attachment = service.stageText(text, displayName = "paste.txt", draftKey = "draft-1").getOrThrow()
        val file = service.stagedFile(attachment.id)!!
        assertTrue(file.isFile)
        assertEquals(text, file.readText())
        assertEquals(sha256(text), attachment.sha256)
        assertEquals(2_500L, attachment.lineCount)
        assertEquals(text.encodeToByteArray().size.toLong(), attachment.byteCount)
        assertEquals("paste.txt", attachment.displayName)
    }

    @Test
    fun rejectsTraversalDisplayNames() = runBlocking {
        val attachment = service.stageText("hello", displayName = "../../evil.txt").getOrThrow()
        assertFalse(attachment.displayName.contains(".."))
        assertTrue(File(root, attachment.id).listFiles()!!.none { it.path.contains("..") })
    }

    @Test
    fun materializesUnderTaskCwdAttachments() = runBlocking {
        val text = "x".repeat(ChatAttachmentLimits.InlineMaxBytes.toInt() + 10)
        val staged = service.stageText(text, displayName = "big.txt").getOrThrow()
        val cwd = File(root, "project").also { it.mkdirs() }
        val materialized = service.materializeForTask("task-abc", cwd.absolutePath, listOf(staged))
        val relative = materialized.single().relativePath!!
        assertEquals(".andy/task-abc/attachments/big.txt", relative)
        val onDisk = File(cwd, relative)
        assertTrue(onDisk.isFile)
        assertEquals(text, onDisk.readText())
        assertEquals(sha256(text), materialized.single().sha256)
    }

    @Test
    fun uploadChunkProtocolVerifiesIntegrity() = runBlocking {
        val payload = "chunked-" + "z".repeat(100_000)
        val bytes = payload.encodeToByteArray()
        val begin = service.beginUpload(
            displayName = "upload.txt",
            byteCount = bytes.size.toLong(),
            sha256 = sha256(payload),
        ).getOrThrow()
        val mid = bytes.size / 2
        service.appendUploadChunk(begin.uploadId, 0, java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(0, mid)))
            .getOrThrow()
        service.appendUploadChunk(begin.uploadId, 1, java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(mid, bytes.size)))
            .getOrThrow()
        val committed = service.commitUpload(begin.uploadId).getOrThrow()
        assertEquals(bytes.size.toLong(), committed.byteCount)
        assertEquals(sha256(payload), committed.sha256)
    }

    @Test
    fun uploadRejectsHashMismatch() = runBlocking {
        val payload = "bad-hash"
        val begin = service.beginUpload(
            displayName = "bad.txt",
            byteCount = payload.encodeToByteArray().size.toLong(),
            sha256 = "0".repeat(64),
        ).getOrThrow()
        service.appendUploadChunk(
            begin.uploadId,
            0,
            java.util.Base64.getEncoder().encodeToString(payload.encodeToByteArray()),
        ).getOrThrow()
        assertTrue(service.commitUpload(begin.uploadId).isFailure)
    }

    @Test
    fun spilloverReplacesOversizedPrompt() = runBlocking {
        val huge = "p".repeat(ChatAttachmentLimits.InlineMaxBytes.toInt() + 50)
        val cwd = File(root, "spill-project").also { it.mkdirs() }
        val spilled = service.spilloverPromptIfNeeded("task-spill", cwd.absolutePath, huge)
        assertTrue(spilled.length < 2_000)
        assertTrue(spilled.contains(".andy/task-spill/attachments/"))
        assertFalse(spilled.contains(huge.take(100)))
        val files = File(cwd, ".andy/task-spill/attachments").listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        assertEquals(huge, files.single().readText())
    }

    @Test
    fun discardRemovesOnlyOwnedStaging() = runBlocking {
        val a = service.stageText("a".repeat(70_000), draftKey = "d1").getOrThrow()
        val b = service.stageText("b".repeat(70_000), draftKey = "d2").getOrThrow()
        assertTrue(service.discardStaged(a.id))
        assertFalse(File(root, a.id).exists())
        assertTrue(File(root, b.id).exists())
        service.discardDraft("d2")
        assertFalse(File(root, b.id).exists())
    }

    @Test
    fun largeGeneratedFixtureStagesMaterializesAndBoundsPrompt() = runBlocking {
        val fixture = File(root, "generated-9mib.txt")
        val line = "0123456789abcdef\n".toByteArray()
        val targetBytes = 9L * 1024 * 1024
        java.io.FileOutputStream(fixture).use { out ->
            var written = 0L
            while (written < targetBytes) {
                out.write(line)
                written += line.size
            }
        }
        assertTrue(fixture.length() in (8L * 1024 * 1024)..(10L * 1024 * 1024))

        val digest = MessageDigest.getInstance("SHA-256")
        var lineCount = 0L
        var prevWasNewline = true
        fixture.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                for (i in 0 until n) {
                    when (buf[i]) {
                        '\n'.code.toByte() -> {
                            lineCount++
                            prevWasNewline = true
                        }
                        else -> prevWasNewline = false
                    }
                }
            }
        }
        if (fixture.length() > 0 && !prevWasNewline) lineCount++
        val sha = digest.digest().joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }

        val begin = service.beginUpload(
            displayName = "generated-9mib.txt",
            byteCount = fixture.length(),
            sha256 = sha,
        ).getOrThrow()
        var offset = 0L
        var sequence = 0
        fixture.inputStream().use { input ->
            val buf = ByteArray(ChatAttachmentLimits.UploadChunkBytes)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                service.appendUploadChunk(
                    begin.uploadId,
                    sequence,
                    java.util.Base64.getEncoder().encodeToString(chunk),
                ).getOrThrow()
                offset += n
                sequence++
            }
        }
        assertEquals(fixture.length(), offset)
        val staged = service.commitUpload(begin.uploadId).getOrThrow()
        assertEquals(fixture.length(), staged.byteCount)
        assertEquals(sha, staged.sha256)
        assertEquals(lineCount, staged.lineCount)

        val cwd = File(root, "big-project").also { it.mkdirs() }
        val materialized = service.materializeForTask("task-big", cwd.absolutePath, listOf(staged))
        val relative = materialized.single().relativePath!!
        val onDisk = File(cwd, relative)
        assertTrue(onDisk.isFile)
        assertEquals(fixture.length(), onDisk.length())
        assertEquals(sha, materialized.single().sha256)
        assertFalse(File(root, staged.id).exists(), "staging must be released after materialize")

        val prompt = app.andy.model.promptWithAttachmentHints("summarize this file", materialized)
        assertTrue(prompt.length < 4_000, "prompt must stay bounded, was ${prompt.length}")
        assertTrue(prompt.contains(relative))
        assertTrue(prompt.contains(sha))
        assertFalse(prompt.contains("0123456789abcdef".repeat(20)))

        // Restart / retry without in-memory stagedMeta: rematerialize from task-relative copy.
        val restarted = DesktopChatAttachmentService(stagingRoot = File(root, "empty-staging").also { it.mkdirs() })
        val again = restarted.materializeForTask("task-big", cwd.absolutePath, materialized)
        assertEquals(relative, again.single().relativePath)
        assertTrue(File(cwd, relative).isFile)
    }

    @Test
    fun expireAbandonedStagingRemovesOnlyOldEntries() = runBlocking {
        val old = service.stageText("o".repeat(70_000), displayName = "old.txt").getOrThrow()
        val fresh = service.stageText("f".repeat(70_000), displayName = "fresh.txt").getOrThrow()
        val oldDir = File(root, old.id)
        assertTrue(oldDir.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
        service.expireAbandonedStaging(maxAgeMillis = 24L * 60 * 60 * 1000)
        assertFalse(oldDir.exists())
        assertTrue(File(root, fresh.id).exists())
    }

    @Test
    fun multiAttachmentMaterializeIsAtomicOnFailure() = runBlocking {
        val a = service.stageText("a".repeat(70_000), displayName = "a.txt").getOrThrow()
        val b = service.stageText("b".repeat(70_000), displayName = "b.txt").getOrThrow()
        // Corrupt the second staging source so materialize fails after the first dest is created.
        File(root, b.id).deleteRecursively()
        val cwd = File(root, "atomic-project").also { it.mkdirs() }
        assertFails {
            service.materializeForTask("task-atomic", cwd.absolutePath, listOf(a, b))
        }
        assertTrue(File(root, a.id).exists(), "first staging source must be retained")
        val destDir = File(cwd, ".andy/task-atomic/attachments")
        val leftovers = destDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(leftovers.isEmpty(), "partial dest files must be cleaned: $leftovers")
    }

    @Test
    fun rejectsTraversalAndSiblingRelativePathsOnReuse() = runBlocking {
        val staged = service.stageText("safe-body-" + "z".repeat(70_000), displayName = "safe.txt").getOrThrow()
        val cwd = File(root, "path-project").also { it.mkdirs() }
        val good = service.materializeForTask("task-path", cwd.absolutePath, listOf(staged)).single()
        val onDisk = File(cwd, good.relativePath!!)
        assertTrue(onDisk.isFile)

        // Sibling task file that happens to match size/hash must not be selectable.
        val siblingDir = File(cwd, ".andy/other-task/attachments").also { it.mkdirs() }
        val sibling = File(siblingDir, "evil.txt")
        onDisk.copyTo(sibling, overwrite = true)

        val traversal = good.copy(relativePath = "../../.andy/other-task/attachments/evil.txt")
        val absolute = good.copy(relativePath = sibling.absolutePath)
        val siblingRelative = good.copy(relativePath = ".andy/other-task/attachments/evil.txt")

        // Without staging (already released), unsafe relativePath must not reuse sibling files.
        assertFails { service.materializeForTask("task-path", cwd.absolutePath, listOf(traversal)) }
        assertFails { service.materializeForTask("task-path", cwd.absolutePath, listOf(absolute)) }
        assertFails { service.materializeForTask("task-path", cwd.absolutePath, listOf(siblingRelative)) }

        // Canonical same-task path still works after restart-style rematerialize.
        val again = service.materializeForTask("task-path", cwd.absolutePath, listOf(good))
        assertEquals(good.relativePath, again.single().relativePath)
    }

    @Test
    fun beginUploadReservesDraftBudgetAndReleasesOnCancel() = runBlocking {
        val tight = DesktopChatAttachmentService(
            stagingRoot = File(root, "budget").also { it.mkdirs() },
            maxStagedBytesPerDraft = 1_000L,
        )
        val placeholderSha = "ab".repeat(32)
        val first = tight.beginUpload("one.txt", byteCount = 600, sha256 = placeholderSha, draftKey = "draft")
            .getOrThrow()
        assertEquals(600L, tight.draftByteTotal("draft"))
        assertTrue(
            tight.beginUpload("two.txt", byteCount = 600, sha256 = placeholderSha, draftKey = "draft").isFailure,
            "second begin must fail while first reservation is held",
        )
        assertEquals(600L, tight.draftByteTotal("draft"))
        assertTrue(tight.cancelUpload(first.uploadId))
        assertEquals(0L, tight.draftByteTotal("draft"))

        val body = ByteArray(400) { 'x'.code.toByte() }
        val realSha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
        val begin = tight.beginUpload("ok.txt", byteCount = 400, sha256 = realSha, draftKey = "draft").getOrThrow()
        assertEquals(400L, tight.draftByteTotal("draft"))
        tight.appendUploadChunk(begin.uploadId, 0, java.util.Base64.getEncoder().encodeToString(body)).getOrThrow()
        val committed = tight.commitUpload(begin.uploadId).getOrThrow()
        assertEquals(400L, committed.byteCount)
        assertEquals(400L, tight.draftByteTotal("draft"), "commit must not double-count reservation")
        assertTrue(tight.beginUpload("overflow.txt", byteCount = 700, sha256 = placeholderSha, draftKey = "draft").isFailure)
        assertTrue(tight.beginUpload("fit.txt", byteCount = 600, sha256 = placeholderSha, draftKey = "draft").isSuccess)
        assertEquals(1_000L, tight.draftByteTotal("draft"))
    }

    @Test
    fun hashFailureReleasesReservation() = runBlocking {
        val tight = DesktopChatAttachmentService(stagingRoot = File(root, "hash-budget").also { it.mkdirs() }, maxStagedBytesPerDraft = 500L)
        val body = ByteArray(200) { 'y'.code.toByte() }
        val begin = tight.beginUpload(
            displayName = "bad.txt",
            byteCount = 200,
            sha256 = "0".repeat(64),
            draftKey = "d",
        ).getOrThrow()
        assertEquals(200L, tight.draftByteTotal("d"))
        tight.appendUploadChunk(begin.uploadId, 0, java.util.Base64.getEncoder().encodeToString(body)).getOrThrow()
        assertTrue(tight.commitUpload(begin.uploadId).isFailure)
        assertEquals(0L, tight.draftByteTotal("d"))
    }

    @Test
    fun expireAbandonedStagingDecrementsDraftTotals() = runBlocking {
        val staged = service.stageText("e".repeat(70_000), draftKey = "expire-draft").getOrThrow()
        assertTrue(service.draftByteTotal("expire-draft") > 0L)
        val dir = File(root, staged.id)
        assertTrue(dir.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
        service.expireAbandonedStaging(maxAgeMillis = 24L * 60 * 60 * 1000)
        assertFalse(dir.exists())
        assertEquals(0L, service.draftByteTotal("expire-draft"))
    }

    @Test
    fun cancelUploadDiscardsCommittedStagedAttachment() = runBlocking {
        val staged = service.stageText("c".repeat(70_000), displayName = "committed.txt").getOrThrow()
        assertTrue(File(root, staged.id).exists())
        assertTrue(service.cancelUpload(staged.id), "cancel must discard committed staging")
        assertFalse(File(root, staged.id).exists())
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        return digest.joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    }
}
