package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.service.ActionRunService
import app.andy.terminal.createAndyJediTermWidget
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class DesktopActionRunService(
    private val scope: CoroutineScope,
    private val terminalAppearance: () -> TerminalAppearanceSnapshot = { TerminalAppearanceSnapshot() },
) : ActionRunService {
    private data class RunHandle(
        val process: PtyProcess?,
        val terminal: JediTermWidget?,
    )

    private val nextRun = AtomicInteger(1)
    private val handles = ConcurrentHashMap<String, RunHandle>()
    private val _running = MutableStateFlow<List<RunningAction>>(emptyList())
    override val running: StateFlow<List<RunningAction>> = _running

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            handles.values.forEach { handle -> handle.process?.let(::killTree) }
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
            val environment = HashMap(System.getenv()).apply {
                putAll(project.env)
                putAll(action.env)
                put("TERM", "xterm-256color")
                if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                    put("LC_CTYPE", "UTF-8")
                }
            }
            val process = PtyProcessBuilder()
                .setDirectory(cwd)
                .setCommand(command.toTypedArray())
                .setEnvironment(environment)
                .setInitialColumns(120)
                .setInitialRows(32)
                .setConsole(false)
                .setUseWinConPty(true)
                .start()
            val connector = PtyTtyConnector(process, command)
            val appearance = terminalAppearance()
            val terminal = onSwingEdt {
                createAndyJediTermWidget(120, 32, appearance).apply {
                    ttyConnector = connector
                    start()
                }
            }
            process to terminal
        }.fold(
            onSuccess = { (process, terminal) ->
                val handle = RunHandle(process, terminal)
                handles[runId] = handle
                initialCommand?.let { command ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { terminal.ttyConnector.write("$command\r") }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    val exitCode = runCatching { process.waitFor() }.getOrElse { -1 }
                    markComplete(runId, if (exitCode == 0) ActionRunStatus.Exited else ActionRunStatus.Failed, exitCode)
                }
            },
            onFailure = { error ->
                markComplete(runId, ActionRunStatus.Failed, null)
                handles[runId] = RunHandle(null, null)
            },
        )
        return runId
    }

    override fun stop(runId: String) {
        val handle = handles[runId] ?: return
        scope.launch(Dispatchers.IO) {
            handle.terminal?.let(::closeTerminal)
            handle.process?.let(::killTree)
            markComplete(runId, ActionRunStatus.Stopped, null)
        }
    }

    override fun clear(runId: String) {
        val handle = handles.remove(runId)
        scope.launch(Dispatchers.IO) {
            handle?.terminal?.let(::closeTerminal)
            handle?.process?.takeIf { it.isAlive }?.let(::killTree)
        }
        _running.update { runs -> runs.filterNot { it.runId == runId } }
    }

    internal fun terminalWidget(runId: String): JediTermWidget? = handles[runId]?.terminal

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

    private fun killTree(process: Process) {
        // Pty4J intentionally does not expose a Java ProcessHandle, so its
        // descendants() implementation throws on Unix. Destroying the PTY
        // process closes the terminal session and sends the shell its signal.
        if (process is PtyProcess) {
            process.destroy()
            if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            return
        }
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun closeTerminal(terminal: JediTermWidget) {
        onSwingEdt { terminal.close() }
    }

    private fun <T> onSwingEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}

private class PtyTtyConnector(
    private val process: PtyProcess,
    commandLine: List<String>,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8, commandLine) {
    override fun resize(termSize: TermSize) {
        if (isConnected) process.setWinSize(WinSize(termSize.columns, termSize.rows))
    }

    override fun isConnected(): Boolean = process.isAlive

    override fun getName(): String = "Local"
}
