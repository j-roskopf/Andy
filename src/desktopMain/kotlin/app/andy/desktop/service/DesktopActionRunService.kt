package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.service.ActionRunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DesktopActionRunService(
    private val scope: CoroutineScope,
) : ActionRunService {
    private data class RunHandle(
        val process: Process?,
        val readerJob: Job?,
        val output: MutableStateFlow<List<String>>,
    )

    private val nextRun = AtomicInteger(1)
    private val handles = ConcurrentHashMap<String, RunHandle>()
    private val outputs = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()
    private val _running = MutableStateFlow<List<RunningAction>>(emptyList())
    override val running: StateFlow<List<RunningAction>> = _running
    private val emptyOutput = MutableStateFlow<List<String>>(emptyList())

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            handles.values.forEach { handle -> handle.process?.let(::killTree) }
        })
    }

    override fun run(project: ActionProject, action: ProjectAction): String {
        val runId = "run-${nextRun.getAndIncrement()}"
        val cwd = resolveCwd(project, action)
        val output = MutableStateFlow<List<String>>(emptyList())
        outputs[runId] = output
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
            ProcessBuilder(shellCommand(action.command))
                .directory(File(cwd))
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(project.env)
                    environment().putAll(action.env)
                }
                .start()
        }.fold(
            onSuccess = { process ->
                val job = scope.launch(Dispatchers.IO) {
                    runCatching { readProcessOutput(process, output) }
                    val exitCode = runCatching { process.waitFor() }.getOrElse { -1 }
                    markComplete(runId, if (exitCode == 0) ActionRunStatus.Exited else ActionRunStatus.Failed, exitCode)
                    handles[runId] = handles[runId]?.copy(readerJob = null) ?: RunHandle(process, null, output)
                }
                handles[runId] = RunHandle(process, job, output)
            },
            onFailure = { error ->
                output.update { it + "failed to start: ${error.message ?: error::class.simpleName.orEmpty()}" }
                markComplete(runId, ActionRunStatus.Failed, null)
                handles[runId] = RunHandle(null, null, output)
            },
        )
        return runId
    }

    override fun stop(runId: String) {
        val handle = handles[runId] ?: return
        scope.launch(Dispatchers.IO) {
            handle.process?.let(::killTree)
            handle.readerJob?.cancel()
            markComplete(runId, ActionRunStatus.Stopped, null)
        }
    }

    override fun clear(runId: String) {
        handles.remove(runId)
        outputs.remove(runId)
        _running.update { runs -> runs.filterNot { it.runId == runId } }
    }

    override fun output(runId: String): StateFlow<List<String>> = outputs[runId] ?: emptyOutput

    private suspend fun readProcessOutput(process: Process, output: MutableStateFlow<List<String>>) {
        process.inputStream.bufferedReader().useLines { lines ->
            val batch = mutableListOf<String>()
            var lastFlush = System.currentTimeMillis()
            fun flush() {
                if (batch.isEmpty()) return
                val next = batch.toList()
                batch.clear()
                output.update { (it + next).takeLast(2000) }
                lastFlush = System.currentTimeMillis()
            }
            for (line in lines) {
                batch += line
                val now = System.currentTimeMillis()
                if (batch.size >= 64 || now - lastFlush >= 100) {
                    flush()
                }
            }
            flush()
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

    private fun shellCommand(command: String): List<String> {
        val osName = System.getProperty("os.name").lowercase()
        return if (osName.contains("win")) {
            val shell = System.getenv("COMSPEC")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
            listOf(shell, "/c", command)
        } else {
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
            listOf(shell, "-lc", command)
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
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
