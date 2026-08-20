package app.andy.desktop.service.agents

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Disposable artifact directories for temporary chats.
 *
 * A temp chat's scrollback and transcript are file-backed like any other chat — the terminal
 * lane has no in-memory mode — so ephemerality is achieved by putting those files somewhere
 * that is wiped rather than by not writing them. The root is per-process so two Andy instances
 * never delete each other's live chats, and a shutdown hook covers the crash case.
 *
 * Workflow artifacts still live at `<cwd>/.andy/<taskId>/` (their path is handed to the agent
 * in prompt text), so each temp chat also records that absolute path under its disposable
 * directory. [sweepOrphans] removes those recorded folders when cleaning a crashed session.
 */
internal class TemporaryChatArtifacts(
    parentDir: File = File(System.getProperty("java.io.tmpdir")),
) {
    private val dirs = ConcurrentHashMap<String, File>()

    /** Created lazily: a session that never opens a temp chat leaves nothing behind at all. */
    private val root: File by lazy {
        Files.createTempDirectory(parentDir.toPath(), ROOT_PREFIX).toFile().also { dir ->
            runCatching {
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { dir.deleteRecursively() } })
            }
        }
    }

    fun dirFor(taskId: String): File = dirs.computeIfAbsent(taskId) {
        File(root, taskId).apply { mkdirs() }
    }

    /** Remember `<cwd>/.andy/<taskId>` so a later orphan sweep can delete it after a crash. */
    fun rememberWorkflowDir(taskId: String, workflowDir: File) {
        val marker = File(dirFor(taskId), WORKFLOW_DIR_MARKER)
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(workflowDir.absolutePath)
        }
    }

    /** Stops routing [taskId] here and returns its directory, for promotion to a real chat. */
    fun release(taskId: String): File? = dirs.remove(taskId)

    fun discard(taskId: String) {
        dirs.remove(taskId)?.let { dir ->
            deleteRememberedWorkflowDir(dir)
            runCatching { dir.deleteRecursively() }
        }
    }

    fun discardAll() {
        dirs.keys.toList().forEach(::discard)
        if (dirs.isEmpty()) runCatching { root.deleteRecursively() }
    }

    companion object {
        private const val ROOT_PREFIX = "andy-temp-chats-"
        internal const val WORKFLOW_DIR_MARKER = "workflow-dir.path"

        /**
         * Age threshold for [sweepOrphans]. Well beyond any plausible clock skew, and long
         * enough that a concurrently starting instance's root is never a candidate.
         */
        private const val ORPHAN_AGE_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Removes roots left by a crashed session, including any remembered
         * `<cwd>/.andy/<taskId>` workflow folders. Only stale directories are touched, so a
         * second running instance keeps its live temp chats.
         */
        fun sweepOrphans(
            parentDir: File = File(System.getProperty("java.io.tmpdir")),
            nowMillis: Long = System.currentTimeMillis(),
        ): Int {
            val stale = parentDir.listFiles()?.filter { file ->
                file.isDirectory &&
                    file.name.startsWith(ROOT_PREFIX) &&
                    nowMillis - file.lastModified() > ORPHAN_AGE_MILLIS
            }.orEmpty()
            return stale.count { root ->
                root.listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { taskDir -> deleteRememberedWorkflowDir(taskDir) }
                runCatching { root.deleteRecursively() }.getOrDefault(false)
            }
        }

        private fun deleteRememberedWorkflowDir(taskDir: File) {
            val marker = File(taskDir, WORKFLOW_DIR_MARKER)
            if (!marker.isFile) return
            val path = marker.readText().trim()
            if (path.isBlank()) return
            runCatching { File(path).takeIf { it.isDirectory }?.deleteRecursively() }
        }
    }
}
