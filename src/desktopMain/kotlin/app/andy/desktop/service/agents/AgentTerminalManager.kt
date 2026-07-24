package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.JediTermBackend
import app.andy.terminal.SCROLLBACK_SESSION_SEPARATOR
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalSession
import app.andy.terminal.TerminalSessions
import app.andy.terminal.atomicWriteText
import app.andy.terminal.capScrollbackSize
import com.jediterm.terminal.ui.JediTermWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns embedded agent [TerminalSession]s. Replaces the headless ProcessBuilder
 * spawn path: composer launches → PTY + TUI → artifacts/hooks drive workflow.
 */
class AgentTerminalManager(
    private val scope: CoroutineScope,
    private val terminalAppearance: () -> TerminalAppearanceSnapshot = { TerminalAppearanceSnapshot() },
    private val scrollbackFile: (taskId: String) -> File = { id ->
        File(File(System.getProperty("user.home"), ".andy/agents"), "$id/scrollback.ansi")
    },
) {
    data class Handle(
        val taskId: String,
        val session: TerminalSession,
        val widget: JediTermWidget,
        val artifacts: AgentWorkflowArtifacts,
        val statusTracker: AgentStatusTracker,
        val artifactDir: File,
        val scrollbackPath: File,
        val committedScrollbackPrefix: String,
        @Volatile var stopRequested: Boolean = false,
        @Volatile var waitJob: Job? = null,
        @Volatile var scrollbackJob: Job? = null,
    )

    private val handles = ConcurrentHashMap<String, Handle>()
    private val perTaskStatuses = ConcurrentHashMap<String, MutableStateFlow<AgentSessionStatus?>>()
    private val _sessionStatuses = MutableStateFlow<Map<String, AgentSessionStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<String, AgentSessionStatus>> = _sessionStatuses.asStateFlow()

    /**
     * Bumped whenever a session starts or stops so Compose can re-query
     * [terminalWidget] — the widget is created asynchronously after createAndStart returns.
     */
    private val _sessionsRevision = MutableStateFlow(0L)
    val sessionsRevision: StateFlow<Long> = _sessionsRevision.asStateFlow()

    private val _attachedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Task ids that currently have an attachable terminal widget. */
    val attachedTaskIds: StateFlow<Set<String>> = _attachedTaskIds.asStateFlow()

    fun statusFlow(taskId: String): StateFlow<AgentSessionStatus?> =
        perTaskStatuses.computeIfAbsent(taskId) { MutableStateFlow(null) }

    fun get(taskId: String): Handle? = handles[taskId]

    fun terminalWidget(taskId: String): JediTermWidget? =
        handles[taskId]?.widget ?: (handles[taskId]?.session as? JediTermBackend)?.terminalWidget()

    fun isAlive(taskId: String): Boolean = handles[taskId]?.session?.isAlive == true

    fun scrollbackPath(taskId: String): File = scrollbackFile(taskId)

    fun hasScrollback(taskId: String): Boolean {
        val file = scrollbackFile(taskId)
        return file.isFile && file.length() > 0L
    }

    private fun bumpSessionsRevision() {
        _sessionsRevision.value = _sessionsRevision.value + 1
        _attachedTaskIds.value = handles.keys.filterTo(mutableSetOf()) { id ->
            terminalWidget(id) != null
        }
    }
    fun write(taskId: String, text: String) {
        val body = text.trimEnd('\r', '\n')
        if (body.isEmpty()) return
        scope.launch { submitText(taskId, body) }
    }

    /** Write raw bytes/text with no automatic Enter (used for retries). */
    fun writeRaw(taskId: String, text: String) {
        handles[taskId]?.session?.writeText(text)
    }

    /**
     * Type [body] into a live TUI, then submit it. Splits type + Enter so Ink/React
     * TUIs register the characters; multiline bodies get a second Enter because the
     * first often only exits paste/compose mode.
     */
    suspend fun submitText(taskId: String, body: String) {
        if (!isAlive(taskId)) return
        writeRaw(taskId, body)
        delay(SUBMIT_KEY_GAP_MS)
        writeRaw(taskId, "\r")
        if (body.contains('\n')) {
            delay(SUBMIT_KEY_GAP_MS)
            writeRaw(taskId, "\r")
        }
    }

    fun markSeen(taskId: String) {
        handles[taskId]?.statusTracker?.markSeen()
        refreshStatus(taskId)
    }

    /**
     * Spawns an interactive CLI in a PTY, installs hooks when possible, and
     * starts artifact + status watchers. Returns the handle once the session
     * has started (process may still be booting its TUI).
     */
    suspend fun start(
        task: AgentTask,
        argv: List<String>,
        env: Map<String, String>,
        isTabSeen: () -> Boolean = { false },
    ): Handle = withContext(Dispatchers.IO) {
        stop(task.id)
        // Without a project cwd, use Andy scratch — never $HOME (Claude trust dialogs
        // + hook install would otherwise target the user's global ~/.claude).
        val cwdPath = AgentScratchWorkspace.resolveCwd(task.cwd)
        val cwd = File(cwdPath)
        val artifactDir = AgentWorkflowArtifacts.dirFor(cwd, task.id)
        artifactDir.mkdirs()
        if (task.agent == AgentKind.ClaudeCode) {
            if (AgentScratchWorkspace.isScratch(cwdPath)) {
                AgentScratchWorkspace.ensureClaudeTrust(cwd)
            }
            installClaudeStatusHooks(cwd, artifactDir)
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = task.id,
                argv = argv,
                cwd = cwdPath,
                env = env,
                appearance = terminalAppearance(),
            ),
        )
        val widget = (session as? JediTermBackend)?.terminalWidget()
            ?: error("terminal widget missing after start (backend=${session::class.simpleName})")
        val artifacts = AgentWorkflowArtifacts(scope, task.id, artifactDir)
        val tracker = AgentStatusTracker(
            scope = scope,
            taskId = task.id,
            agent = task.agent,
            artifactDir = artifactDir,
            session = session,
            isTabSeen = isTabSeen,
        )
        artifacts.start()
        tracker.start()
        val scrollbackPath = scrollbackFile(task.id)
        val committedPrefix = loadCommittedScrollbackPrefix(scrollbackPath)
        val handle = Handle(
            taskId = task.id,
            session = session,
            widget = widget,
            artifacts = artifacts,
            statusTracker = tracker,
            artifactDir = artifactDir,
            scrollbackPath = scrollbackPath,
            committedScrollbackPrefix = committedPrefix,
        )
        handles[task.id] = handle
        handle.scrollbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && handles[task.id] === handle) {
                persistScrollback(handle)
                delay(SCROLLBACK_FLUSH_MILLIS)
            }
        }
        bumpSessionsRevision()
        // Ensure Compose's Main collectors observe attachment after EDT widget creation.
        scope.launch(Dispatchers.Main.immediate) {
            bumpSessionsRevision()
        }
        handle.waitJob = scope.launch {
            tracker.status.collect { status ->
                publishStatus(task.id, status)
            }
        }
        publishStatus(task.id, AgentSessionStatus.Working)
        handle
    }

    /** Latest visible terminal buffer, for prompt-readiness checks. */
    fun bufferSnapshot(taskId: String): String =
        handles[taskId]?.session?.bufferSnapshot().orEmpty()

    fun liveSessionStatus(taskId: String): AgentSessionStatus? =
        handles[taskId]?.statusTracker?.status?.value


    /** Blocks until the PTY process exits (or stop was requested). */
    suspend fun awaitExit(taskId: String): Int {
        val handle = handles[taskId] ?: return -1
        val code = handle.session.exitCode.first { it != null } ?: -1
        return code
    }

    fun stop(taskId: String) {
        val handle = handles.remove(taskId) ?: return
        handle.stopRequested = true
        handle.scrollbackJob?.cancel()
        // Snapshot before tearing down the emulator widget.
        runCatching { persistScrollback(handle) }
        handle.statusTracker.close()
        handle.artifacts.close()
        handle.waitJob?.cancel()
        runCatching { handle.session.close() }
        publishStatus(taskId, null)
        perTaskStatuses.remove(taskId)
        bumpSessionsRevision()
    }

    fun clear(taskId: String) {
        stop(taskId)
    }

    private fun persistScrollback(handle: Handle) {
        val backend = handle.session as? JediTermBackend ?: return
        val export = backend.exportScrollbackAnsi()
        if (export.isBlank() && handle.committedScrollbackPrefix.isBlank()) return
        val content = capScrollbackSize(handle.committedScrollbackPrefix + export)
        atomicWriteText(handle.scrollbackPath, content)
    }

    private fun loadCommittedScrollbackPrefix(file: File): String {
        val existing = runCatching {
            if (file.isFile && file.length() > 0L) file.readText() else ""
        }.getOrDefault("")
        if (existing.isBlank()) return ""
        return existing.trimEnd() + SCROLLBACK_SESSION_SEPARATOR
    }

    private fun refreshStatus(taskId: String) {
        val status = handles[taskId]?.statusTracker?.status?.value ?: return
        publishStatus(taskId, status)
    }

    private fun publishStatus(taskId: String, status: AgentSessionStatus?) {
        if (status == null) {
            _sessionStatuses.value = _sessionStatuses.value - taskId
        } else {
            _sessionStatuses.value = _sessionStatuses.value + (taskId to status)
        }
        perTaskStatuses[taskId]?.value = status
    }

    companion object {
        private const val SCROLLBACK_FLUSH_MILLIS = 2_000L
        internal const val SUBMIT_KEY_GAP_MS = 80L
    }
}

/** Scrub IDE/proxy env that breaks vendor CLIs, then apply project overrides. */
fun buildAgentLaunchEnvironment(projectEnv: Map<String, String>): Map<String, String> =
    app.andy.terminal.buildTerminalLaunchEnvironment(projectEnv)

internal fun scrubInheritedAgentEnvironment(env: MutableMap<String, String>) {
    app.andy.terminal.scrubInheritedTerminalEnvironment(env)
}
