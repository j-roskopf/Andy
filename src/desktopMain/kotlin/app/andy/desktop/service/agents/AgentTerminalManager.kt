package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.SCROLLBACK_SESSION_SEPARATOR
import app.andy.terminal.combineCommittedAndDerivedScrollback
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalMode
import app.andy.terminal.TerminalSession
import app.andy.terminal.TerminalSessions
import app.andy.terminal.TmuxAndy
import app.andy.terminal.TmuxAgentBackend
import app.andy.terminal.TmuxAttachBackend
import app.andy.terminal.RawScrollbackFile
import app.andy.terminal.ScrollbackAnsiCursor
import app.andy.terminal.ScrollbackAnsiSnapshot
import app.andy.terminal.ScrollbackAccumulator
import app.andy.terminal.ScrollbackGridSize
import app.andy.terminal.ScrollbackReplayCapture
import app.andy.terminal.inferScrollbackGridSize
import app.andy.terminal.replayCaptureStyledRows
import app.andy.terminal.StyledTerminalRow
import app.andy.terminal.atomicWriteText
import app.andy.terminal.capScrollbackSize
import app.andy.terminal.collapseRepeatedScrollbackLines
import app.andy.terminal.compactRepeatedProviderStartupText
import app.andy.terminal.formatLegacyScrollbackForReplay
import app.andy.terminal.formatScrollbackForDisplay
import app.andy.terminal.isScrollbackDisplayNoise
import app.andy.terminal.looksLikeBrokenPlainScrollback
import app.andy.terminal.looksLikeRawAnsiTee
import app.andy.terminal.resolveScrollbackForReplay
import app.andy.terminal.rust.RustScrollbackReplay
import app.andy.terminal.rust.RustTerminalBackend
import app.andy.terminal.scrollbackReplayColumns
import app.andy.terminal.stripAnsi
import app.andy.terminal.styledRowsFromAnsiText
import app.andy.terminal.trimLegacyTmuxCopyModeOutput
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How agent CLIs are hosted.
 *
 * - [TmuxWithAttach]: create `tmux -L andy` session + BossTerm attach (GUI default)
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
 * attaches via BossTerm for rendering.
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
        val artifacts: AgentWorkflowArtifacts,
        val statusTracker: AgentStatusTracker,
        val artifactDir: File,
        val scrollbackPath: File,
        val scrollback: ScrollbackAccumulator,
        /** Append-only raw PTY mirror; the transcript is derived from it on demand. */
        val rawScrollback: RawScrollbackFile,
        val foreground: AtomicBoolean = AtomicBoolean(true),
        @Volatile var stopRequested: Boolean = false,
        @Volatile var waitJob: Job? = null,
        @Volatile var scrollbackJob: Job? = null,
        /** Retains terminal state between flushes so a raw PTY tee is replayed only once. */
        var scrollbackReplay: ScrollbackReplayCapture? = null,
        /** On-demand raw replay retained so later history peeks process only the new suffix. */
        var rawHistoryReplay: ScrollbackReplayCapture? = null,
        /** Last raw position folded into `scrollback.ansi`; avoids repeated final derivation. */
        var committedRawCursor: ScrollbackAnsiCursor? = null,
        /** Serializes scrollback writes: the timer loop and end-of-session callers can race. */
        val scrollbackLock: Any = Any(),
    )

    private val handles = ConcurrentHashMap<String, Handle>()

    /** One derivation of `scrollback.raw`, valid while the file is untouched. */
    private data class DerivedRawTranscript(
        val size: Long,
        val modified: Long,
        val text: String,
        val columns: Int,
        val rows: Int,
    ) {
        /** Cheap hit: size + mtime only — do not read or scan the raw file. */
        fun matchesFile(file: File): Boolean =
            size == file.length() && modified == file.lastModified()

        fun matches(file: File, grid: ScrollbackGridSize): Boolean =
            matchesFile(file) &&
                columns == grid.columns &&
                rows == grid.rows
    }

    private data class DerivedRawRows(
        val size: Long,
        val modified: Long,
        val rows: List<StyledTerminalRow>,
    )

    /** Memoizes [derivedRawReplayText] so reopening unchanged history costs nothing. */
    private val derivedRawCache = ConcurrentHashMap<String, DerivedRawTranscript>()

    /** One-shot repair of legacy plain/duplicated `.ansi` from `.raw`. */
    private data class RepairedAnsiTranscript(
        val rawSize: Long,
        val rawModified: Long,
        val ansiSize: Long,
        val ansiModified: Long,
        val text: String,
    ) {
        fun matches(raw: File): Boolean =
            rawSize == raw.length() && rawModified == raw.lastModified()

        fun matchesAnsi(ansi: File): Boolean =
            ansi.isFile && ansiSize == ansi.length() && ansiModified == ansi.lastModified()
    }

    private val repairedAnsiCache = ConcurrentHashMap<String, RepairedAnsiTranscript>()

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
     * [terminalView] — the view is created asynchronously after createAndStart returns.
     */
    private val _sessionsRevision = MutableStateFlow(0L)
    val sessionsRevision: StateFlow<Long> = _sessionsRevision.asStateFlow()

    private val _attachedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Task ids that currently have an attachable terminal view. */
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

    fun rustTerminal(taskId: String): RustTerminalBackend? {
        val handle = handles[taskId] ?: return null
        return when (val session = handle.session) {
            is RustTerminalBackend -> session
            is TmuxAttachBackend -> session.rustTerminal()?.takeIf { session.isViewerAlive }
            else -> null
        }
    }

    /**
     * Drop the local BossTerm viewer while keeping a live tmux session (or DirectPty
     * process) running. Called when the Compose surface unmounts so the next open can
     * [attachExisting] instead of reusing a disposed terminal view.
     */
    fun releaseViewerOnly(taskId: String) {
        val handle = handles[taskId] ?: return
        handle.foreground.set(false)
        when (handle.session) {
            is TmuxAttachBackend -> runCatching { handle.session.releaseViewer() }
            else -> Unit
        }
        if (!isAlive(taskId)) {
            pauseBackgroundPolling(handle)
        }
        bumpSessionsRevision()
    }

    /** Mark [taskId] as the only chat receiving foreground scrape cadence. */
    fun setOnlyForeground(taskId: String) {
        handles.forEach { (id, handle) ->
            if (id == taskId) {
                handle.foreground.set(true)
                if (isViewerAlive(id)) {
                    resumeBackgroundPolling(handle)
                }
            } else if (isViewerAlive(id) && handle.session is TmuxAttachBackend) {
                releaseViewerOnly(id)
            } else {
                handle.foreground.set(false)
                if (!isAlive(id)) {
                    pauseBackgroundPolling(handle)
                }
            }
        }
    }

    fun setForeground(taskId: String, foreground: Boolean) {
        handles[taskId]?.let { handle ->
            if (foreground) {
                handle.foreground.set(true)
                if (isViewerAlive(taskId)) {
                    resumeBackgroundPolling(handle)
                }
            } else if (isViewerAlive(taskId) && handle.session is TmuxAttachBackend) {
                releaseViewerOnly(taskId)
            } else {
                handle.foreground.set(false)
                if (!isAlive(taskId)) {
                    pauseBackgroundPolling(handle)
                }
            }
        }
    }

    fun clearForeground() {
        handles.forEach { (id, handle) ->
            if (isViewerAlive(id) && handle.session is TmuxAttachBackend) {
                releaseViewerOnly(id)
            } else {
                handle.foreground.set(false)
                if (!isAlive(id)) {
                    pauseBackgroundPolling(handle)
                }
            }
        }
    }

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

    /** Raw PTY mirror, sibling of `scrollback.ansi`. See [RawScrollbackFile]. */
    private fun rawScrollbackFile(taskId: String): File =
        File(scrollbackFile(taskId).parentFile, RAW_SCROLLBACK_NAME)

    /**
     * Whether any history exists, without deriving it.
     *
     * Compose asks this while rendering, so it must not read and reprocess the transcript —
     * the derivation is the expensive half of this pipeline. Presence of either file is
     * enough; [scrollbackReplayText] decides what is actually renderable.
     */
    fun hasScrollback(taskId: String): Boolean =
        listOf(scrollbackFile(taskId), rawScrollbackFile(taskId)).any { it.isFile && it.length() > 0L }

    /**
     * Saved history exactly as it should be re-rendered: SGR styling, indentation and
     * box drawing preserved.
     *
     * Two older formats need repair first. A raw PTY tee would replay its cursor motion
     * as overlapping garbage, and unstyled scrapes carry tmux status bars and half-drawn
     * rows that no amount of replay can turn back into a terminal.
     */
    fun scrollbackReplayText(taskId: String): String? {
        // A wheel-up history peek runs on Dispatchers.IO. Flush here so it includes bytes
        // received since the timer's last tick; the append is delta-only and normally tiny.
        handles[taskId]?.let { handle -> runCatching { flushScrollback(handle) } }
        derivedRawReplayText(taskId)?.let { return it }

        val file = scrollbackFile(taskId)
        if (!file.isFile || file.length() == 0L) return null
        val content = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(content))) return null

        // BossTerm migration left some chats with plain, heavily duplicated .ansi while
        // scrollback.raw still has the true stream. Repair once, then serve the file.
        repairBrokenAnsiFromRawIfNeeded(taskId, content)?.let { return it }

        val replay = when {
            looksLikeRawAnsiTee(content) -> cleanedScrollbackText(content)
            content.contains('\u001B') -> content.trimEnd().takeIf { it.isNotBlank() }
            else -> formatLegacyScrollbackForReplay(content).takeIf { it.isNotBlank() }
        }
        return replay
            ?.let(::compactRepeatedProviderStartupText)
            ?.let(::collapseRepeatedScrollbackLines)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * One-shot rewrite of a broken plain/duplicated `scrollback.ansi` from its raw tee.
     * Returns the repaired transcript when repair ran; null when the file is already fine
     * or no raw source exists.
     */
    private fun repairBrokenAnsiFromRawIfNeeded(taskId: String, ansiContent: String): String? {
        if (!looksLikeBrokenPlainScrollback(ansiContent)) return null
        val raw = rawScrollbackFile(taskId)
        if (!raw.isFile || raw.length() == 0L) {
            return compactRepeatedProviderStartupText(ansiContent)
                .let(::collapseRepeatedScrollbackLines)
                .takeIf { it.isNotBlank() }
        }
        repairedAnsiCache[taskId]?.let { cached ->
            if (cached.matches(raw) && cached.matchesAnsi(scrollbackFile(taskId))) return cached.text
        }
        val rawContent = runCatching { raw.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val derived = runCatching { replayCaptureStyledRows(rawContent) }.getOrNull()
            ?.joinToString("\n") { it.ansi }
            ?.trimEnd()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val repaired = compactRepeatedProviderStartupText(derived)
            .let(::collapseRepeatedScrollbackLines)
            .takeIf { it.isNotBlank() }
            ?: return null
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(repaired))) return null
        val ansiFile = scrollbackFile(taskId)
        atomicWriteText(ansiFile, capScrollbackSize(repaired))
        derivedRawCache.remove(taskId)
        repairedAnsiCache[taskId] = RepairedAnsiTranscript(
            rawSize = raw.length(),
            rawModified = raw.lastModified(),
            ansiSize = ansiFile.length(),
            ansiModified = ansiFile.lastModified(),
            text = repaired,
        )
        return repaired
    }

    /**
     * Transcript reconstructed from `scrollback.raw`, for a run that has not been committed
     * to `scrollback.ansi` yet.
     *
     * This is where the expensive work now lives: replaying the raw tee through an emulator
     * and stitching its repaints. It runs when a viewer actually opens history — not on a
     * 2-second timer — and the result is cached against the raw file's size and mtime so
     * reopening the same unchanged history is free.
     *
     * Returns null once `.ansi` is at least as fresh as the raw bytes, which is the state
     * [finalizeScrollback] leaves behind at end of session.
     */
    private fun derivedRawReplayText(taskId: String): String? {
        val raw = rawScrollbackFile(taskId)
        if (!raw.isFile || raw.length() == 0L) return null
        val committedFile = scrollbackFile(taskId)
        val handle = handles[taskId]
        val committedFresh = if (handle != null) {
            synchronized(handle.scrollbackLock) {
                if (!handle.rawScrollback.wroteAnything) {
                    committedFile.isFile && committedFile.lastModified() >= raw.lastModified()
                } else {
                    handle.rawScrollback.cursor()?.let { it == handle.committedRawCursor } == true
                }
            }
        } else {
            committedFile.isFile && committedFile.lastModified() >= raw.lastModified()
        }
        if (committedFresh) return null

        // Hit the cache before reading/scanning multi‑MB raw tees. A warm peek used to
        // re-read the whole file and run CUP/grid inference just to confirm the cache key.
        derivedRawCache[taskId]?.let { cached ->
            if (cached.matchesFile(raw)) return cached.text
        }

        val content = runCatching { raw.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val replayableContent = trimLegacyTmuxCopyModeOutput(content)
        val grid = inferScrollbackGridSize(replayableContent)
        val rawRows = deriveRawRows(raw, handle, replayableContent) ?: return null
        val derived = rawRows.rows.joinToString("\n") { it.ansi }
            .trimEnd()
            .takeIf { it.isNotBlank() }
            ?: return null

        // `.ansi` may hold prior finished runs *or* a partial mid-run commit (history
        // bridge). Overlap-merge collapses the same-run prefix; true prior sessions append.
        val committed = runCatching {
            if (committedFile.isFile) committedFile.readText().trimEnd() else ""
        }.getOrDefault("")
        val combined = combineCommittedAndDerivedScrollback(committed, derived)
        val replay = compactRepeatedProviderStartupText(combined).takeIf { it.isNotBlank() } ?: return null
        // A historical task has no live writer. Publish the compatibility repair once so
        // later opens use the compact committed transcript instead of replaying legacy raw.
        if (handle == null && !TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(replay))) {
            atomicWriteText(committedFile, capScrollbackSize(replay))
        }
        derivedRawCache[taskId] = DerivedRawTranscript(
            size = rawRows.size,
            modified = rawRows.modified,
            text = replay,
            columns = grid.columns,
            rows = grid.rows,
        )
        return replay
    }

    /**
     * Derive the active raw file under the same lock used by its appender.
     *
     * The first history peek reconstructs the retained file. Later peeks hand the same
     * growing snapshot to [ScrollbackReplayCapture], which starts at its previous character
     * offset and emulates only newly appended output. No replay session is retained for a
     * finished chat with no live handle.
     */
    private fun deriveRawRows(raw: File, handle: Handle?, content: String): DerivedRawRows? {
        fun derive(): DerivedRawRows? {
            val rows = runCatching {
                if (handle == null) {
                    replayCaptureStyledRows(content)
                } else {
                    replayRawHistory(handle, content)
                }
            }.getOrNull() ?: return null
            return DerivedRawRows(
                size = raw.length(),
                modified = raw.lastModified(),
                rows = rows,
            )
        }
        return if (handle == null) derive() else synchronized(handle.scrollbackLock) { derive() }
    }

    private fun replayRawHistory(handle: Handle, content: String): List<StyledTerminalRow> {
        val grid = inferScrollbackGridSize(content)
        var replay = handle.rawHistoryReplay
        val existing = replay?.gridSize()
        if (
            replay == null ||
            existing == null ||
            existing.columns < grid.columns ||
            existing.rows < grid.rows
        ) {
            // Grid grew (or first peek): rebuild so a prior 120x32 capture cannot keep
            // duplicating a taller live TUI for the rest of the session.
            runCatching { replay?.close() }
            replay = ScrollbackReplayCapture(cols = grid.columns, rows = grid.rows).also {
                handle.rawHistoryReplay = it
            }
        }
        return replay.capture(
            ScrollbackAnsiSnapshot(
                content = content,
                startOffset = 0L,
                endOffset = content.length.toLong(),
                epoch = RAW_FILE_REPLAY_EPOCH,
            ),
        )
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
     * Build a read-only BossTerm view that replays [scrollback.ansi] for viewing
     * finished chats. Caller owns dispose. Returns null when no history is available.
     */
    fun openScrollbackReplay(taskId: String): RustScrollbackReplay? {
        val text = scrollbackReplayText(taskId) ?: return null
        return RustScrollbackReplay.create(
            content = text,
            cols = scrollbackReplayColumns(text),
            appearance = terminalAppearance(),
        )
    }

    /** Push latest Settings appearance into live sessions. */
    fun reloadAppearance() {
        val appearance = terminalAppearance()
        handles.values.forEach { handle ->
            when (val session = handle.session) {
                is RustTerminalBackend -> session.updateAppearance(appearance)
                is TmuxAttachBackend -> session.updateAppearance(appearance)
            }
        }
    }

    private fun bumpSessionsRevision() {
        _sessionsRevision.value = _sessionsRevision.value + 1
        _attachedTaskIds.value = handles.keys.filterTo(mutableSetOf()) { id ->
            rustTerminal(id) != null
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
        /**
         * True for a view-only reattach with no new turn (no argv prompt, nothing queued
         * to type). Such a launch never calls [AgentStatusTracker.markUserWorking] to arm
         * the tracker, so premature-idle suppression — meant to ride out a fresh launch's
         * boot splash before its first turn starts — would otherwise withhold Done forever
         * once the CLI settles back at its resumed, genuinely idle prompt.
         */
        quietResume: Boolean = false,
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
            // Seed the PTY at the last size any terminal in this app run actually
            // negotiated (see RustTerminalBackend.lastKnownGridSize) rather than a fixed
            // guess — a CLI that dumps its whole history the instant it starts (a quiet
            // resume) never gets a chance to redraw at the correct size otherwise.
            val (seedCols, seedRows) = RustTerminalBackend.lastKnownGridSize(agentCli = true)
            val session = when (resolvedMode) {
                AgentTerminalMode.TmuxHeadless -> {
                    TerminalSessions.create(
                        TerminalLaunchRequest(
                            sessionId = task.id,
                            argv = argv,
                            cwd = cwdPath,
                            env = launchEnv,
                            cols = seedCols,
                            rows = seedRows,
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
                            cols = seedCols,
                            rows = seedRows,
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
                            cols = seedCols,
                            rows = seedRows,
                            appearance = terminalAppearance(),
                            mode = TerminalMode.DirectPty,
                            agentCli = true,
                        ),
                    )
                }
            }

            // Rust DirectPty is ready once start() returns — ultra-fast stubs
            // (`/usr/bin/true`) can already be exited by here, so do not require isAlive.
            val viewReady = when (session) {
                is RustTerminalBackend -> session.pid != null || session.exitCode.value != null
                is TmuxAttachBackend -> session.hasLiveViewer()
                else -> false
            }
            if (resolvedMode == AgentTerminalMode.TmuxWithAttach || resolvedMode == AgentTerminalMode.DirectPty) {
                check(viewReady) {
                    "terminal view missing after start (backend=${session::class.simpleName}, mode=$resolvedMode)"
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
            // View-only reattach: seed Done so collectors never see a fake Working→Done
            // (that transition dings the finish notification when the idle prompt scrapes in).
            val seededStatus = if (quietResume) {
                AgentStatusSnapshot(AgentStatus.Done, confident = true)
            } else {
                null
            }
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = task.id,
                agent = task.agent,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = onStatusSnapshot,
                initialSnapshot = seededStatus,
                foreground = foreground,
                suppressPrematureIdle = !quietResume,
            )
            artifacts.start()
            tracker.start()
            val scrollbackPath = scrollbackFile(task.id)
            derivedRawCache.remove(task.id)
            repairedAnsiCache.remove(task.id)
            // Hard-kill durability: a prior run may exist only in scrollback.raw because
            // finalize never derived scrollback.ansi. Commit it before startNewRun deletes it.
            commitSurvivingRawScrollback(task.id)
            val handle = Handle(
                taskId = task.id,
                session = session,
                artifacts = artifacts,
                statusTracker = tracker,
                artifactDir = artifactDir,
                scrollbackPath = scrollbackPath,
                scrollback = seedScrollback(scrollbackPath, newRun = true),
                rawScrollback = RawScrollbackFile(rawScrollbackFile(task.id)).also { it.startNewRun() },
                foreground = foreground,
            )
            handles[task.id] = handle
            ownedTaskIds += task.id
            // Synchronous so a caller awaiting start() sees accurate interactivity right away;
            // StateFlow conflates the identical value the Main-dispatched bump below recomputes.
            _interactiveTaskIds.value = ownedTaskIds.filterTo(mutableSetOf()) { id -> isAlive(id) }
            handle.scrollbackJob = launchScrollbackJob(handle)
            // Publish once on Main after the terminal view exists so Compose collectors see it
            // without a second IO-thread bump (that forced an extra terminal recomposition).
            scope.launch(Dispatchers.Main.immediate) {
                bumpSessionsRevision()
            }
            handle.waitJob = scope.launch {
                tracker.status.collect { snapshot ->
                    onStatusSnapshot(snapshot)
                }
            }
            // Arm only when THIS launch carries a new user turn (argv prompt). Typing via
            // writeAfterStart/submitText arms separately — do not treat the original Andy
            // task prompt alone as a new turn (quiet --resume / External open).
            // Quiet reattach must not publish Working: the resumed TUI settles at an idle
            // prompt, scrape goes Done, and attention would ding as if the turn just finished.
            when {
                argvHasEmbeddedPrompt(argv) -> tracker.markUserWorking()
                quietResume -> Unit
                else -> onStatusSnapshot(AgentStatusSnapshot(AgentStatus.Working, confident = false))
            }
            handle
        }
    }

    /**
     * Attach a BossTerm viewer to an existing tmux session (GUI reattach after restart,
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
        // "already attached?" check would each spawn a tmux client and a BossTerm
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
                stale.scrollbackReplay?.close()
                stale.rawHistoryReplay?.close()
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
                check(session.hasLiveViewer()) {
                    "terminal view missing after tmux attach"
                }
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
                    artifacts = artifacts,
                    statusTracker = tracker,
                    artifactDir = artifactDir,
                    scrollbackPath = scrollbackPath,
                    scrollback = seedScrollback(scrollbackPath, newRun = false),
                    // Reattach continues the same stream, so no run separator.
                    rawScrollback = RawScrollbackFile(rawScrollbackFile(taskId)),
                    foreground = foreground,
                )
                handles[taskId] = handle
                registered = true
                ownedTaskIds += taskId
                handle.scrollbackJob = launchScrollbackJob(handle)
                handle.waitJob = scope.launch {
                    tracker.status.collect { snapshot -> onStatusSnapshot(snapshot) }
                }
                bumpSessionsRevision()
                handle
            } finally {
                if (!registered) {
                    // Cancelled, or the view never materialised. Drop the viewer and its
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
        if (isViewerAlive(taskId)) {
            existing.foreground.set(true)
            if (existing.session is TmuxAttachBackend) {
                TmuxAndy.exitCopyModeIfActive(taskId)
            }
            return existing
        }
        val session = existing.session
        if (session is TmuxAttachBackend && TmuxAndy.hasSession(taskId)) {
            existing.foreground.set(true)
            session.reattachViewer(terminalAppearance())
            resumeBackgroundPolling(existing)
            // Single Main publish — a second IO-thread bump forced an extra terminal
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
                        finalizeScrollback(handle)
                        if (handle.scrollbackPath.isFile && handle.scrollbackPath.length() > 0L) {
                            return@withContext
                        }
                        repeat(DIRECT_PTY_SCROLLBACK_GRACE_ATTEMPTS) {
                            delay(DIRECT_PTY_SCROLLBACK_GRACE_MS)
                            finalizeScrollback(handle)
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
            finalizeScrollback(handle)
            handle.statusTracker.close()
            handle.artifacts.close()
            handle.waitJob?.cancel()
            runCatching { handle.session.close() }
            // Direct PTY output can land in the buffer just after process exit.
            finalizeScrollback(handle)
            runCatching { handle.scrollbackReplay?.close() }
            runCatching { handle.rawHistoryReplay?.close() }
        }
        // stop() always means terminate, unlike session.close() which a reattached
        // TmuxAttachBackend honors with killTmuxOnClose = false. Force it here so a
        // chat that was reattached after a dropped handle doesn't leak its tmux session.
        // DirectPty never creates tmux sessions — skip the has-session fork (and its
        // up-to-30s wait) so test/harness teardown cannot stall on a wedged tmux.
        if (mode != AgentTerminalMode.DirectPty &&
            TmuxAndy.isAvailable() &&
            TmuxAndy.hasSession(taskId)
        ) {
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
        finalizeScrollback(handle)
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
        runCatching { handle.scrollbackReplay?.close() }
        runCatching { handle.rawHistoryReplay?.close() }
        bumpSessionsRevision()
    }

    /**
     * Drop Andy's local BossTerm viewer without tearing down a detached tmux session.
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
        handle.scrollbackJob = launchScrollbackJob(handle)
    }

    /**
     * Mirror the raw PTY tee to disk on a timer. See [flushScrollback].
     *
     * A live poll of the current screen would lose anything that scrolled past between two
     * ticks — agent TUIs redraw on the alternate screen, which has no native scrollback — so
     * no polling cadence can be trusted against an arbitrarily fast model. Persisting the raw
     * byte stream sidesteps that entirely: it cannot miss output regardless of cadence, and
     * the transcript is reconstructed from it later (see
     * [app.andy.terminal.replayCaptureStyledRows]). The timer therefore governs only how
     * promptly bytes reach disk, not what can be captured.
     */
    private fun launchScrollbackJob(handle: Handle): Job = scope.launch(Dispatchers.IO) {
        while (isActive && handles[handle.taskId] === handle) {
            flushScrollback(handle)
            delay(scrollbackFlushDelay(handle))
        }
    }

    /**
     * The live persistence path: mirror new raw PTY bytes and nothing else.
     *
     * Deriving a transcript from those bytes — replaying them through an emulator and
     * stitching the repaints — used to run here on every tick and measured 74% of the whole
     * process. Nothing consumes the derived form continuously (see [hasScrollback] and
     * [scrollbackReplayText], both reached only when a viewer opens history), so it moved to
     * [finalizeScrollback] and on-demand derivation instead.
     *
     * tmux sessions are intentionally different: their attached-client stream includes
     * copy-mode navigation, so [flushScrollback] captures the pane model instead.
     */
    private fun flushScrollback(handle: Handle) {
        when (val session = handle.session) {
            is TmuxAttachBackend -> {
                val historyLines = if (session.consumeHistoryBridge()) -1 else null
                runCatching { persistScrollback(handle, historyLines) }
                // Legacy builds may have left attached-client bytes behind. Once the pane
                // itself has been captured they are redundant and unsafe to derive.
                handle.rawScrollback.startNewRun()
                handle.committedRawCursor = null
                handle.rawHistoryReplay = null
                derivedRawCache.remove(handle.taskId)
            }
            is TmuxAgentBackend -> runCatching { persistScrollback(handle) }
            else -> if (!appendTeeDelta(handle)) {
            // No raw tee to mirror (headless tmux with no local viewer). Such sessions are
            // captured a bounded screen at a time, which was never the expensive path.
                runCatching { persistScrollback(handle) }
            }
        }
    }

    /**
     * Fold a surviving `scrollback.raw` into `scrollback.ansi` before [RawScrollbackFile.startNewRun]
     * deletes it. Used when [start] resumes a task after a hard kill where finalize never ran.
     */
    private fun commitSurvivingRawScrollback(taskId: String) {
        val raw = rawScrollbackFile(taskId)
        val committedFile = scrollbackFile(taskId)
        if (!raw.isFile || raw.length() == 0L) return
        if (committedFile.isFile && committedFile.lastModified() >= raw.lastModified()) return
        val content = runCatching { raw.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val replayableContent = trimLegacyTmuxCopyModeOutput(content)
        val derived = runCatching { replayCaptureStyledRows(replayableContent) }.getOrNull()
            ?.joinToString("\n") { it.ansi }
            ?.trimEnd()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(derived))) return
        val prior = runCatching {
            if (committedFile.isFile) committedFile.readText().trimEnd() else ""
        }.getOrDefault("")
        val combined = if (prior.isBlank()) derived else prior + SCROLLBACK_SESSION_SEPARATOR + derived
        atomicWriteText(committedFile, capScrollbackSize(combined))
        derivedRawCache.remove(taskId)
        repairedAnsiCache.remove(taskId)
    }

    /** Flush the remaining bytes and derive `scrollback.ansi` once, at end of session. */
    private fun finalizeScrollback(handle: Handle) {
        if (handle.session is TmuxAttachBackend || handle.session is TmuxAgentBackend) {
            runCatching { persistScrollback(handle, historyLinesOverride = -1) }
            return
        }
        if (appendTeeDelta(handle)) {
            runCatching { persistRawScrollback(handle) }
        } else {
            runCatching { persistScrollback(handle) }
        }
    }

    /**
     * Copy and append only the tee suffix that is not already on disk.
     *
     * Resolve the cursor and append under one lock so concurrent timer/final flushes cannot
     * request the same range. This also bounds the timer allocation by bytes produced since
     * its last tick instead of by the full retained raw stream.
     */
    private fun appendTeeDelta(handle: Handle): Boolean = synchronized(handle.scrollbackLock) {
        val snapshot = teeSnapshot(handle.session, handle.rawScrollback.cursor())
            ?: return@synchronized false
        runCatching { handle.rawScrollback.append(snapshot) }.isSuccess
    }

    private fun teeSnapshot(
        session: TerminalSession,
        cursor: ScrollbackAnsiCursor? = null,
    ): app.andy.terminal.ScrollbackAnsiSnapshot? =
        when (session) {
            is RustTerminalBackend -> session.scrollbackAnsiSnapshot(cursor)
            // Tmux history is captured via capture-pane; attached-client tees include
            // copy-mode noise and must not be persisted as the transcript source.
            else -> null
        }

    /**
     * Reconstruct this run once from its durable raw mirror, including recorded grid changes.
     *
     * This is intentionally an end-of-session/on-demand cost. The steady timer performs no
     * terminal emulation and no transcript alignment.
     */
    private fun persistRawScrollback(handle: Handle): Unit = synchronized(handle.scrollbackLock) {
        val cursor = handle.rawScrollback.cursor() ?: return@synchronized
        if (cursor == handle.committedRawCursor) return@synchronized
        val raw = rawScrollbackFile(handle.taskId)
        val content = runCatching { raw.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@synchronized
        val snapshot = replayRawHistory(handle, content)
        commitScrollback(handle, snapshot)
        handle.committedRawCursor = cursor
    }

    /**
     * Snapshot the authoritative terminal model and fold it into this run's transcript.
     * tmux sessions always come from `capture-pane -e`; attached-client bytes include
     * copy-mode navigation and are never valid history records.
     */
    private fun persistScrollback(
        handle: Handle,
        historyLinesOverride: Int? = null,
    ): Unit = synchronized(handle.scrollbackLock) {
        val captureRows = if (handle.foreground.get()) {
            RustTerminalBackend.SCROLLBACK_CAPTURE_ROWS
        } else {
            RustTerminalBackend.SCROLLBACK_BACKGROUND_CAPTURE_ROWS
        }
        val snapshot = when (val session = handle.session) {
            is TmuxAttachBackend, is TmuxAgentBackend ->
                captureTmuxRows(handle.taskId, historyLinesOverride ?: captureRows)
            is RustTerminalBackend ->
                replayCapture(handle, session.scrollbackAnsiSnapshot())
            else -> captureTmuxRows(handle.taskId, captureRows)
        }
        commitScrollback(handle, snapshot)
    }

    /** Fold [snapshot] into this chat and atomically publish its replay transcript. */
    private fun commitScrollback(handle: Handle, snapshot: List<StyledTerminalRow>) {
        if (snapshot.isNotEmpty()) handle.scrollback.merge(snapshot)
        val export = handle.scrollback.render()
        if (export.isBlank()) return
        if (TmuxAndy.paneContentLooksLikeFailedAttach(stripAnsi(export))) return
        atomicWriteText(handle.scrollbackPath, capScrollbackSize(export))
    }

    private fun replayCapture(
        handle: Handle,
        snapshot: app.andy.terminal.ScrollbackAnsiSnapshot,
    ): List<StyledTerminalRow> = (handle.scrollbackReplay ?: ScrollbackReplayCapture().also {
        handle.scrollbackReplay = it
    }).capture(snapshot)

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
            // whichever emulator is currently attached.
            is TmuxAttachBackend -> session.foreground = foreground
            is RustTerminalBackend -> session.foregroundProvider = { foreground.get() }
            else -> Unit
        }
    }

    private fun scrollbackFlushDelay(handle: Handle): Long = when (handle.session) {
        is TmuxAttachBackend, is TmuxAgentBackend ->
            if (handle.foreground.get()) TMUX_SCROLLBACK_FLUSH_MILLIS else TMUX_SCROLLBACK_BACKGROUND_MILLIS
        else -> if (handle.foreground.get()) SCROLLBACK_FLUSH_MILLIS else SCROLLBACK_BACKGROUND_MILLIS
    }

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
        private const val TMUX_SCROLLBACK_FLUSH_MILLIS = 10_000L
        private const val TMUX_SCROLLBACK_BACKGROUND_MILLIS = 30_000L

        /** Raw PTY mirror written on the live path; `scrollback.ansi` is derived from it. */
        private const val RAW_SCROLLBACK_NAME = "scrollback.raw"
        /** Stable identity for a growing raw file; a shorter/replaced file triggers reset. */
        private const val RAW_FILE_REPLAY_EPOCH = 1L
        /** Max wait after DirectPty exit for trailing buffer bytes when scrollback is still empty. */
        private const val DIRECT_PTY_SCROLLBACK_GRACE_ATTEMPTS = 5
        private const val DIRECT_PTY_SCROLLBACK_GRACE_MS = 20L

        /** Re-check cadence for a Direct PTY that has not published an exit code yet. */
        private const val EXIT_CODE_POLL_MS = 100L

        /**
         * How long a dead Direct PTY may go without publishing an exit code before
         * [awaitDirectPtyExit] gives up. Publishing needs [BossTermBackend]'s own wait
         * coroutine — parked in a blocking `pty.waitFor()` on its own internal scope,
         * independent of this process's dispatcher — to actually get scheduled and observe
         * the reap. 2s proved too tight under real scheduler/GC jitter (a shared CI runner,
         * or just a busy dev machine): the exit code lands a beat late, [isAlive] has already
         * flipped false, and the turn is misreported as [AgentStatus.Error] with an unknown
         * exit code instead of the [AgentStatus.Done] it actually reached.
         */
        private const val EXIT_CODE_GRACE_MS = 8_000L

        /** Returned when a session ends without ever reporting a status. */
        const val UNKNOWN_EXIT_CODE = RustTerminalBackend.CLOSED_EXIT_CODE
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

/** True when argv already carries the first-turn prompt (so Andy will not PTY-type it). */
internal fun argvHasEmbeddedPrompt(argv: List<String>): Boolean {
    val promptFlags = setOf("--prompt", "--prompt-interactive", "-i")
    for (index in argv.indices) {
        if (argv[index] in promptFlags && index + 1 < argv.size && argv[index + 1].isNotBlank()) {
            return true
        }
    }
    // Providers that accept a trailing positional prompt (Codex / Claude / Pi / Cursor).
    // OpenCode's positional is a directory — never treat a bare path-looking tail as a prompt.
    val last = argv.lastOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("-") } ?: return false
    val previous = argv.getOrNull(argv.lastIndex - 1)
    if (previous != null && previous.startsWith("-")) return false
    return true
}
