package app.andy.desktop.service.agents

import app.andy.desktop.service.DesktopActionConfigStore
import app.andy.model.AgentTask
import app.andy.service.AgentRetentionService
import app.andy.service.RetentionSweepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private val periodicIntervalMs = 4.hours.inWholeMilliseconds

internal enum class RetentionAction {
    Skip,
    CompressArchive,
    PermanentDelete,
}

/** Pure task eligibility used by the sweep and by retention table tests. */
@Suppress("UNUSED_PARAMETER")
internal fun retentionAction(
    task: AgentTask,
    nowMillis: Long,
    cutoffArchiveMillis: Long,
    cutoffDeleteMillis: Long,
): RetentionAction {
    if (task.isActive || task.unread) return RetentionAction.Skip
    if (task.archived && !task.transcriptCompressed) return RetentionAction.Skip

    val ageBasis = task.finishedAtMillis?.takeIf { it > 0 } ?: task.createdAtMillis
    return when {
        task.archived && task.transcriptCompressed && ageBasis < cutoffDeleteMillis ->
            RetentionAction.PermanentDelete
        !task.archived && !task.transcriptCompressed && ageBasis < cutoffArchiveMillis ->
            RetentionAction.CompressArchive
        else -> RetentionAction.Skip
    }
}

class DesktopAgentRetentionService(
    private val runService: DesktopAgentRunService,
    private val store: DesktopAgentTaskStore,
    private val actionConfigStore: DesktopActionConfigStore,
    private val workspace: StateFlow<app.andy.model.WorkspaceState>,
    private val scope: CoroutineScope,
) : AgentRetentionService {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runGuardedSweep()
            while (currentCoroutineContext().isActive) {
                delay(periodicIntervalMs)
                runGuardedSweep()
            }
        }
    }

    private suspend fun runGuardedSweep() {
        if (!workspace.value.retentionCleanupEnabled) return
        try {
            runSweepNow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            System.err.println("andy: automatic agent retention sweep failed: ${error.message}")
        }
    }

    override suspend fun runSweepNow(): RetentionSweepResult {
        runService.awaitReady()
        val settings = workspace.value
        val now = System.currentTimeMillis()
        val archiveDays = settings.retentionCompressArchiveAfterDays.coerceIn(1, 3650)
        // Keep corrupted properties files safe: permanent deletion may never be earlier than
        // the compression threshold, even though the normal Settings UI enforces this.
        val deleteDays = maxOf(archiveDays + 1, settings.retentionPermanentDeleteAfterDays.coerceIn(1, 3650))
        val cutoffArchive = now - archiveDays.days.inWholeMilliseconds
        val cutoffDelete = now - deleteDays.days.inWholeMilliseconds

        var compressed = 0
        var deleted = 0
        var bytesReclaimed = 0L
        val tasks = runService.tasks.value

        for (task in tasks) {
            when (retentionAction(task, now, cutoffArchive, cutoffDelete)) {
                RetentionAction.PermanentDelete -> {
                    bytesReclaimed += store.taskDir(task.id).walkFileSize()
                    runService.delete(task.id, removeWorktree = false)
                    deleted++
                }
                RetentionAction.CompressArchive -> {
                    val reclaimed = compressAndArchive(task.id)
                    if (reclaimed >= 0L) {
                        compressed++
                        bytesReclaimed += reclaimed
                    }
                }
                RetentionAction.Skip -> Unit
            }
        }

        val (foldersDeleted, folderBytes) = sweepProjectLocalFolders(cutoffArchive, tasks)
        return RetentionSweepResult(
            chatsCompressedArchived = compressed,
            chatsPermanentlyDeleted = deleted,
            projectLocalFoldersDeleted = foldersDeleted,
            bytesReclaimed = bytesReclaimed + folderBytes,
        )
    }

    internal suspend fun compressAndArchive(taskId: String): Long = withContext(Dispatchers.IO) {
        val dir = store.taskDir(taskId)
        val entries = dir.listFiles().orEmpty()
        if (!dir.exists() || entries.isEmpty()) {
            runService.markArchivedByRetention(taskId, compressed = false)
            return@withContext 0L
        }

        val existingArchive = store.archiveFile(taskId)
        if (existingArchive.isFile && entries.all { it == existingArchive }) {
            runService.markArchivedByRetention(taskId, compressed = true)
            return@withContext 0L
        }

        val sizeBefore = dir.walkFileSize()
        val tmpZip = File(dir, "archive.zip.tmp")
        tmpZip.delete()
        ZipOutputStream(tmpZip.outputStream().buffered()).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && it.name != tmpZip.name }
                .forEach { file ->
                    val relativePath = dir.toPath().relativize(file.toPath()).toString()
                    zip.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }

        dir.listFiles()
            ?.filterNot { it.name == tmpZip.name }
            ?.forEach { it.deleteRecursively() }
        val finalZip = store.archiveFile(taskId)
        if (finalZip.exists()) finalZip.delete()
        check(tmpZip.renameTo(finalZip)) { "Could not finalize retention archive for $taskId" }
        val reclaimed = (sizeBefore - finalZip.length()).coerceAtLeast(0L)
        runService.markArchivedByRetention(taskId, compressed = true)
        reclaimed
    }

    private suspend fun sweepProjectLocalFolders(
        cutoffArchiveMillis: Long,
        knownTasks: List<AgentTask>,
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val taskById = knownTasks.associateBy { it.id }
        val roots = buildSet {
            knownTasks.mapNotNullTo(this) { it.originDir }
            actionConfigStore.load().projects.mapTo(this) { it.contextDir }
        }
        var deleted = 0
        var bytes = 0L
        for (rootPath in roots) {
            val andyDir = File(rootPath, ".andy")
            val subDirs = andyDir.listFiles { file -> file.isDirectory } ?: continue
            for (subDir in subDirs) {
                val matchedTask = taskById[subDir.name]
                val eligible = when {
                    matchedTask != null -> {
                        if (
                            matchedTask.isActive ||
                                matchedTask.unread ||
                                (matchedTask.archived && !matchedTask.transcriptCompressed)
                        ) {
                            false
                        } else {
                            val age = matchedTask.finishedAtMillis?.takeIf { it > 0 } ?: matchedTask.createdAtMillis
                            age < cutoffArchiveMillis
                        }
                    }
                    else -> subDir.lastModified() < cutoffArchiveMillis
                }
                if (eligible) {
                    bytes += subDir.walkFileSize()
                    subDir.deleteRecursively()
                    deleted++
                }
            }
        }
        deleted to bytes
    }
}

internal fun File.walkFileSize(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
