package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.service.ActionRunService
import app.andy.terminal.KetraTermBackend
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalSession
import app.andy.terminal.TerminalSessions
import app.andy.terminal.buildTerminalLaunchEnvironment
import io.github.ketraterm.ui.swing.api.SwingTerminal
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

class DesktopActionRunService(
    private val scope: CoroutineScope,
    private val terminalAppearance: () -> TerminalAppearanceSnapshot = { TerminalAppearanceSnapshot() },
) : ActionRunService {
    private data class RunHandle(
        val session: TerminalSession?,
        val terminal: SwingTerminal?,
    )

    private val nextRun = AtomicInteger(1)
    private val handles = ConcurrentHashMap<String, RunHandle>()
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

    override fun run(project: ActionProject, action: ProjectAction): String = start(
        project = project,
        action = action,
        initialCommand = action.command.takeIf { it.isNotBlank() },
    )

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
            status = ActionRunStatus.Running,
            startedAtMillis = System.currentTimeMillis(),
        )
        _running.update { it + snapshot }

        runCatching {
            val command = persistentShellCommand()
            val environment = buildTerminalLaunchEnvironment(
                project.env + action.env,
            )
            val session = TerminalSessions.create(
                TerminalLaunchRequest(
                    sessionId = runId,
                    argv = command,
                    cwd = cwd,
                    env = environment,
                    appearance = terminalAppearance(),
                ),
            )
            val terminal = (session as? KetraTermBackend)?.swingTerminal()
                ?: error("terminal widget missing after start")
            session to terminal
        }.fold(
            onSuccess = { (session, terminal) ->
                val handle = RunHandle(session, terminal)
                handles[runId] = handle
                initialCommand?.let { command ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { session.writeText("$command\r") }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    val exitCode = runCatching {
                        session.exitCode.first { it != null }
                    }.getOrNull() ?: -1
                    markComplete(
                        runId,
                        if (exitCode == 0) ActionRunStatus.Exited else ActionRunStatus.Failed,
                        exitCode,
                    )
                }
            },
            onFailure = {
                markComplete(runId, ActionRunStatus.Failed, null)
                handles[runId] = RunHandle(null, null)
            },
        )
        return runId
    }

    override fun stop(runId: String) {
        val handle = handles[runId] ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { handle.session?.close() }
            markComplete(runId, ActionRunStatus.Stopped, null)
        }
    }

    override fun clear(runId: String) {
        val handle = handles.remove(runId)
        scope.launch(Dispatchers.IO) {
            runCatching { handle?.session?.close() }
        }
        _running.update { runs -> runs.filterNot { it.runId == runId } }
    }

    internal fun terminalWidget(runId: String): SwingTerminal? = handles[runId]?.terminal

    internal fun writeToTerminal(runId: String, text: String) {
        handles[runId]?.session?.writeText(text)
    }

    internal fun bufferSnapshot(runId: String): String =
        handles[runId]?.session?.bufferSnapshot().orEmpty()

    /** Push latest Settings appearance into live project terminals. */
    fun reloadAppearance() {
        val appearance = terminalAppearance()
        handles.values.forEach { handle ->
            (handle.session as? KetraTermBackend)?.updateAppearance(appearance)
        }
    }

    private fun markComplete(runId: String, status: ActionRunStatus, exitCode: Int?) {
        _running.update { runs ->
            runs.map { run ->
                if (run.runId == runId && run.status == ActionRunStatus.Running) {
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
