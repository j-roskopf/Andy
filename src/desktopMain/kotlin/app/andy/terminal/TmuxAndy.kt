package app.andy.terminal

import app.andy.desktop.service.agents.AgentScratchWorkspace
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

    /**
     * Options every Andy session needs, applied globally to the `andy` server.
     *
     * `status off` is a correctness requirement, not just a redraw saving: the embedded
     * viewer attaches a real tmux client, so a visible status bar would occupy the bottom
     * row of the screen Andy scrapes for agent state — hiding the prompt line that
     * idle/blocked detection keys on. It also stops tmux re-rendering (and running
     * `status-left`/`status-right` shell commands) on `status-interval`.
     */
    private val SERVER_OPTIONS = listOf(
        "set-option", "-g", "exit-empty", "off", ";",
        // Interactive agent TUIs need a real terminal type (Cursor hosts often use TERM=dumb).
        "set-option", "-g", "default-terminal", "xterm-256color", ";",
        "set-option", "-g", "status", "off", ";",
        // CLI attach has no chrome — prefix-free ways back to `andy tui`.
        "bind-key", "-n", "F12", "detach-client", ";",
        "bind-key", "-n", "C-]", "detach-client", ";",
        "bind-key", "-n", "M-d", "detach-client",
    )

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
            listOf(binary, "-L", SERVER, "start-server", ";") + SERVER_OPTIONS,
            checkExit = false,
        )
        boot()
        serverConfigured.set(true)
        if (serverResponds()) return
        clearStaleSockets()
        boot()
    }

    /**
     * Apply [SERVER_OPTIONS] to a server this process did not start (andyd's, or one left
     * by an older build). Idempotent and forks at most once per process.
     */
    fun ensureServerConfigured() {
        if (!serverConfigured.compareAndSet(false, true)) return
        run(listOf(tmuxBinary(), "-L", SERVER) + SERVER_OPTIONS, checkExit = false)
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
     * Liveness for [taskId] answered from a cached [listSessions] snapshot.
     *
     * The UI re-derives which chats are live on every sessions-revision bump, i.e. on every
     * navigation, and a per-chat `has-session` made that one fork per chat. One
     * `list-sessions` fork answers all of them, so this scales with time rather than with
     * chat count. Prefer [hasSession] where a stale answer would be acted on destructively.
     */
    fun sessionExists(taskId: String, maxAgeMs: Long = SESSION_CACHE_MS): Boolean {
        val now = System.currentTimeMillis()
        val cached = sessionCache.get()
        val names = if (cached != null && now - cached.first <= maxAgeMs) {
            cached.second
        } else {
            listSessions().toSet().also { sessionCache.set(now to it) }
        }
        return sessionName(taskId) in names
    }

    /** Drop the [sessionExists] snapshot after this process changes the session set. */
    private fun invalidateSessionCache() {
        sessionCache.set(null)
    }

    /**
     * Creates a detached session that runs [argv] under [cwd] with [env] overlays.
     * Kills any pre-existing session with the same name first.
     *
     * If the Andy tmux server itself was started with a deleted cwd, `new-session -c`
     * is not enough — every new shell prints getcwd / uv_cwd errors. Detect that and
     * recycle the server once from a safe directory.
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

        val sessionCwd = AgentScratchWorkspace.resolveCwd(cwd)
        val launch = buildLaunchCommand(argv, env, sessionCwd)
        createDetachedSession(taskId, sessionCwd, launch)

        // Poisoned server cwd: -c and even `cd` still leave shell-init noise that makes
        // Node CLIs die on uv_cwd. Recycle once; other Andy sessions die with the server,
        // which is preferable to every new chat being unusable.
        if (sessionLooksBrokenSoon(taskId)) {
            killServer()
            startServer()
            createDetachedSession(taskId, sessionCwd, launch)
        }
        check(hasSession(taskId)) {
            "failed to create tmux session ${sessionName(taskId)}"
        }
    }

    private fun createDetachedSession(taskId: String, sessionCwd: String, launch: String) {
        if (hasSession(taskId)) killSession(taskId)
        val name = sessionName(taskId)
        val cmd = listOf(
            tmuxBinary(), "-L", SERVER, "new-session", "-d", "-s", name,
            "-c", sessionCwd,
            "--", "/bin/sh", "-c", launch,
        )
        val result = run(cmd, workingDirectory = File(sessionCwd))
        invalidateSessionCache()
        check(result.exitCode == 0) {
            "failed to create tmux session $name: ${result.stderr.ifBlank { result.stdout }}"
        }
    }

    /** True when a newly spawned pane shows getcwd / uv_cwd failure within [timeoutMs]. */
    private fun sessionLooksBrokenSoon(taskId: String, timeoutMs: Long = 600): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionLooksBroken(taskId)) return true
            val content = capturePane(taskId, historyLines = 0, escapes = false)
            if (content.isNotBlank()) return false
            Thread.sleep(40)
        }
        return sessionLooksBroken(taskId)
    }

    fun killSession(taskId: String) {
        run(
            listOf(tmuxBinary(), "-L", SERVER, "kill-session", "-t", sessionName(taskId)),
            checkExit = false,
        )
        invalidateSessionCache()
    }

    /** Drop the Andy tmux server (all sessions). Next [startServer] boots from a safe cwd. */
    fun killServer() {
        run(listOf(tmuxBinary(), "-L", SERVER, "kill-server"), checkExit = false)
        invalidateSessionCache()
        serverConfigured.set(false)
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
     * [escapes] keeps SGR sequences (`-e`) so captures can be replayed with styling.
     */
    fun capturePane(taskId: String, historyLines: Int = 200, escapes: Boolean = false): String {
        val cmd = mutableListOf(tmuxBinary(), "-L", SERVER, "capture-pane", "-p", "-t", sessionName(taskId))
        if (escapes) cmd += "-e"
        if (historyLines < 0) {
            cmd += listOf("-S", "-")
        } else if (historyLines > 0) {
            cmd += listOf("-S", "-$historyLines")
        }
        val result = run(cmd, checkExit = false)
        if (result.exitCode != 0) return ""
        return result.stdout
    }

    /** True when a session exists but its shell failed to start (e.g. deleted cwd). */
    fun sessionLooksBroken(taskId: String): Boolean {
        if (!hasSession(taskId)) return false
        // Visible pane only — full scrollback false-positives when agents discuss getcwd errors.
        val content = capturePane(taskId, historyLines = 0, escapes = false)
        return paneContentLooksBroken(content)
    }

    internal fun paneContentLooksBroken(content: String): Boolean =
        content.contains("shell-init: error retrieving current directory") ||
            content.contains("uv_cwd") ||
            (
                content.contains("getcwd") &&
                    content.contains("cannot access parent directories")
                )

    /** tmux attach client output when the target session is already gone. */
    internal fun paneContentLooksLikeFailedAttach(content: String): Boolean {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        return lines.all { line ->
            line.equals("no sessions", ignoreCase = true) ||
                line.contains("can't find session", ignoreCase = true) ||
                line.contains("no current target", ignoreCase = true)
        }
    }

    /**
     * Blocks until [taskId]'s tmux session exists or [timeoutMs] elapses.
     * GUI attach races daemon startup without this.
     */
    fun waitForSession(taskId: String, timeoutMs: Long = 30_000, pollMs: Long = 100): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasSession(taskId)) return true
            Thread.sleep(pollMs)
        }
        return hasSession(taskId)
    }

    /** Pane OSC/window title (`#{pane_title}`), empty when unavailable. */
    fun paneTitle(taskId: String): String {
        val result = run(
            listOf(
                tmuxBinary(), "-L", SERVER,
                "display-message", "-p", "-t", sessionName(taskId), "#{pane_title}",
            ),
            checkExit = false,
        )
        if (result.exitCode != 0) return ""
        return result.stdout.trim()
    }

    /** Liveness + title + pane text from a single tmux invocation. See [probePane]. */
    data class PaneProbe(val alive: Boolean, val title: String, val content: String)

    /**
     * One-process pane probe: session liveness, pane title and pane text together.
     *
     * The scrape loops poll per session and forking dominates Andy's CPU — each
     * `ProcessBuilder.start()` from the app JVM costs far more than the tmux command
     * itself. Separate `has-session` + `display-message` + `capture-pane` calls were
     * three forks per sample; tmux runs `;`-separated commands in one client, and a
     * non-zero exit already means the session is gone, so one fork answers all three.
     */
    fun probePane(taskId: String, historyLines: Int = 200, escapes: Boolean = false): PaneProbe {
        val name = sessionName(taskId)
        val cmd = mutableListOf(
            tmuxBinary(), "-L", SERVER,
            "display-message", "-p", "-t", name, "#{pane_title}", ";",
            "capture-pane", "-p", "-t", name,
        )
        if (escapes) cmd += "-e"
        if (historyLines < 0) {
            cmd += listOf("-S", "-")
        } else if (historyLines > 0) {
            cmd += listOf("-S", "-$historyLines")
        }
        val result = run(cmd, checkExit = false)
        if (result.exitCode != 0) return PaneProbe(alive = false, title = "", content = "")
        // display-message emits the title plus a newline, then capture-pane's output.
        return PaneProbe(
            alive = true,
            title = result.stdout.substringBefore('\n', "").trim(),
            content = result.stdout.substringAfter('\n', ""),
        )
    }

    fun attachArgv(taskId: String): List<String> =
        listOf(tmuxBinary(), "-L", SERVER, "attach-session", "-t", sessionName(taskId))

    /**
     * Blocks until the tmux session disappears (agent process exited) or [timeoutMs]
     * elapses. Returns 0 when the session ends, -1 on timeout.
     *
     * Fallback path only — backends with a live scrape loop learn the exit from that loop
     * instead, since every poll here costs a fork. Keep the interval coarse for the same reason.
     */
    fun waitExit(taskId: String, timeoutMs: Long = Long.MAX_VALUE): Int {
        val deadline = if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        while (hasSession(taskId)) {
            if (System.currentTimeMillis() >= deadline) return -1
            Thread.sleep(500)
        }
        return 0
    }

    /**
     * @param cwd when set to an existing directory, the launch script `cd`s there first.
     *   Needed when the tmux server process has a deleted cwd — `new-session -c` alone
     *   does not repair getcwd for the pane shell / Node `uv_cwd`.
     */
    internal fun buildLaunchCommand(
        argv: List<String>,
        env: Map<String, String>,
        cwd: String? = null,
    ): String {
        val exports = env.entries.joinToString(" ") { (k, v) ->
            "${shellQuote(k)}=${shellQuote(v)}"
        }
        val command = argv.joinToString(" ") { shellQuote(it) }
        // Do not `exec` the agent — when it exits, keep the tmux pane open so the GUI
        // can attach, capture scrollback, and the user can type a follow-up at a shell.
        val keepShell = "exec ${shellQuote("/bin/sh")}"
        val body = if (exports.isEmpty()) {
            "$command; $keepShell"
        } else {
            "env -i $exports $command; $keepShell"
        }
        val dir = cwd?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            ?.absoluteFile?.normalize()?.absolutePath
        return if (dir != null) {
            "cd ${shellQuote(dir)} && $body"
        } else {
            body
        }
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(
        cmd: List<String>,
        checkExit: Boolean = true,
        workingDirectory: File = safeProcessWorkingDirectory(),
    ): ProcResult {
        val process = ProcessBuilder(cmd)
            .directory(workingDirectory)
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
    private val serverConfigured = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sessionCache = AtomicReference<Pair<Long, Set<String>>?>(null)

    /** How long [sessionExists] trusts a `list-sessions` snapshot. */
    private const val SESSION_CACHE_MS = 1_000L

    /** Never inherit a deleted JVM cwd when forking tmux — that can poison new sessions. */
    private fun safeProcessWorkingDirectory(): File {
        val home = File(System.getProperty("user.home"))
        return home.takeIf { it.isDirectory } ?: AgentScratchWorkspace.ensure()
    }

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
