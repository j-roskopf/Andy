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

    /** Stops routing [taskId] here and returns its directory, for promotion to a real chat. */
    fun release(taskId: String): File? = dirs.remove(taskId)

    fun discard(taskId: String) {
        dirs.remove(taskId)?.let { dir -> runCatching { dir.deleteRecursively() } }
    }

    fun discardAll() {
        dirs.keys.toList().forEach(::discard)
        if (dirs.isEmpty()) runCatching { root.deleteRecursively() }
    }

    companion object {
        private const val ROOT_PREFIX = "andy-temp-chats-"

        /**
         * Age threshold for [sweepOrphans]. Well beyond any plausible clock skew, and long
         * enough that a concurrently starting instance's root is never a candidate.
         */
        private const val ORPHAN_AGE_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Removes roots left by a crashed session. Only stale directories are touched, so a
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
            return stale.count { runCatching { it.deleteRecursively() }.getOrDefault(false) }
        }
    }
}
