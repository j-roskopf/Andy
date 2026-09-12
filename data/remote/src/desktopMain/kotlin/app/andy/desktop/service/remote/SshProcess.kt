package app.andy.desktop.service.remote

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Shared OpenSSH argv / ProcessBuilder helpers for Andy remote sessions.
 *
 * Connect model:
 * 1. One-shot `ssh` (no mux) to resolve remote socket paths — askpass may prompt.
 * 2. Long-lived `ssh -N -L …` with [masterOptions] (`ControlMaster=yes`) — askpass cache
 *    reuses the password; this process owns the forwards.
 *
 * Do not use `ControlMaster=auto` on a second client that also passes `-L` against an
 * existing mux — OpenSSH 10+ fails with `Broken pipe`.
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

    private fun commonOptions(connectTimeoutSeconds: Int = 20): List<String> = listOf(
        "-o", "StrictHostKeyChecking=yes",
        "-o", "ForwardAgent=no",
        "-o", "ConnectTimeout=$connectTimeoutSeconds",
        "-o", "Compression=no",
        "-o", "IPQoS=lowdelay",
        // One prompt only — Cancel must not open 3 stacked Andy SSH dialogs.
        "-o", "NumberOfPasswordPrompts=1",
    )

    /** Long-lived `ssh -N -L …` that owns the ControlMaster + forwards. */
    fun masterOptions(controlPath: File): List<String> = buildList {
        addAll(commonOptions())
        add("-o")
        add("ControlPath=${controlPath.absolutePath}")
        add("-o")
        add("ControlMaster=yes")
        add("-o")
        add("ControlPersist=no")
    }

    /**
     * Mux clients against an existing master (probes, `-O forward`).
     * ControlPath only — never `ControlMaster=auto`.
     */
    fun muxOptions(controlPath: File): List<String> = buildList {
        addAll(commonOptions())
        add("-o")
        add("ControlPath=${controlPath.absolutePath}")
    }

    /**
     * When [controlPath] is set, talk to an existing master ([muxOptions]).
     * When null, one-shot non-multiplexed ssh (initial path resolve).
     */
    fun baseOptions(controlPath: File?): List<String> =
        if (controlPath != null) muxOptions(controlPath) else commonOptions()

    /**
     * Background probes that must never interrupt the user: `BatchMode=yes` makes OpenSSH fail
     * instead of prompting, so a host needing a password reports an error rather than popping the
     * askpass dialog. Reuses an existing master when [controlPath] is live (already authenticated).
     */
    fun batchOptions(controlPath: File?, connectTimeoutSeconds: Int = 8): List<String> = buildList {
        addAll(commonOptions(connectTimeoutSeconds))
        add("-o")
        add("BatchMode=yes")
        if (controlPath != null) {
            add("-o")
            add("ControlPath=${controlPath.absolutePath}")
        }
    }

    /**
     * Background probe that may use a *saved* password: no `BatchMode`, because that would rule
     * password auth out entirely. Safe only when paired with a non-prompting askpass endpoint
     * such as [SshScanAskpass] — `NumberOfPasswordPrompts=1` then bounds the attempt.
     */
    fun credentialProbeOptions(controlPath: File?, connectTimeoutSeconds: Int = 8): List<String> = buildList {
        addAll(commonOptions(connectTimeoutSeconds))
        if (controlPath != null) {
            add("-o")
            add("ControlPath=${controlPath.absolutePath}")
        }
    }

    /**
     * @param askpass false for [batchOptions] probes — without a prompt path there is nothing for
     *   the askpass helper to do, and attaching it risks a dialog on a background scan.
     */
    fun processBuilder(command: List<String>, askpass: Boolean = true): ProcessBuilder =
        ProcessBuilder(command).also { if (askpass) SshAskpass.applyTo(it) }

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

    fun debugLog(message: String) {
        runCatching {
            File("/tmp/andy-ssh-connect.log").appendText(
                "${System.currentTimeMillis()} $message\n",
            )
        }
    }
}
