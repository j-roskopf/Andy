package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
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
import app.andy.terminal.ScrollbackAccumulator
import app.andy.terminal.StyledTerminalRow
import app.andy.terminal.atomicWriteText
import app.andy.terminal.capScrollbackSize
import app.andy.terminal.createScrollbackReplayTerminal
import app.andy.terminal.formatLegacyScrollbackForReplay
import app.andy.terminal.formatScrollbackForDisplay
import app.andy.terminal.isScrollbackDisplayNoise
import app.andy.terminal.looksLikeRawAnsiTee
import app.andy.terminal.resolveScrollbackForReplay
import app.andy.terminal.stripAnsi
import app.andy.terminal.styledRowsFromAnsiText
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val artifactPollIntervalMs: Long = AgentWorkflowArtifacts.DEFAULT_POLL_INTERVAL_MS,
) {
    data class Handle(
        val taskId: String,
        val session: TerminalSession,
        val widget: SwingTerminal?,
        val artifacts: AgentWorkflowArtifacts,
        val statusTracker: AgentStatusTracker,
        val artifactDir: File,
        val scrollbackPath: File,
        val scrollback: ScrollbackAccumulator,
        val foreground: AtomicBoolean = AtomicBoolean(true),
        @Volatile var stopRequested: Boolean = false,
        @Volatile var waitJob: Job? = null,
        @Volatile var scrollbackJob: Job? = null,
    )

    private val handles = ConcurrentHashMap<String, Handle>()

    /** Per-chat attach serialization; see [start] and [attachExisting]. */
    private val attachLocks = ConcurrentHashMap<String, Mutex>()

    private fun attachLock(taskId: String): Mutex = attachLocks.computeIfAbsent(taskId) { Mutex() }

    /**
     * Sessions this process started or attached to. Survives [detach] and
     * [releaseViewerOnly] — dropping the viewer while the agent keeps running must not
     * turn the chat read-only — but is emptied by [stop] and by process death, which is
     * what puts a chat back into read-only replay after Andy restarts.
     */
    private val ownedTaskIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Bumped whenever a session starts or stops so Compose can re-query
     * [terminalWidget] — the widget is created asynchronously after createAndStart returns.
     */
    private val _sessionsRevision = MutableStateFlow(0L)
    val sessionsRevision: StateFlow<Long> = _sessionsRevision.asStateFlow()

    private val _attachedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Task ids that currently have an attachable terminal widget. */
    val attachedTaskIds: StateFlow<Set<String>> = _attachedTaskIds.asStateFlow()

    private val _interactiveTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Task ids this run owns a live session for — the chats that render as typeable. */
    val interactiveTaskIds: StateFlow<Set<String>> = _interactiveTaskIds.asStateFlow()

    /**
     * True when this app run owns a still-running session for [taskId]. A tmux session
     * left behind by an earlier run is [isAlive] but never interactive.
     */
    fun isInteractive(taskId: String): Boolean = taskId in ownedTaskIds && isAlive(taskId)

    fun get(taskId: String): Handle? = handles[taskId]

    fun terminalWidget(taskId: String): SwingTerminal? {
        val handle = handles[taskId] ?: return null
        val session = handle.session
        if (session is TmuxAttachBackend && !session.isViewerAlive) {
            return null
        }
        return when (session) {
            is KetraTermBackend -> session.swingTerminal()
            is TmuxAttachBackend -> session.swingTerminal()
            else -> null
        }
    }

    /**
     * Drop the local KetraTerm viewer while keeping a live tmux session (or DirectPty
     * process) running. Called when the Compose surface unmounts so the next open can
     * [attachExisting] instead of reusing a disposed Swing widget.
     */
    fun releaseViewerOnly(taskId: String) {
        val handle = handles[taskId] ?: return
        handle.foreground.set(false)
        when (handle.session) {
            is TmuxAttachBackend -> runCatching { handle.session.releaseViewer() }
            else -> Unit
        }
        if (!isViewerAlive(taskId)) {
            pauseBackgroundPolling(handle)
        }
        bumpSessionsRevision()
    }

    /** Mark [taskId] as the only chat receiving foreground scrape cadence. */
    fun setOnlyForeground(taskId: String) {
        handles.forEach { (id, handle) ->
            val foreground = id == taskId
            handle.foreground.set(foreground)
            if (foreground && isViewerAlive(id)) {
                resumeBackgroundPolling(handle)
            } else if (!isViewerAlive(id)) {
                pauseBackgroundPolling(handle)
            }
        }
    }

    fun setForeground(taskId: String, foreground: Boolean) {
        handles[taskId]?.let { handle ->
            handle.foreground.set(foreground)
            if (foreground && isViewerAlive(taskId)) {
                resumeBackgroundPolling(handle)
            } else if (!isViewerAlive(taskId)) {
                pauseBackgroundPolling(handle)
            }
        }
    }

    fun clearForeground() {
        handles.values.forEach { handle ->
            handle.foreground.set(false)
            if (!isViewerAlive(handle.taskId)) {
                pauseBackgroundPolling(handle)
            }
        }
    }

    fun terminalComponent(taskId: String): Component? = terminalWidget(taskId)

    fun isViewerAlive(taskId: String): Boolean {
        val handle = handles[taskId] ?: return false
        val session = handle.session
        if (session is TmuxAttachBackend) {
            return session.isViewerAlive
        }
        return session.isAlive
    }

    fun isAlive(taskId: String): Boolean {
        val handle = handles[taskId]
        if (handle?.session?.isAlive == true) return true
        // GUI may have closed while the daemon/tmux session still runs. This runs once per
        // owned chat on every sessions-revision bump, so it reads the shared session-list
        // snapshot rather than forking a has-session per chat per navigation.
        return TmuxAndy.isAvailable() && TmuxAndy.sessionExists(taskId)
    }

    fun scrollbackPath(taskId: String): File = scrollbackFile(taskId)

    fun hasScrollback(taskId: String): Boolean = scrollbackReplayText(taskId) != null

    /**
     * Saved history exactly as it should be re-rendered: SGR styling, indentation and
     * box drawing preserved.
     *
     * Two older formats need repair first. A raw PTY tee would replay its cursor motion
     * as overlapping garbage, and unstyled scrapes carry tmux status bars and half-drawn
     * rows that no amount of replay can turn back into a terminal.
     */
    fun scrollbackReplayText(taskId: String): String? {
        val file = scrollbackFile(taskId)
        if (!file.isFile || file.length() == 0L) return null
        val content = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(content))) return null
        return when {
            looksLikeRawAnsiTee(content) -> cleanedScrollbackText(content)
            content.contains('\u001B') -> content.trimEnd().takeIf { it.isNotBlank() }
            else -> formatLegacyScrollbackForReplay(content).takeIf { it.isNotBlank() }
        }
    }

    private fun cleanedScrollbackText(content: String): String? {
        val resolved = stripAnsi(resolveScrollbackForReplay(content))
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
        val text = scrollbackReplayText(taskId) ?: return null
        return createScrollbackReplayTerminal(
            content = text,
            appearance = terminalAppearance(),
        )
    }

    /**
     * Flush live capture, then build the same read-only replay finished chats get.
     * Lets a running chat peek its history while the TUI owns the alt screen.
     */
    fun flushScrollbackReplay(taskId: String): SwingTerminal? {
        flushScrollback(taskId)
        return openScrollbackReplay(taskId)
    }

    private fun flushScrollback(taskId: String) {
        handles[taskId]?.let { handle -> runCatching { persistScrollback(handle) } }
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
        _interactiveTaskIds.value = ownedTaskIds.filterTo(mutableSetOf()) { id -> isAlive(id) }
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
        handles[taskId]?.let { handle ->
            handle.statusTracker.clearLatch()
            handle.statusTracker.markUserWorking()
        }
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

    /**
     * Spawns an interactive CLI in tmux (or DirectPty), installs hooks when possible,
     * and starts artifact + status watchers. Returns the handle once the session
     * has started (process may still be booting its TUI).
     *
     * Serialized with [attachExisting] via [attachLock]: attach happens before the
     * handle is registered, so a racing UI attach that saw `get(taskId) == null`
     * would otherwise spawn a second tmux client that never lands in [handles].
     */
    suspend fun start(
        task: AgentTask,
        argv: List<String>,
        env: Map<String, String>,
        onStatusSnapshot: (AgentStatusSnapshot) -> Unit = {},
    ): Handle = attachLock(task.id).withLock {
        withContext(Dispatchers.IO) {
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
            }
            installStatusSignals(task.agent, cwd, artifactDir)
            // Drop leftover turn artifacts so a resumed run does not immediately re-publish
            // stale Done / re-open a answered question card.
            File(artifactDir, "status.json").delete()
            File(artifactDir, "question.json").delete()

            val launchEnv = env + mapOf(
                AndyStatusHookInstaller.TASK_ID_ENV to task.id,
                AndyStatusHookInstaller.PROJECT_ROOT_ENV to cwdPath,
            )

            val resolvedMode = resolveMode()
            val session = when (resolvedMode) {
                AgentTerminalMode.TmuxHeadless -> {
                    TerminalSessions.create(
                        TerminalLaunchRequest(
                            sessionId = task.id,
                            argv = argv,
                            cwd = cwdPath,
                            env = launchEnv,
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
                            env = launchEnv,
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
                            env = launchEnv,
                            appearance = terminalAppearance(),
                            mode = TerminalMode.DirectPty,
                            agentCli = true,
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

            val artifacts = AgentWorkflowArtifacts(
                scope = scope,
                taskId = task.id,
                root = artifactDir,
                pollIntervalMs = artifactPollIntervalMs,
            )
            val foreground = AtomicBoolean(true)
            bindSessionForeground(session, foreground)
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = task.id,
                agent = task.agent,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = onStatusSnapshot,
                foreground = foreground,
            )
            artifacts.start()
            tracker.start()
            val scrollbackPath = scrollbackFile(task.id)
            val handle = Handle(
                taskId = task.id,
                session = session,
                widget = widget,
                artifacts = artifacts,
                statusTracker = tracker,
                artifactDir = artifactDir,
                scrollbackPath = scrollbackPath,
                scrollback = seedScrollback(scrollbackPath, newRun = true),
                foreground = foreground,
            )
            handles[task.id] = handle
            ownedTaskIds += task.id
            // Synchronous so a caller awaiting start() sees accurate interactivity right away;
            // StateFlow conflates the identical value the Main-dispatched bump below recomputes.
            _interactiveTaskIds.value = ownedTaskIds.filterTo(mutableSetOf()) { id -> isAlive(id) }
            handle.scrollbackJob = scope.launch(Dispatchers.IO) {
                while (isActive && handles[task.id] === handle) {
                    persistScrollback(handle)
                    delay(scrollbackFlushDelay(handle))
                }
            }
            // Publish once on Main after the EDT widget exists so Compose collectors see it
            // without a second IO-thread bump (that forced an extra terminal recomposition).
            scope.launch(Dispatchers.Main.immediate) {
                bumpSessionsRevision()
            }
            handle.waitJob = scope.launch {
                tracker.status.collect { snapshot ->
                    onStatusSnapshot(snapshot)
                }
            }
            onStatusSnapshot(AgentStatusSnapshot(AgentStatus.Working, confident = false))
            handle
        }
    }

    /**
     * Attach a KetraTerm viewer to an existing tmux session (GUI reattach after restart,
     * or remount after [releaseViewerOnly] when switching chat windows).
     * No-op if already attached or session missing.
     *
     * @param cwd project/scratch directory that owns `.andy/<taskId>/` (hooks + status.json).
     *   Must match the directory used when the session was started; scratch is only a fallback.
     * @param preferredStatus optional badge from the task model so a Done chat is not briefly
     *   seeded as Working while the new viewer's first scrape catches a half-drawn idle screen.
     */
    suspend fun attachExisting(
        taskId: String,
        agent: AgentKind = AgentKind.ClaudeCode,
        cwd: String? = null,
        preferredStatus: AgentStatusSnapshot? = null,
        onStatusSnapshot: (AgentStatusSnapshot) -> Unit = {},
    ): Handle? = withContext(Dispatchers.IO) {
        if (!TmuxAndy.isAvailable()) return@withContext null

        // Serialized per chat with [start]: overlapping callers that both got past the
        // "already attached?" check would each spawn a tmux client and a KetraTerm
        // emulator, with only the second reachable through [handles].
        //
        // waitForSession must run *outside* the lock — [start] holds the same mutex while
        // creating the session, so waiting inside would deadlock a UI attach that raced
        // ahead of start.
        attachLock(taskId).withLock {
            liveOrReattachHandle(taskId)
        }?.let { return@withContext it }

        if (!TmuxAndy.hasSession(taskId) && !TmuxAndy.waitForSession(taskId)) {
            return@withContext null
        }

        attachLock(taskId).withLock {
            liveOrReattachHandle(taskId)?.let { return@withLock it }
            // Broken panes (deleted cwd / uv_cwd) are not attachable — kill so the
            // caller can relaunch into a resolved scratch/project directory.
            if (TmuxAndy.sessionLooksBroken(taskId)) {
                TmuxAndy.killSession(taskId)
                return@withLock null
            }
            if (!TmuxAndy.hasSession(taskId)) return@withLock null

            // Capture before clearing a stale handle so the fresh attach can keep
            // status + artifact dir across the viewer rebuild.
            val stale = get(taskId)
            val retainedStatus = stale?.statusTracker?.status?.value
            val retainedArtifactDir = stale?.artifactDir
            if (stale != null) {
                stale.scrollbackJob?.cancel()
                stale.statusTracker.close()
                stale.artifacts.close()
                stale.waitJob?.cancel()
                releaseSessionViewer(stale.session)
                handles.remove(taskId)
            }
            val session = TmuxAttachBackend(
                sessionId = taskId,
                appearance = terminalAppearance(),
                killTmuxOnClose = false,
            )
            // From here the session owns an OS process and emulator threads but is not yet
            // reachable through [handles]. `attach()` is blocking, so a cancellation racing
            // this cannot stop the spawn — it can only strand it. Everything up to
            // registration therefore unwinds through the `finally` below.
            var registered = false
            try {
                session.attach()
                Thread.sleep(200)
                if (!TmuxAndy.hasSession(taskId)) return@withLock null
                val attachSnap = stripAnsi(session.bufferSnapshot().trim())
                if (TmuxAndy.paneContentLooksLikeFailedAttach(attachSnap)) return@withLock null
                val widget = session.swingTerminal()
                    ?: error("terminal widget missing after tmux attach")
                // Prefer the dir the live session already used, then the task cwd, then scratch.
                val artifactDir = retainedArtifactDir
                    ?: AgentWorkflowArtifacts.dirFor(
                        File(AgentScratchWorkspace.resolveCwd(cwd)),
                        taskId,
                    )
                artifactDir.mkdirs()
                val artifacts = AgentWorkflowArtifacts(
                    scope = scope,
                    taskId = taskId,
                    root = artifactDir,
                    pollIntervalMs = artifactPollIntervalMs,
                )
                val foreground = AtomicBoolean(true)
                bindSessionForeground(session, foreground)
                val seededStatus = listOfNotNull(retainedStatus, preferredStatus)
                    .firstOrNull { it.confident || it.status != AgentStatus.Working }
                val tracker = AgentStatusTracker(
                    scope = scope,
                    taskId = taskId,
                    agent = agent,
                    artifactDir = artifactDir,
                    session = session,
                    onSnapshot = onStatusSnapshot,
                    initialSnapshot = seededStatus,
                    foreground = foreground,
                )
                artifacts.start()
                tracker.start()
                val scrollbackPath = scrollbackFile(taskId)
                val handle = Handle(
                    taskId = taskId,
                    session = session,
                    widget = widget,
                    artifacts = artifacts,
                    statusTracker = tracker,
                    artifactDir = artifactDir,
                    scrollbackPath = scrollbackPath,
                    scrollback = seedScrollback(scrollbackPath, newRun = false),
                    foreground = foreground,
                )
                handles[taskId] = handle
                registered = true
                ownedTaskIds += taskId
                handle.scrollbackJob = scope.launch(Dispatchers.IO) {
                    while (isActive && handles[taskId] === handle) {
                        persistScrollback(handle)
                        delay(scrollbackFlushDelay(handle))
                    }
                }
                handle.waitJob = scope.launch {
                    tracker.status.collect { snapshot -> onStatusSnapshot(snapshot) }
                }
                bumpSessionsRevision()
                handle
            } finally {
                if (!registered) {
                    // Cancelled, or the widget never materialised. Drop the viewer and its
                    // threads; the tmux session keeps running so the agent is unaffected.
                    runCatching { session.abandonLocalResources() }
                }
            }
        }
    }

    /**
     * Under [attachLock]: return a live viewer or reattach an existing [TmuxAttachBackend].
     * Stale handles are left for the caller to clear (so retained status/artifact dir
     * can be captured first). Caller must hold the per-task attach lock.
     */
    private fun liveOrReattachHandle(taskId: String): Handle? {
        val existing = get(taskId) ?: return null
        if (isViewerAlive(taskId)) return existing
        val session = existing.session
        if (session is TmuxAttachBackend && TmuxAndy.hasSession(taskId)) {
            existing.foreground.set(true)
            session.reattachViewer(terminalAppearance())
            resumeBackgroundPolling(existing)
            // Single Main publish — a second IO-thread bump forced an extra SwingPanel
            // recomposition for every chat that collected sessionsRevision.
            scope.launch(Dispatchers.Main.immediate) { bumpSessionsRevision() }
            return existing
        }
        return null
    }

    /**
     * Latest visible terminal buffer, for prompt-readiness checks.
     *
     * Polled every 150ms while a new chat waits for its first prompt, so it must not fork
     * per call: a live backend answers from its own emulator, and only a chat with no
     * handle at all (detached tmux session) goes to tmux. A handle that returns blank has
     * already consulted tmux itself, so there is nothing to gain by asking again here.
     */
    fun bufferSnapshot(taskId: String): String {
        val handle = handles[taskId]
        if (handle != null) return handle.session.bufferSnapshot()
        // No has-session precheck: capture-pane already returns empty for a dead session.
        if (TmuxAndy.isAvailable()) {
            return TmuxAndy.capturePane(taskId, historyLines = 80).trimEnd()
        }
        return ""
    }

    fun liveSessionStatus(taskId: String): AgentStatus? =
        handles[taskId]?.statusTracker?.status?.value?.status

    /**
     * Blocks until the agent turn is finished. For tmux-backed sessions the pane may
     * keep a shell alive after the CLI exits, so completion is inferred from scrape
     * rather than session death.
     *
     * Important: interactive TUIs emit confident [AgentStatus.Blocked] for permissions
     * and confident [AgentStatus.Done] for the boot splash / idle prompt. Neither is
     * process exit. We only treat Done/Error as turn-complete after the turn has been
     * "armed" by a real blocker or visible working chrome — never by Blocked itself.
     */
    suspend fun awaitExit(taskId: String): Int {
        val handle = handles[taskId]
        if (handle != null) {
            return when (handle.session) {
                is TmuxAttachBackend, is TmuxAgentBackend -> {
                    var armed = false
                    val snapshot = handle.statusTracker.status.first { snap ->
                        when (snap.status) {
                            AgentStatus.Blocked -> {
                                armed = true
                                false
                            }
                            AgentStatus.Working -> {
                                if (snap.confident || handle.statusTracker.showsWorkingIndicator()) {
                                    armed = true
                                }
                                false
                            }
                            AgentStatus.Error -> snap.confident
                            AgentStatus.Done -> snap.confident && armed
                        }
                    }
                    when (snapshot.status) {
                        AgentStatus.Error -> handle.session.exitCode.value ?: 1
                        else -> 0
                    }
                }
                else -> {
                    val code = awaitDirectPtyExit(handle)
                    withContext(Dispatchers.IO) {
                        // Trailing Direct PTY bytes can land after exit. Persist once, then
                        // give a short grace window — not a multi-second stall on empty stubs.
                        persistScrollback(handle)
                        if (handle.scrollbackPath.isFile && handle.scrollbackPath.length() > 0L) {
                            return@withContext
                        }
                        repeat(DIRECT_PTY_SCROLLBACK_GRACE_ATTEMPTS) {
                            delay(DIRECT_PTY_SCROLLBACK_GRACE_MS)
                            persistScrollback(handle)
                            if (handle.scrollbackPath.isFile && handle.scrollbackPath.length() > 0L) {
                                return@withContext
                            }
                        }
                    }
                    code
                }
            }
        }
        // Headless wait when only tmux remains.
        if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            return withContext(Dispatchers.IO) { TmuxAndy.waitExit(taskId) }
        }
        return UNKNOWN_EXIT_CODE
    }

    /**
     * Wait for a Direct PTY's exit code — indefinitely while the process is alive, since an
     * agent turn has no time bound, but never indefinitely once it is gone.
     *
     * The old `exitCode.first { it != null }` had no floor: any path that fails to report a
     * code parks the caller forever, and with it the concurrency permit its run holds in
     * [DesktopAgentRunService], so the workflow stage stalls with nothing in the log. A dead
     * session that has still not reported after [EXIT_CODE_GRACE_MS] now resolves as unknown,
     * turning a silent hang into a visible failure.
     */
    private suspend fun awaitDirectPtyExit(handle: Handle): Int {
        var deadSinceMillis = 0L
        while (true) {
            withTimeoutOrNull(EXIT_CODE_POLL_MS) {
                handle.session.exitCode.first { it != null }
            }?.let { return it }
            if (handle.session.isAlive) {
                deadSinceMillis = 0L
                continue
            }
            val now = System.currentTimeMillis()
            when {
                deadSinceMillis == 0L -> deadSinceMillis = now
                now - deadSinceMillis >= EXIT_CODE_GRACE_MS -> return UNKNOWN_EXIT_CODE
            }
        }
    }

    fun stop(taskId: String) {
        ownedTaskIds -= taskId
        val handle = handles.remove(taskId)
        if (handle != null) {
            handle.stopRequested = true
            handle.scrollbackJob?.cancel()
            runCatching { persistScrollback(handle) }
            handle.statusTracker.close()
            handle.artifacts.close()
            handle.waitJob?.cancel()
            runCatching { handle.session.close() }
            // Direct PTY output can land in the buffer just after process exit.
            runCatching { persistScrollback(handle) }
        }
        // stop() always means terminate, unlike session.close() which a reattached
        // TmuxAttachBackend honors with killTmuxOnClose = false. Force it here so a
        // chat that was reattached after a dropped handle doesn't leak its tmux session.
        if (TmuxAndy.isAvailable() && TmuxAndy.hasSession(taskId)) {
            TmuxAndy.killSession(taskId)
        }
        bumpSessionsRevision()
    }

    /**
     * Tear down Andy's local tracker/viewer for [taskId] but leave a live tmux
     * session running so the user can attach or send follow-ups.
     */
    fun detach(taskId: String) {
        val handle = handles.remove(taskId) ?: return
        handle.stopRequested = true
        handle.scrollbackJob?.cancel()
        runCatching { persistScrollback(handle) }
        handle.statusTracker.close()
        handle.artifacts.close()
        handle.waitJob?.cancel()
        when (val session = handle.session) {
            is TmuxAgentBackend -> {
                session.setKillOnClose(false)
                runCatching { session.close() }
            }
            else -> releaseSessionViewer(session)
        }
        bumpSessionsRevision()
    }

    /**
     * Drop Andy's local KetraTerm viewer without tearing down a detached tmux session.
     * [TmuxAttachBackend.close] honors [killTmuxOnClose] and must not be used here.
     */
    private fun releaseSessionViewer(session: TerminalSession) {
        when (session) {
            is TmuxAttachBackend -> runCatching { session.abandonLocalResources() }
            else -> runCatching { session.close() }
        }
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
                    "Re-run install-andy.sh or set ANDY_TMUX.",
            )
            }
            // GUI soft-fallback so local dev still works without tmux.
            return AgentTerminalMode.DirectPty
        }
        return mode
    }

    private fun pauseBackgroundPolling(handle: Handle) {
        handle.statusTracker.pause()
        handle.artifacts.pause()
        handle.scrollbackJob?.cancel()
        handle.scrollbackJob = null
    }

    private fun resumeBackgroundPolling(handle: Handle) {
        handle.statusTracker.resume()
        handle.artifacts.resume()
        if (handle.scrollbackJob?.isActive == true) return
        val taskId = handle.taskId
        handle.scrollbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && handles[taskId] === handle) {
                persistScrollback(handle)
                delay(scrollbackFlushDelay(handle))
            }
        }
    }

    /**
     * Snapshot the terminal and fold it into this run's transcript.
     *
     * An attached viewer is the capture source: with `status off` on the Andy tmux server
     * its screen is the pane and nothing else, so it carries the same styled rows
     * `capture-pane -e` returns without forking one every flush. tmux still supplies the
     * first capture after an attach ([TmuxAttachBackend.consumeHistoryBridge]) — a new
     * viewer has no scrollback for output produced while Andy was detached — and every
     * capture for a chat with no viewer at all.
     */
    private fun persistScrollback(handle: Handle) {
        val captureRows = if (handle.foreground.get()) {
            KetraTermBackend.SCROLLBACK_CAPTURE_ROWS
        } else {
            KetraTermBackend.SCROLLBACK_BACKGROUND_CAPTURE_ROWS
        }
        val snapshot = when (val session = handle.session) {
            is TmuxAttachBackend ->
                if (session.isViewerAlive && !session.consumeHistoryBridge()) {
                    session.captureStyledRows(captureRows).ifEmpty {
                        captureTmuxRows(handle.taskId, captureRows)
                    }
                } else {
                    captureTmuxRows(handle.taskId, captureRows).ifEmpty {
                        session.captureStyledRows(captureRows)
                    }
                }
            is KetraTermBackend ->
                session.captureStyledRows(captureRows).ifEmpty {
                    styledRowsFromAnsiText(resolveScrollbackForReplay(session.scrollbackAnsi()))
                }
            else -> captureTmuxRows(handle.taskId, captureRows)
        }
        if (snapshot.isNotEmpty()) handle.scrollback.merge(snapshot)
        val export = handle.scrollback.render()
        if (export.isBlank()) return
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(export))) return
        atomicWriteText(handle.scrollbackPath, capScrollbackSize(export))
    }

    private fun captureTmuxRows(taskId: String, historyLines: Int): List<StyledTerminalRow> {
        // capture-pane's exit code covers the dead-session case, so skip the extra has-session fork.
        if (!TmuxAndy.isAvailable()) return emptyList()
        val pane = TmuxAndy.capturePane(
            taskId,
            historyLines = historyLines,
            escapes = true,
        )
        if (pane.isBlank() || TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(pane))) return emptyList()
        return styledRowsFromAnsiText(pane)
    }

    private fun bindSessionForeground(session: TerminalSession, foreground: AtomicBoolean) {
        when (session) {
            // Reaches the inner viewer too: TmuxAttachBackend hands its own flag down to
            // whichever KetraTermBackend is currently attached.
            is TmuxAttachBackend -> session.foreground = foreground
            is KetraTermBackend -> session.foregroundProvider = { foreground.get() }
            else -> Unit
        }
    }

    private fun scrollbackFlushDelay(handle: Handle): Long =
        if (handle.foreground.get()) SCROLLBACK_FLUSH_MILLIS else SCROLLBACK_BACKGROUND_MILLIS

    /**
     * Start this chat's transcript from what is already on disk, keeping earlier runs
     * and their styling. Legacy raw PTY tees are collapsed to text first — replaying
     * their cursor motion would scribble over everything that follows.
     *
     * [newRun] marks a freshly spawned CLI, whose output belongs after a session rule.
     * A reattach instead re-captures output the file already holds, so it is seeded
     * without a rule and left for the snapshot merge to recognise.
     */
    private fun seedScrollback(file: File, newRun: Boolean): ScrollbackAccumulator {
        val accumulator = ScrollbackAccumulator()
        val existing = runCatching {
            if (file.isFile && file.length() > 0L) file.readText() else ""
        }.getOrDefault("")
        if (existing.isBlank()) return accumulator
        val committed = if (looksLikeRawAnsiTee(existing)) {
            formatScrollbackForDisplay(stripAnsi(resolveScrollbackForReplay(existing)))
        } else {
            existing
        }.trimEnd()
        if (committed.isBlank()) return accumulator
        // A fresh CLI run keeps the resolved transcript on disk and marks the boundary
        // with a session rule; reattach seeds the same file without adding a rule.
        val seed = if (newRun) committed + SCROLLBACK_SESSION_SEPARATOR else committed
        accumulator.seed(styledRowsFromAnsiText(seed.trimEnd()))
        return accumulator
    }

    companion object {
        private const val SCROLLBACK_FLUSH_MILLIS = 2_000L
        private const val SCROLLBACK_BACKGROUND_MILLIS = 15_000L
        /** Max wait after DirectPty exit for trailing buffer bytes when scrollback is still empty. */
        private const val DIRECT_PTY_SCROLLBACK_GRACE_ATTEMPTS = 5
        private const val DIRECT_PTY_SCROLLBACK_GRACE_MS = 20L

        /** Re-check cadence for a Direct PTY that has not published an exit code yet. */
        private const val EXIT_CODE_POLL_MS = 100L

        /**
         * How long a dead Direct PTY may go without publishing an exit code before
         * [awaitDirectPtyExit] gives up. Publishing needs [KetraTermBackend]'s own wait
         * coroutine — parked in a blocking `pty.waitFor()` on its own internal scope,
         * independent of this process's dispatcher — to actually get scheduled and observe
         * the reap. 2s proved too tight under real scheduler/GC jitter (a shared CI runner,
         * or just a busy dev machine): the exit code lands a beat late, [isAlive] has already
         * flipped false, and the turn is misreported as [AgentStatus.Error] with an unknown
         * exit code instead of the [AgentStatus.Done] it actually reached.
         */
        private const val EXIT_CODE_GRACE_MS = 8_000L

        /** Returned when a session ends without ever reporting a status. */
        const val UNKNOWN_EXIT_CODE = KetraTermBackend.CLOSED_EXIT_CODE
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
