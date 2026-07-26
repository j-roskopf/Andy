package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A status observation with confidence for notification gating. */
data class AgentStatusSnapshot(
    val status: AgentStatus,
    val confident: Boolean,
)

/**
 * Hybrid status tracker aligned with Herdr's detection policy:
 * - vendor lifecycle hooks (`status.json` via [installStatusSignals]) are authoritative when present
 * - per-agent screen manifests for blocked / working / idle (regions + OSC) as fallback
 * - known agent + no match → idle/Done (idle fallback)
 * - Working→plain-idle requires pending confirmation (Herdr-style)
 *
 * Intentionally does **not** treat PTY buffer churn as Working. That heuristic
 * fights Cursor alt-screen redraws and is the main source of idle↔working flicker.
 */
class AgentStatusTracker(
    private val scope: CoroutineScope,
    private val taskId: String,
    private val agent: AgentKind,
    private val artifactDir: File,
    private val session: TerminalSession,
    private val onSnapshot: (AgentStatusSnapshot) -> Unit,
    /**
     * Optional starting snapshot for viewer reattach. Prevents StateFlow collectors from
     * briefly observing the default Working and flipping a finished chat back to Working.
     * New runs leave this null.
     */
    initialSnapshot: AgentStatusSnapshot? = null,
    private val foreground: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(true),
) {
    private val hook = HookStatusSource(artifactDir)
    private val _status = MutableStateFlow(
        initialSnapshot ?: AgentStatusSnapshot(AgentStatus.Working, confident = false),
    )
    val status: StateFlow<AgentStatusSnapshot> = _status.asStateFlow()

    private val scrape = ScrapeStatusSource(agent)
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var jobs: List<Job> = emptyList()

    @Volatile private var latch: AgentStatusSnapshot? =
        initialSnapshot?.takeIf { it.confident }

    fun start() {
        if (closed.get() || paused.get()) return
        artifactDir.mkdirs()
        startJobs()
    }

    /** Stop hook/buffer/poll loops while no viewer is mounted for this chat. */
    fun pause() {
        if (!paused.compareAndSet(false, true)) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Resume status observation after a viewer is attached again. */
    fun resume() {
        if (closed.get() || !paused.compareAndSet(true, false)) return
        startJobs()
    }

    private fun startJobs() {
        jobs = listOf(
            scope.launch { hook.watch { publish() } },
            scope.launch {
                session.bufferSnapshots.collect { buffer ->
                    scrape.onBuffer(buffer)
                    scrape.onOsc(
                        title = session.windowTitle.value,
                        progress = session.oscProgress.value,
                    )
                    publish()
                }
            },
            scope.launch {
                session.windowTitle.collect { title ->
                    scrape.onOsc(title = title, progress = session.oscProgress.value)
                    publish()
                }
            },
            scope.launch {
                session.oscProgress.collect { progress ->
                    scrape.onOsc(title = session.windowTitle.value, progress = progress)
                    publish()
                }
            },
            scope.launch {
                while (isActive && !closed.get() && !paused.get()) {
                    val pollMs = when {
                        !foreground.get() -> BACKGROUND_STATUS_POLL_MS
                        scrape.hasPendingIdle() -> PENDING_IDLE_POLL_MS
                        else -> STATUS_POLL_MS
                    }
                    delay(pollMs)
                    if (paused.get() || closed.get()) return@launch
                    // Deliberately does not re-read the screen. The backend already pushes
                    // every change through bufferSnapshots, so polling for it re-fetched a
                    // buffer that was almost always identical — and on tmux backends that
                    // fetch was a fork, at up to 10/s per chat during pending-idle. Feeding
                    // an unchanged buffer to onBuffer only re-runs the manifest, which is
                    // exactly what tick() below does.
                    scrape.onOsc(
                        title = session.windowTitle.value,
                        progress = session.oscProgress.value,
                    )
                    scrape.tick()
                    if (!session.isAlive) {
                        val exit = session.exitCode.value
                        publish(processExited = true, exitCode = exit)
                    } else {
                        publish()
                    }
                }
            },
        )
    }

    /** Clears latch when user sends a message or session resumes. */
    fun clearLatch() {
        latch = null
        scrape.clearPendingIdle()
    }

    fun markPhaseFinished() {
        publish(phaseFinished = true)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pause()
    }

    private fun publish(
        processExited: Boolean = false,
        exitCode: Int? = null,
        phaseFinished: Boolean = false,
    ) {
        if (closed.get()) return

        val confidentSignal = when {
            processExited -> AgentStatusSnapshot(
                status = if (exitCode == 0 || exitCode == null) AgentStatus.Done else AgentStatus.Error,
                confident = true,
            )
            phaseFinished -> AgentStatusSnapshot(AgentStatus.Done, confident = true)
            else -> {
                val hookStatus = hook.latest()
                when (hookStatus) {
                    AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Error ->
                        AgentStatusSnapshot(hookStatus, confident = true)
                    AgentStatus.Working ->
                        AgentStatusSnapshot(AgentStatus.Working, confident = true)
                    null -> null
                }
            }
        }

        if (confidentSignal != null) {
            // Hook Done is authoritative unless the screen shows a strong visible working cue (e.g. active spinner).
            if (confidentSignal.status == AgentStatus.Done &&
                !phaseFinished &&
                !processExited &&
                scrape.showsVisibleWorking()
            ) {
                latch = null
            } else {
                latch = confidentSignal
                emit(confidentSignal)
                return
            }
        }

        val latchSnapshot = latch
        if (latchSnapshot != null && latchSnapshot.confident && latchHolds(latchSnapshot)) {
            emit(latchSnapshot)
            return
        }

        val scrapeHint = scrape.badgeHint() ?: return
        val scrapeConfident = when (scrapeHint) {
            AgentStatus.Blocked -> scrape.isCurrentlyBlocked()
            AgentStatus.Done -> scrape.isDoneConfident()
            else -> false
        }
        // Soft idle (Done, not yet confident) is treated as Working for *new* runs so the
        // badge does not flash Done during boot. Once we already know the chat is Done
        // (reattach / remount seed), keep Done — a half-drawn idle screen after switching
        // chat windows must not invent Working.
        val alreadyDone = _status.value.status == AgentStatus.Done ||
            latch?.status == AgentStatus.Done
        val publishedStatus = if (scrapeHint == AgentStatus.Done && !scrapeConfident) {
            if (alreadyDone) AgentStatus.Done else AgentStatus.Working
        } else {
            scrapeHint
        }
        val snapshot = AgentStatusSnapshot(publishedStatus, confident = scrapeConfident)
        if (scrapeConfident) {
            latch = snapshot
        }
        emit(snapshot)
    }

    private fun latchHolds(latch: AgentStatusSnapshot): Boolean = when (latch.status) {
        AgentStatus.Blocked -> scrape.isCurrentlyBlocked() || hook.latest() == AgentStatus.Blocked
        AgentStatus.Done, AgentStatus.Error -> {
            if (hook.latest() == AgentStatus.Working) return false
            // Only a strong working indicator unlatches Done (no churn→Working).
            !scrape.showsWorkingIndicator()
        }
        AgentStatus.Working -> true
    }

    private fun emit(snapshot: AgentStatusSnapshot) {
        if (_status.value != snapshot) {
            _status.value = snapshot
            onSnapshot(snapshot)
        }
    }

    companion object {
        private const val STATUS_POLL_MS = 500L
        private const val PENDING_IDLE_POLL_MS = 100L
        private const val BACKGROUND_STATUS_POLL_MS = 3_000L
    }
}

/** Reads `.andy/<taskId>/status.json` written by CLI lifecycle hooks or the file protocol. */
class HookStatusSource(
    private val artifactDir: File,
) {
    private val statusFile = File(artifactDir, "status.json")
    /** Eager read so [AgentStatusTracker] can seed from hooks on reattach. */
    @Volatile private var latest: AgentStatus? = readLatestHookStatus(artifactDir)

    fun latest(): AgentStatus? = latest

    suspend fun watch(onChange: () -> Unit) {
        var lastModified = -1L
        while (true) {
            if (statusFile.isFile) {
                val modified = statusFile.lastModified()
                if (modified != lastModified) {
                    lastModified = modified
                    latest = readLatestHookStatus(artifactDir)
                    onChange()
                }
            }
            delay(400)
        }
    }
}

/**
 * Herdr-style screen scrape (no PTY-churn→Working):
 * - evaluate manifests for blocked / working / idle
 * - known agent + no match → Done (idle fallback)
 * - Working→plain-idle held until pending confirmation
 *
 * Confidence / notifications are decided by [AgentStatusTracker].
 */
class ScrapeStatusSource(
    private val agent: AgentKind,
) {
    @Volatile private var lastBuffer: String = ""
    @Volatile private var lastBufferCleaned: String = ""
    @Volatile private var oscTitle: String = ""
    @Volatile private var oscProgress: String = ""
    @Volatile private var hint: AgentStatus? = null
    @Volatile private var lastMatch: ManifestMatch? = null
    @Volatile private var lastVisibleIdle: Boolean = false
    @Volatile private var lastVisibleBlocker: Boolean = false
    @Volatile private var lastVisibleWorking: Boolean = false

    private val pendingIdle = PendingIdleConfirmation()

    fun badgeHint(): AgentStatus? = hint

    fun hasPendingIdle(): Boolean = pendingIdle.active

    fun clearPendingIdle() {
        pendingIdle.clear()
    }

    fun onOsc(title: String, progress: String) {
        val titleChanged = title != oscTitle
        val progressChanged = progress != oscProgress
        if (!titleChanged && !progressChanged) return
        oscTitle = title
        oscProgress = progress
        publishFromManifest()
    }

    fun onBuffer(buffer: String) {
        val trimmed = buffer.takeLast(SCRAPE_BUFFER_CHARS)
        val cleaned = stripAnsiControlSequences(trimmed).trimEnd()
        lastBuffer = trimmed
        lastBufferCleaned = cleaned
        publishFromManifest()
    }

    /** Re-evaluate (pending-idle confirmations advance on the poll loop). */
    fun tick() {
        publishFromManifest()
    }

    fun indicatesWorking(): Boolean = showsWorkingIndicator()

    fun showsVisibleWorking(): Boolean {
        val match = lastMatch ?: evaluateCurrent().also { lastMatch = it }
        return match.visibleWorking
    }

    fun showsWorkingIndicator(): Boolean {
        val match = lastMatch ?: evaluateCurrent().also { lastMatch = it }
        if (match.visibleWorking || match.state == ScreenState.Working) return true
        return bufferLooksWorking(agent, detectionInput())
    }

    fun isCurrentlyBlocked(): Boolean {
        val match = lastMatch ?: evaluateCurrent().also { lastMatch = it }
        return match.state == ScreenState.Blocked
    }

    fun isQuiescentAtPrompt(): Boolean {
        val match = lastMatch ?: evaluateCurrent().also { lastMatch = it }
        if (match.skipStateUpdate) return false
        if (match.state == ScreenState.Blocked || match.state == ScreenState.Working) return false
        if (match.visibleWorking) return false
        return hint == AgentStatus.Done && isDoneConfident()
    }

    /** True when idle/Done should notify or finish an active run (prompt visible, not mid-stream). */
    fun isDoneConfident(): Boolean {
        if (showsWorkingIndicator()) return false
        if (pendingIdle.active) return false
        if (lastVisibleIdle) return true
        val match = lastMatch ?: evaluateCurrent().also { lastMatch = it }
        if (match.state == ScreenState.Idle && !match.idleFallback) return true
        if (match.idleFallback || match.state == ScreenState.Idle) {
            val screen = detectionInput().screen
            if (terminalBufferLooksReadyForInput(screen)) return true
            if (agent == AgentKind.Cursor && cursorChromeLooksIdle(screen)) return true
            return false
        }
        return false
    }

    private fun publishFromManifest() {
        val match = evaluateCurrent()
        if (match.skipStateUpdate) return
        lastMatch = match

        val next = DetectionPublishState(
            status = when {
                match.state == ScreenState.Blocked || match.visibleBlocker -> AgentStatus.Blocked
                match.state == ScreenState.Working || match.visibleWorking -> AgentStatus.Working
                match.state == ScreenState.Idle || match.idleFallback -> AgentStatus.Done
                else -> return
            },
            visibleIdle = match.visibleIdle &&
                (match.state == ScreenState.Idle && !match.idleFallback),
            visibleBlocker = match.visibleBlocker && match.state == ScreenState.Blocked,
            visibleWorking = match.visibleWorking && match.state == ScreenState.Working,
        )

        val previousStatus = hint
        if (previousStatus != null) {
            val previous = DetectionPublishState(
                status = previousStatus,
                visibleIdle = lastVisibleIdle,
                visibleBlocker = lastVisibleBlocker,
                visibleWorking = lastVisibleWorking,
            )
            if (pendingIdle.shouldHoldWorkingToIdle(previous, next, now = System.currentTimeMillis())) {
                // Keep showing Working while soft idle is confirmed.
                hint = AgentStatus.Working
                return
            }
        }

        hint = next.status
        lastVisibleIdle = next.visibleIdle
        lastVisibleBlocker = next.visibleBlocker
        lastVisibleWorking = next.visibleWorking
    }

    private fun evaluateCurrent(): ManifestMatch =
        evaluateScreenManifest(agent, detectionInput())

    private fun detectionInput(): DetectionInput {
        val screen = lastBufferCleaned.ifBlank { lastBuffer }.takeLast(SCRAPE_BUFFER_CHARS)
        return DetectionInput(screen = screen, oscTitle = oscTitle, oscProgress = oscProgress)
    }
}

/** Mirror of Herdr's `PendingIdleConfirmation` / `DetectionPublishState`. */
internal data class DetectionPublishState(
    val status: AgentStatus,
    val visibleIdle: Boolean = false,
    val visibleBlocker: Boolean = false,
    val visibleWorking: Boolean = false,
)

/**
 * Hold Working→plain-Idle (idle fallback, no visible_idle) until confirmed.
 * Visible idle / blocked / working transitions publish immediately.
 */
internal class PendingIdleConfirmation {
    private var startedAt: Long? = null
    private var confirmations: Int = 0

    val active: Boolean get() = startedAt != null

    fun clear() {
        startedAt = null
        confirmations = 0
    }

    fun shouldHoldWorkingToIdle(
        previous: DetectionPublishState,
        next: DetectionPublishState,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val isWorkingToPlainIdle = previous.status == AgentStatus.Working &&
            next.status == AgentStatus.Done &&
            !next.visibleIdle &&
            !next.visibleBlocker

        if (!isWorkingToPlainIdle) {
            clear()
            return false
        }

        val started = startedAt
        if (started == null) {
            startedAt = now
            confirmations = 0
            return true
        }

        if (now - started >= PENDING_IDLE_CAP_MS) {
            clear()
            return false
        }

        confirmations += 1
        if (confirmations >= PENDING_IDLE_CONFIRMATIONS) {
            clear()
            return false
        }
        return true
    }
}

data class ScrapeRules(
    val blocked: List<Regex>,
    val idlePrompt: List<Regex>,
    val working: List<Regex> = emptyList(),
)

private const val SCRAPE_BUFFER_CHARS = 4_000
internal const val PENDING_IDLE_CONFIRMATIONS = 5
internal const val PENDING_IDLE_CAP_MS = 1_500L

internal fun stripAnsiControlSequences(input: String): String {
    if (input.isEmpty()) return ""
    return input.replace(Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])"""), "")
}

internal fun bufferLooksBlocked(agent: AgentKind, buffer: String): Boolean =
    bufferLooksBlocked(agent, DetectionInput(screen = buffer.takeLast(SCRAPE_BUFFER_CHARS)))

internal fun bufferLooksBlocked(agent: AgentKind, input: DetectionInput): Boolean {
    if (input.screen.isBlank() && input.oscTitle.isBlank()) return false
    val match = evaluateScreenManifest(agent, input)
    return match.state == ScreenState.Blocked
}

internal fun bufferLooksWorking(agent: AgentKind, buffer: String): Boolean =
    bufferLooksWorking(agent, DetectionInput(screen = buffer.takeLast(SCRAPE_BUFFER_CHARS)))

internal fun bufferLooksWorking(agent: AgentKind, input: DetectionInput): Boolean {
    if (input.screen.isBlank() && input.oscTitle.isBlank()) return false
    val match = evaluateScreenManifest(agent, input)
    return match.state == ScreenState.Working || match.visibleWorking
}

internal fun bufferLooksIdle(agent: AgentKind, buffer: String): Boolean =
    bufferLooksIdle(agent, DetectionInput(screen = buffer.takeLast(SCRAPE_BUFFER_CHARS)))

internal fun bufferLooksIdle(agent: AgentKind, input: DetectionInput): Boolean {
    if (input.screen.isBlank() && input.oscTitle.isBlank()) return false
    val match = evaluateScreenManifest(agent, input)
    if (match.state == ScreenState.Blocked || match.state == ScreenState.Working) return false
    if (match.state == ScreenState.Idle && !match.idleFallback) return true
    // Soft idle fallback: require a prompt-like tail for recovery / "at prompt" checks.
    // (Screen scrape publish uses idle fallback more aggressively, with pending confirm.)
    if (match.idleFallback) {
        if (terminalBufferLooksReadyForInput(input.screen)) return true
        if (agent == AgentKind.Cursor && cursorChromeLooksIdle(input.screen)) return true
    }
    return false
}

/** Cursor's alt-screen footer at rest (model %, follow-up affordance) without a plain `>` prompt. */
internal fun cursorChromeLooksIdle(screen: String): Boolean {
    val tail = bottomNonEmptyLines(screen, 8).lowercase()
    if (tail.isBlank() || "ctrl+c to stop" in tail) return false
    return "→ add a follow-up" in tail ||
        Regex("""cursor .+ · \d""").containsMatchIn(tail)
}

/**
 * Compatibility view of manifest rules for recovery/tests.
 * Prefer [evaluateScreenManifest] for new call sites.
 */
fun scrapeRulesFor(agent: AgentKind): ScrapeRules {
    val rules = screenManifestFor(agent)
    fun needles(state: ScreenState): List<Regex> =
        rules.filter { it.state == state && !it.skipStateUpdate }
            .flatMap { rule ->
                rule.gate.contains.map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) } +
                    rule.gate.regex +
                    rule.gate.lineRegex
            }
    return ScrapeRules(
        blocked = needles(ScreenState.Blocked),
        idlePrompt = needles(ScreenState.Idle),
        working = needles(ScreenState.Working),
    )
}

internal fun parseStatusJson(raw: String): AgentStatus? {
    val normalized = raw.lowercase()
    return when {
        "blocked" in normalized -> AgentStatus.Blocked
        "\"done\"" in normalized || "\"status\": \"done\"" in normalized || """"status":"done"""" in normalized ->
            AgentStatus.Done
        "error" in normalized || "failed" in normalized -> AgentStatus.Error
        "working" in normalized || "busy" in normalized -> AgentStatus.Working
        else -> null
    }
}

internal fun appendAgentStatus(artifactDir: File, status: AgentStatus) {
    artifactDir.mkdirs()
    val statusPath = File(artifactDir, "status.json")
    val line = """{"status":"${status.name.lowercase()}","at":${System.currentTimeMillis() / 1000}}"""
    statusPath.appendText(line + "\n")
}
