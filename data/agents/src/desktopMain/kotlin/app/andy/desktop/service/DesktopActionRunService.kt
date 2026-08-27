package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.service.ActionRunService
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalSession
import app.andy.terminal.TerminalSessions
import app.andy.terminal.buildTerminalLaunchEnvironment
import app.andy.terminal.rust.RustTerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Creates (and starts) a run's PTY backend. Injectable so tests can gate spawn timing. */
internal typealias SpawnSession =
    (runId: String, argv: List<String>, cwd: String, env: Map<String, String>) -> RustTerminalBackend

class DesktopActionRunService(
    private val scope: CoroutineScope,
    private val terminalAppearance: () -> TerminalAppearanceSnapshot = { TerminalAppearanceSnapshot() },
    internal val spawnSession: SpawnSession = { runId, argv, cwd, env ->
        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = runId,
                argv = argv,
                cwd = cwd,
                env = env,
                appearance = terminalAppearance(),
            ),
        )
        session as? RustTerminalBackend
            ?: error("terminal view missing after start: ${session::class.simpleName}")
    },
) : ActionRunService {
    private data class RunHandle(
        val session: TerminalSession?,
        val rustTerminal: RustTerminalBackend? = null,
    )

    private val nextRun = AtomicInteger(1)
    private val handles = ConcurrentHashMap<String, RunHandle>()
    private val pendingAppends = ConcurrentHashMap<String, MutableList<String>>()

    // Serializes the spawn handshake (status check + handle registration) against stop()/clear(),
    // so a run cancelled while its PTY is still spawning never publishes a live backend afterwards.
    private val lifecycleLock = Any()

    private val _running = MutableStateFlow<List<RunningAction>>(emptyList())
    override val running: StateFlow<List<RunningAction>> = _running

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            handles.values.forEach { handle ->
                runCatching { handle.session?.close() }
            }
        })
    }

    override fun openShell(project: ActionProject): String = start(
        project = project,
        action = ProjectAction(
            id = "terminal",
            name = "Terminal",
            icon = "terminal",
            command = "",
        ),
        initialCommand = null,
    )

    override fun run(project: ActionProject, action: ProjectAction): String {
        val command = action.command.takeIf { it.isNotBlank() }
        synchronized(lifecycleLock) {
            val existing = _running.value.lastOrNull {
                it.projectId == project.id &&
                    it.actionId == action.id &&
                    it.status in ACTIVE_STATUSES
            }
            if (existing != null && isAppendable(existing.runId)) {
                if (command != null) appendCommand(existing.runId, command)
                return existing.runId
            }
        }
        clearExistingRuns(project.id, action.id)
        return start(
            project = project,
            action = action,
            initialCommand = command,
        )
    }

    private fun start(project: ActionProject, action: ProjectAction, initialCommand: String?): String {
        val runId = "run-${nextRun.getAndIncrement()}"
        val cwd = resolveCwd(project, action)
        val snapshot = RunningAction(
            runId = runId,
            projectId = project.id,
            actionId = action.id,
            actionName = action.name,
            icon = action.icon,
            command = action.command,
            cwd = cwd,
            status = ActionRunStatus.Starting,
            startedAtMillis = System.currentTimeMillis(),
        )
        _running.update { it + snapshot }

        // PTY spawn does synchronous shell/native-library work (login shell capture, native
        // lib load), so it runs off the UI thread and the dock tab appears before it finishes.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val command = persistentShellCommand()
                val environment = buildTerminalLaunchEnvironment(
                    project.env + action.env,
                )
                spawnSession(runId, command, cwd, environment)
            }.fold(
                onSuccess = { rustTerminal ->
                    val registered = synchronized(lifecycleLock) {
                        val stillStarting = _running.value
                            .firstOrNull { it.runId == runId }
                            ?.status == ActionRunStatus.Starting
                        if (!stillStarting) {
                            false
                        } else {
                            handles[runId] = RunHandle(rustTerminal, rustTerminal)
                            _running.update { runs ->
                                runs.map { run ->
                                    if (run.runId == runId && run.status == ActionRunStatus.Starting) {
                                        run.copy(status = ActionRunStatus.Running)
                                    } else {
                                        run
                                    }
                                }
                            }
                            true
                        }
                    }
                    if (!registered) {
                        // Tab was closed/stopped while the PTY was still spawning; don't orphan it.
                        runCatching { rustTerminal.close() }
                        return@launch
                    }
                    initialCommand?.let { command ->
                        runCatching { rustTerminal.writeText("$command\r") }
                    }
                    drainPendingAppends(runId, rustTerminal)
                    val exitCode = runCatching {
                        rustTerminal.exitCode.first { it != null }
                    }.getOrNull() ?: -1
                    markComplete(
                        runId,
                        if (exitCode == 0) ActionRunStatus.Exited else ActionRunStatus.Failed,
                        exitCode,
                    )
                },
                onFailure = {
                    markComplete(runId, ActionRunStatus.Failed, null)
                    synchronized(lifecycleLock) {
                        // Publish a tombstone only if the run wasn't already cleared, so a
                        // cancelled run doesn't leak a stale map entry.
                        if (_running.value.any { it.runId == runId }) {
                            handles[runId] = RunHandle(null, null)
                        }
                    }
                },
            )
        }
        return runId
    }

    override fun stop(runId: String) {
        val handle = synchronized(lifecycleLock) {
            val handle = handles[runId]
            if (handle == null) {
                // PTY may still be spawning; marking it Stopped now makes start() close it
                // instead of registering a handle once the spawn finishes.
                markComplete(runId, ActionRunStatus.Stopped, null)
            }
            handle
        }
        if (handle != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { handle.session?.close() }
                markComplete(runId, ActionRunStatus.Stopped, null)
            }
        }
    }

    override fun sessionRootPid(runId: String): Long? =
        handles[runId]?.session?.pid ?: handles[runId]?.rustTerminal?.pid

    override fun clear(runId: String) {
        val handle = synchronized(lifecycleLock) {
            pendingAppends.remove(runId)
            val handle = handles.remove(runId)
            _running.update { runs -> runs.filterNot { it.runId == runId } }
            handle
        }
        scope.launch(Dispatchers.IO) {
            runCatching { handle?.session?.close() }
        }
    }

    fun rustTerminal(runId: String): RustTerminalBackend? = handles[runId]?.rustTerminal

    internal fun writeToTerminal(runId: String, text: String) {
        handles[runId]?.session?.writeText(text)
    }

    internal fun bufferSnapshot(runId: String): String =
        handles[runId]?.session?.bufferSnapshot().orEmpty()

    /** Push latest Settings appearance into live project terminals. */
    fun reloadAppearance() {
        val appearance = terminalAppearance()
        handles.values.forEach { handle ->
            (handle.session as? RustTerminalBackend)?.updateAppearance(appearance)
        }
    }

    private fun clearExistingRuns(projectId: String, actionId: String) {
        _running.value
            .filter { it.projectId == projectId && it.actionId == actionId }
            .forEach { clear(it.runId) }
    }

    private fun isAppendable(runId: String): Boolean {
        val terminal = handles[runId]?.rustTerminal ?: return true
        return terminal.isAlive
    }

    private fun appendCommand(runId: String, command: String) {
        val terminal = handles[runId]?.rustTerminal
        if (terminal != null && terminal.isAlive) {
            runCatching { terminal.writeText("$command\r") }
        } else {
            pendingAppends.computeIfAbsent(runId) { mutableListOf() }.add(command)
        }
    }

    private fun drainPendingAppends(runId: String, terminal: RustTerminalBackend) {
        pendingAppends.remove(runId)?.forEach { command ->
            runCatching { terminal.writeText("$command\r") }
        }
    }

    private companion object {
        private val ACTIVE_STATUSES = setOf(ActionRunStatus.Starting, ActionRunStatus.Running)
    }

    private fun markComplete(runId: String, status: ActionRunStatus, exitCode: Int?) {
        val active = setOf(ActionRunStatus.Starting, ActionRunStatus.Running)
        _running.update { runs ->
            runs.map { run ->
                if (run.runId == runId && run.status in active) {
                    run.copy(status = status, exitCode = exitCode)
                } else {
                    run
                }
            }
        }
    }

    private fun persistentShellCommand(): List<String> {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        return if (osName.contains("win")) {
            val shell = System.getenv("COMSPEC")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
            listOf(shell, "/k")
        } else {
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
            val shellName = shell.replace('\\', '/').substringAfterLast('/')
            if (shellName == "sh") listOf(shell) else listOf(shell, "-l")
        }
    }

    private fun resolveCwd(project: ActionProject, action: ProjectAction): String {
        val override = action.cwd?.takeIf { it.isNotBlank() }
        return when {
            override == null -> project.contextDir
            File(override).isAbsolute -> override
            else -> File(project.contextDir, override).path
        }
    }
}
