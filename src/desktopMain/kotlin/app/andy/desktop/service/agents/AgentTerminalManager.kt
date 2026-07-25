package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.KetraTermBackend
import app.andy.terminal.SCROLLBACK_SESSION_SEPARATOR
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalMode
import app.andy.terminal.TerminalSession
import app.andy.terminal.TerminalSessions
import app.andy.terminal.TmuxAndy
import app.andy.terminal.TmuxAgentBackend
import app.andy.terminal.TmuxAttachBackend
import app.andy.terminal.atomicWriteText
import app.andy.terminal.capScrollbackSize
import app.andy.terminal.createScrollbackReplayTerminal
import app.andy.terminal.formatScrollbackForDisplay
import app.andy.terminal.isScrollbackDisplayNoise
import app.andy.terminal.joinReadableLines
import app.andy.terminal.replayCaptureReadableLines
import app.andy.terminal.resolveScrollbackForReplay
import io.github.ketraterm.ui.swing.api.SwingTerminal
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
import java.awt.Component
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * How agent CLIs are hosted.
 *
 * - [TmuxWithAttach]: create `tmux -L andy` session + KetraTerm attach (GUI default)
 * - [TmuxHeadless]: create tmux session only (daemon)
 * - [DirectPty]: legacy Pty4J spawn (tests / fallback when tmux unavailable)
 */
enum class AgentTerminalMode {
    TmuxWithAttach,
    TmuxHeadless,
    DirectPty,
}

/**
 * Owns embedded agent [TerminalSession]s. Agents run in tmux by default; the GUI
 * attaches via KetraTerm for rendering.
 */
class AgentTerminalManager(
    private val scope: CoroutineScope,
    private val terminalAppearance: () -> TerminalAppearanceSnapshot = { TerminalAppearanceSnapshot() },
    private val scrollbackFile: (taskId: String) -> File = { id ->
        File(File(System.getProperty("user.home"), ".andy/agents"), "$id/scrollback.ansi")
    },
    private val mode: AgentTerminalMode = defaultMode(),
) {
    data class Handle(
        val taskId: String,
        val session: TerminalSession,
        val widget: SwingTerminal?,
        val artifacts: AgentWorkflowArtifacts,
        val statusTracker: AgentStatusTracker,
        val artifactDir: File,
        val scrollbackPath: File,
        val committedScrollbackPrefix: String,
        val capturedLineKeys: MutableSet<String>,
        val capturedLines: MutableList<String>,
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

    fun terminalWidget(taskId: String): SwingTerminal? {
        val handle = handles[taskId] ?: return null
        handle.widget?.let { return it }
        return when (val session = handle.session) {
            is KetraTermBackend -> session.swingTerminal()
            is TmuxAttachBackend -> session.swingTerminal()
            else -> null
        }
    }

    fun terminalComponent(taskId: String): Component? = terminalWidget(taskId)

    fun isAlive(taskId: String): Boolean {
        val handle = handles[taskId]
        if (handle?.session?.isAlive == true) return true
        // GUI may have closed while the daemon/tmux session still runs.
        return TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)
    }

    fun scrollbackPath(taskId: String): File = scrollbackFile(taskId)

    fun hasScrollback(taskId: String): Boolean {
        val file = scrollbackFile(taskId)
        return file.isFile && file.length() > 0L
    }

    /**
     * Cleaned plain-text history for finished-chat viewing (TUI chrome stripped).
     * Prefer this over [openScrollbackReplay] in the GUI — Compose text avoids
     * wide rule-line horizontal scroll / right-shifted layouts from terminal replay.
     */
    fun scrollbackDisplayText(taskId: String): String? {
        val file = scrollbackFile(taskId)
        if (!file.isFile || file.length() == 0L) return null
        val content = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val resolved = resolveScrollbackForReplay(content)
        return formatScrollbackForDisplay(resolved).ifBlank {
            resolved.lines()
                .filterNot { isScrollbackDisplayNoise(it) }
                .joinToString("\n")
                .trim()
        }.takeIf { it.isNotBlank() }
    }

    /**
     * Build a read-only KetraTerm widget that replays [scrollback.ansi] for viewing
     * finished chats. Caller owns dispose. Returns null when no history is available.
     */
    fun openScrollbackReplay(taskId: String): SwingTerminal? {
        val text = scrollbackDisplayText(taskId) ?: return null
        return createScrollbackReplayTerminal(
            content = text,
            appearance = terminalAppearance(),
        )
    }

    /** Push latest Settings appearance into live sessions. */
    fun reloadAppearance() {
        val appearance = terminalAppearance()
        handles.values.forEach { handle ->
            when (val session = handle.session) {
                is KetraTermBackend -> session.updateAppearance(appearance)
                is TmuxAttachBackend -> session.updateAppearance(appearance)
            }
        }
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
        val handle = handles[taskId]
        if (handle != null) {
            handle.session.writeText(text)
            return
        }
        // Detached tmux session (e.g. GUI reattach pending).
        if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            TmuxAndy.sendKeys(taskId, text)
        }
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
        if (handles[taskId]?.session is TmuxAgentBackend ||
            (handles[taskId] == null && TmuxAndy.hasSession(taskId))
        ) {
            TmuxAndy.sendEnter(taskId)
        } else {
            writeRaw(taskId, "\r")
        }
        if (body.contains('\n')) {
            delay(SUBMIT_KEY_GAP_MS)
            if (handles[taskId]?.session is TmuxAgentBackend ||
                (handles[taskId] == null && TmuxAndy.hasSession(taskId))
            ) {
                TmuxAndy.sendEnter(taskId)
            } else {
                writeRaw(taskId, "\r")
            }
        }
    }

    fun markSeen(taskId: String) {
        handles[taskId]?.statusTracker?.markSeen()
        refreshStatus(taskId)
    }

    /**
     * Spawns an interactive CLI in tmux (or DirectPty), installs hooks when possible,
     * and starts artifact + status watchers. Returns the handle once the session
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

        val resolvedMode = resolveMode()
        val session = when (resolvedMode) {
            AgentTerminalMode.TmuxHeadless -> {
                TerminalSessions.create(
                    TerminalLaunchRequest(
                        sessionId = task.id,
                        argv = argv,
                        cwd = cwdPath,
                        env = env,
                        appearance = terminalAppearance(),
                        mode = TerminalMode.TmuxAgent,
                        killTmuxOnClose = true,
                    ),
                )
            }
            AgentTerminalMode.TmuxWithAttach -> {
                TerminalSessions.create(
                    TerminalLaunchRequest(
                        sessionId = task.id,
                        argv = argv,
                        cwd = cwdPath,
                        env = env,
                        appearance = terminalAppearance(),
                        mode = TerminalMode.TmuxAttach,
                        killTmuxOnClose = true,
                    ),
                )
            }
            AgentTerminalMode.DirectPty -> {
                TerminalSessions.create(
                    TerminalLaunchRequest(
                        sessionId = task.id,
                        argv = argv,
                        cwd = cwdPath,
                        env = env,
                        appearance = terminalAppearance(),
                        mode = TerminalMode.DirectPty,
                    ),
                )
            }
        }

        val widget = when (session) {
            is KetraTermBackend -> session.swingTerminal()
            is TmuxAttachBackend -> session.swingTerminal()
            else -> null
        }
        if (resolvedMode == AgentTerminalMode.TmuxWithAttach || resolvedMode == AgentTerminalMode.DirectPty) {
            check(widget != null) {
                "terminal widget missing after start (backend=${session::class.simpleName}, mode=$resolvedMode)"
            }
        }

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
        val (committedPrefix, capturedKeys, capturedLines) = loadCommittedScrollbackState(scrollbackPath)
        val handle = Handle(
            taskId = task.id,
            session = session,
            widget = widget,
            artifacts = artifacts,
            statusTracker = tracker,
            artifactDir = artifactDir,
            scrollbackPath = scrollbackPath,
            committedScrollbackPrefix = committedPrefix,
            capturedLineKeys = capturedKeys,
            capturedLines = capturedLines,
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

    /**
     * Attach a KetraTerm viewer to an existing tmux session (GUI reattach after restart).
     * No-op if already attached or session missing.
     */
    suspend fun attachExisting(taskId: String, isTabSeen: () -> Boolean = { false }): Handle? =
        withContext(Dispatchers.IO) {
            get(taskId)?.let { return@withContext it }
            if (!TmuxAndy.isAvailable() || !TmuxAndy.hasSession(taskId)) return@withContext null
            val session = TmuxAttachBackend(
                sessionId = taskId,
                appearance = terminalAppearance(),
                killTmuxOnClose = false,
            )
            session.attach()
            val widget = session.swingTerminal()
                ?: error("terminal widget missing after tmux attach")
            val cwdPath = AgentScratchWorkspace.resolveCwd(null)
            val artifactDir = AgentWorkflowArtifacts.dirFor(File(cwdPath), taskId)
            artifactDir.mkdirs()
            val artifacts = AgentWorkflowArtifacts(scope, taskId, artifactDir)
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = taskId,
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                isTabSeen = isTabSeen,
            )
            artifacts.start()
            tracker.start()
            val scrollbackPath = scrollbackFile(taskId)
            val (committedPrefix, capturedKeys, capturedLines) = loadCommittedScrollbackState(scrollbackPath)
            val handle = Handle(
                taskId = taskId,
                session = session,
                widget = widget,
                artifacts = artifacts,
                statusTracker = tracker,
                artifactDir = artifactDir,
                scrollbackPath = scrollbackPath,
                committedScrollbackPrefix = committedPrefix,
                capturedLineKeys = capturedKeys,
                capturedLines = capturedLines,
            )
            handles[taskId] = handle
            handle.scrollbackJob = scope.launch(Dispatchers.IO) {
                while (isActive && handles[taskId] === handle) {
                    persistScrollback(handle)
                    delay(SCROLLBACK_FLUSH_MILLIS)
                }
            }
            handle.waitJob = scope.launch {
                tracker.status.collect { status -> publishStatus(taskId, status) }
            }
            bumpSessionsRevision()
            scope.launch(Dispatchers.Main.immediate) { bumpSessionsRevision() }
            handle
        }

    /** Latest visible terminal buffer, for prompt-readiness checks. */
    fun bufferSnapshot(taskId: String): String {
        handles[taskId]?.session?.bufferSnapshot()?.takeIf { it.isNotBlank() }?.let { return it }
        if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            return TmuxAndy.capturePane(taskId, historyLines = 80).trimEnd()
        }
        return ""
    }

    fun liveSessionStatus(taskId: String): AgentSessionStatus? =
        handles[taskId]?.statusTracker?.status?.value

    /** Blocks until the PTY/tmux session exits (or stop was requested). */
    suspend fun awaitExit(taskId: String): Int {
        val handle = handles[taskId]
        if (handle != null) {
            return handle.session.exitCode.first { it != null } ?: -1
        }
        // Headless wait when only tmux remains.
        if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            return withContext(Dispatchers.IO) { TmuxAndy.waitExit(taskId) }
        }
        return -1
    }

    fun stop(taskId: String) {
        val handle = handles.remove(taskId)
        if (handle != null) {
            handle.stopRequested = true
            handle.scrollbackJob?.cancel()
            runCatching { persistScrollback(handle) }
            handle.statusTracker.close()
            handle.artifacts.close()
            handle.waitJob?.cancel()
            runCatching { handle.session.close() }
        } else if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            TmuxAndy.killSession(taskId)
        }
        publishStatus(taskId, null)
        perTaskStatuses.remove(taskId)
        bumpSessionsRevision()
    }

    fun clear(taskId: String) {
        stop(taskId)
    }

    fun stopAll() {
        handles.keys.toList().forEach(::stop)
    }

    private fun resolveMode(): AgentTerminalMode {
        if (mode == AgentTerminalMode.DirectPty) return AgentTerminalMode.DirectPty
        if (!TmuxAndy.isAvailable()) {
            if (mode == AgentTerminalMode.TmuxHeadless) {
                error(
                    "tmux is required for headless Andy agent sessions. " +
                        "Install it (e.g. `brew install tmux`) or set ANDY_TMUX.",
                )
            }
            // GUI soft-fallback so local dev still works without tmux.
            return AgentTerminalMode.DirectPty
        }
        return mode
    }

    private fun persistScrollback(handle: Handle) {
        val export = when (val session = handle.session) {
            is KetraTermBackend -> persistReadableScrollback(
                capture = { session.captureReadableLines(it) },
                tee = { session.scrollbackAnsi() },
                handle = handle,
            )
            is TmuxAttachBackend -> persistReadableScrollback(
                capture = { session.captureReadableLines(it) },
                tee = { session.scrollbackAnsi() },
                handle = handle,
            )
            is TmuxAgentBackend -> {
                if (TmuxAndy.hasSession(handle.taskId)) {
                    formatScrollbackForDisplay(
                        resolveScrollbackForReplay(
                            TmuxAndy.capturePane(handle.taskId, historyLines = -1),
                        ),
                    )
                } else {
                    ""
                }
            }
            else -> {
                if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(handle.taskId)) {
                    formatScrollbackForDisplay(
                        resolveScrollbackForReplay(
                            TmuxAndy.capturePane(handle.taskId, historyLines = -1),
                        ),
                    )
                } else {
                    ""
                }
            }
        }
        if (export.isBlank() && handle.committedScrollbackPrefix.isBlank()) return
        val content = capScrollbackSize(handle.committedScrollbackPrefix + export)
        atomicWriteText(handle.scrollbackPath, content)
    }

    private fun persistReadableScrollback(
        capture: (MutableSet<String>) -> List<String>,
        tee: () -> String,
        handle: Handle,
    ): String {
        val fresh = capture(handle.capturedLineKeys).ifEmpty {
            val raw = tee()
            if (raw.isBlank()) {
                emptyList()
            } else {
                replayCaptureReadableLines(raw).filter { line ->
                    val key = line.trim()
                    key.isNotEmpty() && handle.capturedLineKeys.add(key)
                }
            }
        }
        if (fresh.isNotEmpty()) {
            handle.capturedLines.addAll(fresh)
        }
        return joinReadableLines(handle.capturedLines)
    }

    private fun loadCommittedScrollbackState(file: File): Triple<String, MutableSet<String>, MutableList<String>> {
        val existing = runCatching {
            if (file.isFile && file.length() > 0L) file.readText() else ""
        }.getOrDefault("")
        if (existing.isBlank()) {
            return Triple("", LinkedHashSet(), mutableListOf())
        }
        val normalized = resolveScrollbackForReplay(existing).trimEnd()
        if (normalized.isBlank()) {
            return Triple("", LinkedHashSet(), mutableListOf())
        }
        val lines = normalized.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val keys = lines.map { it.trim() }.toCollection(LinkedHashSet())
        return Triple(
            normalized + SCROLLBACK_SESSION_SEPARATOR,
            keys,
            lines.toMutableList(),
        )
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

        fun defaultMode(): AgentTerminalMode =
            when (System.getenv("ANDY_TERMINAL_MODE")?.lowercase()) {
                "direct", "pty", "directpty" -> AgentTerminalMode.DirectPty
                "headless", "tmuxheadless" -> AgentTerminalMode.TmuxHeadless
                else -> AgentTerminalMode.TmuxWithAttach
            }
    }
}

/** Scrub IDE/proxy env that breaks vendor CLIs, then apply project overrides. */
fun buildAgentLaunchEnvironment(projectEnv: Map<String, String>): Map<String, String> =
    app.andy.terminal.buildTerminalLaunchEnvironment(projectEnv)

internal fun scrubInheritedAgentEnvironment(env: MutableMap<String, String>) {
    app.andy.terminal.scrubInheritedTerminalEnvironment(env)
}
