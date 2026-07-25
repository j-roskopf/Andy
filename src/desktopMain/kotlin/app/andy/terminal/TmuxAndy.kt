package app.andy.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around a dedicated Andy tmux server (`tmux -L andy`).
 *
 * Agent tasks run as detached sessions named `andy-task-<taskId>`. Live attach
 * (GUI / CLI) is `tmux -L andy attach -t andy-task-<taskId>` — MCP never streams
 * PTY bytes.
 */
object TmuxAndy {
    const val SERVER = "andy"

    fun sessionName(taskId: String): String = "andy-task-$taskId"

    fun tmuxBinary(): String {
        cachedBinary.get()?.let { return it }
        val resolved = resolveTmuxBinary()
            ?: error(
                "tmux is required for Andy agent sessions. Install it (e.g. `brew install tmux`) " +
                    "and ensure it is on PATH, or set ANDY_TMUX to the binary path.",
            )
        cachedBinary.set(resolved)
        return resolved
    }

    fun isAvailable(): Boolean = runCatching { tmuxBinary() }.isSuccess ||
        resolveTmuxBinary() != null

    fun ensureAvailable() {
        tmuxBinary()
    }

    /**
     * Ensure the Andy tmux server is running.
     *
     * tmux 3.2+ defaults `exit-empty` on, so a bare `start-server` exits
     * immediately with no sessions and can leave a dead socket that makes
     * every later command print `no server running on …/andy`.
     */
    fun startServer() {
        val binary = tmuxBinary()
        fun boot() = run(
            listOf(
                binary, "-L", SERVER,
                "start-server", ";",
                "set-option", "-g", "exit-empty", "off", ";",
                // Interactive agent TUIs need a real terminal type (Cursor hosts often use TERM=dumb).
                "set-option", "-g", "default-terminal", "xterm-256color",
            ),
            checkExit = false,
        )
        boot()
        if (serverResponds()) return
        clearStaleSockets()
        boot()
    }

    /** True when the Andy tmux server accepts commands. */
    fun serverResponds(): Boolean {
        val result = run(
            listOf(tmuxBinary(), "-L", SERVER, "list-sessions"),
            checkExit = false,
        )
        // exit 0 = sessions listed (maybe empty). exit 1 with empty stderr can also
        // mean "no sessions" on some versions; "no server running" is the failure.
        if (result.exitCode == 0) return true
        val err = result.stderr.lowercase()
        return !err.contains("no server running") && result.stdout.isNotBlank()
    }

    private fun clearStaleSockets() {
        val bases = listOfNotNull(
            System.getenv("TMUX_TMPDIR"),
            System.getenv("TMPDIR"),
            "/tmp",
            "/private/tmp",
        ).map(::File).distinctBy { it.absolutePath }
        for (base in bases) {
            val dirs = base.listFiles { f -> f.isDirectory && f.name.startsWith("tmux-") } ?: continue
            for (dir in dirs) {
                File(dir, SERVER).takeIf { it.exists() }?.delete()
            }
        }
    }

    fun hasSession(taskId: String): Boolean {
        val result = run(
            listOf(tmuxBinary(), "-L", SERVER, "has-session", "-t", sessionName(taskId)),
            checkExit = false,
        )
        return result.exitCode == 0
    }

    fun listSessions(): List<String> {
        val result = run(
            listOf(tmuxBinary(), "-L", SERVER, "list-sessions", "-F", "#{session_name}"),
            checkExit = false,
        )
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Creates a detached session that runs [argv] under [cwd] with [env] overlays.
     * Kills any pre-existing session with the same name first.
     */
    fun newSession(
        taskId: String,
        cwd: String?,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
    ) {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        ensureAvailable()
        startServer()
        if (hasSession(taskId)) killSession(taskId)

        val name = sessionName(taskId)
        val launch = buildLaunchCommand(argv, env)
        val cmd = mutableListOf(tmuxBinary(), "-L", SERVER, "new-session", "-d", "-s", name)
        if (!cwd.isNullOrBlank()) {
            cmd += listOf("-c", cwd)
        }
        cmd += listOf("--", "/bin/sh", "-c", launch)
        val result = run(cmd)
        check(result.exitCode == 0) {
            "failed to create tmux session $name: ${result.stderr.ifBlank { result.stdout }}"
        }
    }

    fun killSession(taskId: String) {
        run(
            listOf(tmuxBinary(), "-L", SERVER, "kill-session", "-t", sessionName(taskId)),
            checkExit = false,
        )
    }

    /** Literal keystrokes into the session's active pane (no automatic Enter). */
    fun sendKeys(taskId: String, text: String) {
        if (text.isEmpty()) return
        run(
            listOf(
                tmuxBinary(), "-L", SERVER, "send-keys", "-t", sessionName(taskId),
                "-l", "--", text,
            ),
        )
    }

    fun sendEnter(taskId: String) {
        run(
            listOf(tmuxBinary(), "-L", SERVER, "send-keys", "-t", sessionName(taskId), "Enter"),
        )
    }

    /**
     * Captures pane text. [historyLines] of `-1` means full history (`-S -`).
     */
    fun capturePane(taskId: String, historyLines: Int = 200): String {
        val cmd = mutableListOf(tmuxBinary(), "-L", SERVER, "capture-pane", "-p", "-t", sessionName(taskId))
        if (historyLines < 0) {
            cmd += listOf("-S", "-")
        } else if (historyLines > 0) {
            cmd += listOf("-S", "-$historyLines")
        }
        val result = run(cmd, checkExit = false)
        if (result.exitCode != 0) return ""
        return result.stdout
    }

    fun attachArgv(taskId: String): List<String> =
        listOf(tmuxBinary(), "-L", SERVER, "attach-session", "-t", sessionName(taskId))

    /**
     * Blocks until the tmux session disappears (agent process exited) or [timeoutMs]
     * elapses. Returns 0 when the session ends, -1 on timeout.
     */
    fun waitExit(taskId: String, timeoutMs: Long = Long.MAX_VALUE): Int {
        val deadline = if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        while (hasSession(taskId)) {
            if (System.currentTimeMillis() >= deadline) return -1
            Thread.sleep(200)
        }
        return 0
    }

    internal fun buildLaunchCommand(argv: List<String>, env: Map<String, String>): String {
        val exports = env.entries.joinToString(" ") { (k, v) ->
            "${shellQuote(k)}=${shellQuote(v)}"
        }
        val command = argv.joinToString(" ") { shellQuote(it) }
        // Leading `exec` is the shell builtin (this string runs under `/bin/sh -c`).
        // Do NOT pass `exec` as an argument to `/usr/bin/env` — macOS has no
        // `exec` binary, so the pane exits immediately with "env: exec: No such file".
        // `env -i` prevents scrubbed IDE vars (CURSOR_*, TERM=dumb, …) from leaking
        // back in from the andyd/tmux process environment.
        return if (exports.isEmpty()) {
            "exec $command"
        } else {
            "exec env -i $exports $command"
        }
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(cmd: List<String>, checkExit: Boolean = true): ProcResult {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else {
            process.destroyForcibly()
            -1
        }
        if (checkExit && code != 0) {
            error("tmux command failed (${cmd.joinToString(" ")}): exit=$code stderr=${stderr.trim()} stdout=${stdout.trim()}")
        }
        return ProcResult(code, stdout, stderr)
    }

    private val cachedBinary = AtomicReference<String?>(null)

    private fun resolveTmuxBinary(): String? {
        System.getenv("ANDY_TMUX")?.takeIf { it.isNotBlank() }?.let { path ->
            if (File(path).canExecute()) return path
        }
        val pathDirs = (System.getenv("PATH") ?: "")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        val candidates = pathDirs.map { File(it, "tmux") } + listOf(
            File("/opt/homebrew/bin/tmux"),
            File("/usr/local/bin/tmux"),
            File("/usr/bin/tmux"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
