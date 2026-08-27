package app.andy.desktop.service.remote

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Shared OpenSSH argv / ProcessBuilder helpers for Andy remote sessions.
 * Prefer multiplexing via [controlPath] so password askpass runs once per connect.
 *
 * Socket paths stay under `/tmp` with short hashed names — macOS AF_UNIX paths are
 * capped around 104 bytes, and `java.io.tmpdir` (`/var/folders/...`) is already long.
 */
object SshProcess {
    /** Stable short token for [target] (not reversible; collision-resistant enough for warm sessions). */
    fun targetKey(target: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.trim().toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { b -> "%02x".format(b) }
    }

    fun controlPathForTarget(
        target: String,
        pid: Long = ProcessHandle.current().pid(),
    ): File = socketFile(pid, target, "c")

    fun localAndydSocket(target: String, pid: Long = ProcessHandle.current().pid()): File =
        socketFile(pid, target, "a")

    fun localTmuxSocket(target: String, pid: Long = ProcessHandle.current().pid()): File =
        socketFile(pid, target, "t")

    private fun socketFile(pid: Long, target: String, kind: String): File {
        val dir = File("/tmp", "andy-r$pid").also { it.mkdirs() }
        // e.g. /tmp/andy-r12345/a1b2c3d4.a  (~28 chars) — well under the unix socket limit
        return File(dir, "${targetKey(target)}.$kind")
    }

    /** @deprecated Use [controlPathForTarget]. */
    fun controlPathForPid(pid: Long = ProcessHandle.current().pid()): File =
        File(File("/tmp", "andy-r$pid").also { it.mkdirs() }, "mux")

    fun baseOptions(controlPath: File?): List<String> = buildList {
        add("-o")
        add("StrictHostKeyChecking=yes")
        add("-o")
        add("ForwardAgent=no")
        add("-o")
        add("ConnectTimeout=20")
        add("-o")
        add("Compression=no")
        // Prefer interactive/low-delay QoS — Live video + scrcpy control share this mux.
        add("-o")
        add("IPQoS=lowdelay")
        if (controlPath != null) {
            add("-o")
            add("ControlPath=${controlPath.absolutePath}")
            add("-o")
            add("ControlMaster=auto")
            add("-o")
            add("ControlPersist=yes")
        }
    }

    fun processBuilder(command: List<String>): ProcessBuilder =
        ProcessBuilder(command).also { SshAskpass.applyTo(it) }

    fun exitMaster(controlPath: File) {
        if (!controlPath.exists()) return
        val cmd = listOf(
            "ssh",
            "-O", "exit",
            "-o", "ControlPath=${controlPath.absolutePath}",
            "unused",
        )
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS)
        }
        controlPath.delete()
    }
}
