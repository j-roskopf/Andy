package app.andy.desktop.service.computeruse

import app.andy.model.ComputerAction
import app.andy.model.ComputerActionResult
import app.andy.model.ComputerActionVerdict
import app.andy.model.ComputerUseActionLogEntry
import app.andy.model.ComputerUseArmRequest
import app.andy.model.ComputerUseCapabilities
import app.andy.model.ComputerUseHudState
import app.andy.model.ComputerUsePermissionStatus
import app.andy.model.ComputerUsePlatform
import app.andy.model.ComputerUseScope
import app.andy.model.HostAccessibilityElement
import app.andy.model.HostAccessibilityTree
import app.andy.model.HostElementBounds
import app.andy.model.HostElementKind
import app.andy.model.HostScreenshotResult
import app.andy.service.ComputerUseArmResult
import app.andy.service.ComputerUseService
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val json = Json { ignoreUnknownKeys = true }

data class ComputerUseSession(
    val id: String,
    val scope: ComputerUseScope,
    val attended: Boolean,
    val startedAtEpochMs: Long,
    val wallClockCapSeconds: Int,
    val ownerTaskId: String? = null,
    val extraHighConsequenceLabels: List<String> = emptyList(),
)

/**
 * Local (GUI-process) computer-use implementation — Phase 1 macOS attended slice.
 */
class LocalComputerUseService(
    private val workspaceStore: WorkspaceStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val headless: Boolean = isHeadlessJvm(),
    private val platform: ComputerUsePlatform = detectComputerUsePlatform(),
    /** How long an attended arm request waits for an explicit user decision before denying. */
    private val armDecisionTimeoutMillis: Long = 120_000,
) : ComputerUseService {
    private val mutex = Mutex()
    private val sessionRef = AtomicReference<ComputerUseSession?>(null)
    private var disarmJob: Job? = null
    private var pendingAction: ComputerAction? = null
    private var pendingReason: String? = null
    private val armDecisions = Channel<Boolean>(Channel.CONFLATED)

    private val _hud = MutableStateFlow<ComputerUseHudState?>(null)
    override val hud: StateFlow<ComputerUseHudState?> = _hud.asStateFlow()

    private val _pendingArm = MutableStateFlow<ComputerUseArmRequest?>(null)
    override val pendingArm: StateFlow<ComputerUseArmRequest?> = _pendingArm.asStateFlow()

    private val _actionLog = MutableStateFlow<List<ComputerUseActionLogEntry>>(emptyList())
    override val actionLog: StateFlow<List<ComputerUseActionLogEntry>> = _actionLog.asStateFlow()

    private val lastDumpElements = AtomicReference<List<HostAccessibilityElement>>(emptyList())

    override suspend fun capabilities(): ComputerUseCapabilities {
        val ws = workspaceStore.state?.value ?: return ComputerUseCapabilities(
            platform = platform,
            available = false,
            unavailableReason = "Workspace not loaded.",
        )
        val master = ws.computerUseEnabled
        val session = sessionRef.get()
        if (headless) {
            return ComputerUseCapabilities(
                platform = platform,
                available = false,
                unavailableReason = "Computer use requires the Andy desktop UI process " +
                    "(andyd is headless and cannot inject input or read Accessibility).",
                masterSwitchEnabled = master,
                armed = false,
            )
        }
        return when (platform) {
            ComputerUsePlatform.LinuxWayland -> ComputerUseCapabilities(
                platform = platform,
                available = false,
                unavailableReason = "Computer use is not supported on Wayland. " +
                    "Use an X11 session, or run Andy under XWayland with DISPLAY set. " +
                    "Global input injection is blocked by the compositor.",
                masterSwitchEnabled = master,
            )
            ComputerUsePlatform.Windows, ComputerUsePlatform.LinuxX11 -> ComputerUseCapabilities(
                platform = platform,
                available = false,
                unavailableReason = "Computer use on this platform ships after the macOS attended slice " +
                    "(Phase ${if (platform == ComputerUsePlatform.Windows) "2" else "3"}).",
                masterSwitchEnabled = master,
                accessibility = ComputerUsePermissionStatus.NotRequired,
                screenRecording = ComputerUsePermissionStatus.NotRequired,
            )
            ComputerUsePlatform.Unsupported -> ComputerUseCapabilities(
                platform = platform,
                available = false,
                unavailableReason = "Unsupported host platform for computer use.",
                masterSwitchEnabled = master,
            )
            ComputerUsePlatform.MacOs -> {
                val axTrusted = MacOsComputerUseNative.isAccessibilityTrusted()
                val screenOk = MacOsComputerUseNative.isScreenCaptureAllowed()
                val processOutside = !axTrusted // Specific launchd diagnosis when bundle holds grant is best-effort
                ComputerUseCapabilities(
                    platform = platform,
                    available = master && axTrusted,
                    unavailableReason = when {
                        !master -> "Enable Computer Use in Settings (master switch is off)."
                        !axTrusted -> buildString {
                            append("Accessibility permission is not granted to this process. ")
                            append("Open System Settings → Privacy & Security → Accessibility and enable Andy. ")
                            append("If Andy.app already has the grant but this process does not, ")
                            append("you launched outside the app chain (e.g. launchd / terminal) — ")
                            append("start Andy from the app bundle so the child inherits TCC.")
                            append(" Restart Andy after granting.")
                        }
                        else -> null
                    },
                    accessibility = if (axTrusted) {
                        ComputerUsePermissionStatus.Granted
                    } else {
                        ComputerUsePermissionStatus.Denied
                    },
                    screenRecording = if (screenOk) {
                        ComputerUsePermissionStatus.Granted
                    } else {
                        ComputerUsePermissionStatus.Denied
                    },
                    processOutsideAppChain = processOutside && master,
                    masterSwitchEnabled = master,
                    armed = session != null,
                    sessionId = session?.id,
                    ownerTaskId = session?.ownerTaskId,
                    scopeAppNames = session?.scope?.appNames.orEmpty(),
                    attended = session?.attended ?: true,
                    accessibilitySettingsUrl =
                        "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
                    screenRecordingSettingsUrl =
                        "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture",
                )
            }
        }
    }

    override suspend fun requestControl(
        scope: ComputerUseScope,
        attended: Boolean,
        profileId: String?,
        wallClockCapSeconds: Int?,
        ownerTaskId: String?,
    ): ComputerUseArmResult {
        val caps = capabilities()
        if (!caps.masterSwitchEnabled) {
            return ComputerUseArmResult.Denied("Computer Use master switch is off (Settings → Computer Use).")
        }
        if (!caps.available && caps.unavailableReason != null && sessionRef.get() == null) {
            // Still allow arming attempt to surface permission errors clearly after user accepts.
            if (platform != ComputerUsePlatform.MacOs || headless) {
                return ComputerUseArmResult.Denied(caps.unavailableReason!!)
            }
        }
        ComputerUseProfileStore.validateScope(scope)?.let {
            return ComputerUseArmResult.Denied(it)
        }
        if (!attended) {
            return ComputerUseArmResult.Denied(
                "Unattended computer use is Phase 4 — use an attended session for now.",
            )
        }
        val profiles = ComputerUseProfileStore.decode(
            workspaceStore.state?.value?.computerUseProfilesJson,
        )
        val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
        val capSeconds = wallClockCapSeconds
            ?: profile?.wallClockCapSeconds
            ?: workspaceStore.state?.value?.computerUseDefaultWallClockSeconds
            ?: 600

        if (attended) {
            val existing = _pendingArm.value
            if (existing != null) {
                return ComputerUseArmResult.Denied(
                    "An arming request is already awaiting user approval (${existing.requestId}).",
                )
            }
            val requestId = UUID.randomUUID().toString()
            _pendingArm.value = ComputerUseArmRequest(
                requestId = requestId,
                scope = scope,
                requestedAtEpochMs = clock(),
                ownerTaskId = ownerTaskId,
            )
            while (armDecisions.tryReceive().isSuccess) { /* drop stale decisions */ }
            val accepted = try {
                withTimeoutOrNull(armDecisionTimeoutMillis) { armDecisions.receive() } ?: false
            } finally {
                if (_pendingArm.value?.requestId == requestId) _pendingArm.value = null
            }
            if (!accepted) {
                return ComputerUseArmResult.Denied("Computer-use arming was not approved by the user.")
            }
        }

        val session = ComputerUseSession(
            id = UUID.randomUUID().toString(),
            scope = scope,
            attended = attended,
            startedAtEpochMs = clock(),
            wallClockCapSeconds = capSeconds,
            ownerTaskId = ownerTaskId,
            extraHighConsequenceLabels = profile?.highConsequenceLabels.orEmpty(),
        )
        mutex.withLock {
            sessionRef.set(session)
            _actionLog.value = emptyList()
            publishHud(session)
            scheduleAutoDisarm(session)
        }
        return ComputerUseArmResult.Armed(session.id)
    }

    override fun decideArm(requestId: String, accepted: Boolean) {
        val pending = _pendingArm.value ?: return
        if (pending.requestId == requestId) {
            armDecisions.trySend(accepted)
        }
    }

    override suspend fun releaseControl(sessionId: String?): ComputerActionResult {
        mutex.withLock {
            val current = sessionRef.get()
            if (current == null) {
                return ComputerActionResult(ComputerActionVerdict.Succeeded, "Already disarmed")
            }
            if (sessionId != null && sessionId != current.id) {
                return ComputerActionResult(ComputerActionVerdict.Failed, "Session id mismatch")
            }
            clearSessionLocked("released")
        }
        return ComputerActionResult(ComputerActionVerdict.Succeeded, "Control released")
    }

    override fun panic(reason: String) {
        scope.launch {
            mutex.withLock { clearSessionLocked("panic: $reason") }
        }
    }

    override suspend fun dump(appName: String?, menus: Boolean): HostAccessibilityTree {
        val session = requireArmed()
        val target = resolveTargetApp(session, appName)
        return withContext(Dispatchers.IO) {
            val raw = MacOsComputerUseNative.dumpTree(target, menus)
            parseDumpJson(raw, target).also { lastDumpElements.set(it.elements) }
        }
    }

    override suspend fun findElements(
        query: String,
        role: String?,
        limit: Int,
    ): List<HostAccessibilityElement> {
        requireArmed()
        val q = query.trim().lowercase()
        return lastDumpElements.get()
            .asSequence()
            .filter { el ->
                (role == null || el.role.equals(role, ignoreCase = true)) &&
                    (el.label?.lowercase()?.contains(q) == true || el.id == query)
            }
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    override suspend fun screenshot(appName: String?): HostScreenshotResult {
        val session = requireArmed()
        return withContext(Dispatchers.IO) {
            val displays = parseDisplays(MacOsComputerUseNative.displayGeometryJson())
            val target = appName ?: session.scope.appNames.firstOrNull()
            val rect: HostRect? = if (target != null) {
                resolveTargetApp(session, target)
                parseAppWindowBounds(MacOsComputerUseNative.appWindowBounds(target))?.let {
                    HostRect(it.x, it.y, it.width, it.height)
                } ?: CoordinateSpace.unionBounds(displays)
            } else {
                if (!session.scope.wholeDesktop) {
                    error("No app in scope — pass appName or arm with a scoped app set")
                }
                CoordinateSpace.unionBounds(displays)
            }
            val bytes = MacOsComputerUseNative.capturePng(
                x = rect?.x ?: 0,
                y = rect?.y ?: 0,
                w = rect?.width ?: 0,
                h = rect?.height ?: 0,
            ) ?: error(
                "Screen capture failed. Grant Screen Recording to Andy in System Settings " +
                    "→ Privacy & Security → Screen Recording, then restart Andy.",
            )
            val persist = workspaceStore.state?.value?.computerUsePersistScreenshots == true
            val savedPath = if (persist) {
                ComputerUseScreenshotStore.save(bytes, session.id, clock())
            } else {
                null
            }
            val primary = displays.firstOrNull { it.primary } ?: displays.firstOrNull()
            HostScreenshotResult(
                pngBase64 = Base64.getEncoder().encodeToString(bytes),
                scaleFactor = primary?.scale ?: 1.0,
                originX = rect?.x ?: 0,
                originY = rect?.y ?: 0,
                width = rect?.width ?: 0,
                height = rect?.height ?: 0,
                savedPath = savedPath,
                untrusted = true,
            )
        }
    }

    override suspend fun act(action: ComputerAction, confirmHighConsequence: Boolean): ComputerActionResult {
        val session = requireArmed()
        if (!session.attended && action is ComputerAction.Drag) {
            return deny("computer_drag is unavailable in unattended mode")
        }
        if (!session.attended && action is ComputerAction.Tap && action.elementId == null) {
            return deny("Pixel fallback (coordinate tap) is unavailable in unattended mode")
        }

        // High-consequence gate
        val consequence = evaluateConsequence(session, action)
        if (consequence != null && !confirmHighConsequence) {
            if (!session.attended) {
                panic("high-consequence action aborted (unattended)")
                return ComputerActionResult(
                    verdict = ComputerActionVerdict.Denied,
                    message = consequence,
                    needsConfirmation = false,
                )
            }
            pendingAction = action
            pendingReason = consequence
            publishHud(session)
            return ComputerActionResult(
                verdict = ComputerActionVerdict.NeedsConfirmation,
                message = consequence,
                needsConfirmation = true,
                confirmationReason = consequence,
            )
        }

        pendingAction = null
        pendingReason = null
        val result = withContext(Dispatchers.IO) { perform(session, action) }
        appendLog(result, action)
        return result
    }

    override suspend fun confirmPendingAction(): ComputerActionResult {
        val action = pendingAction
            ?: return ComputerActionResult(ComputerActionVerdict.Failed, "No pending action")
        pendingAction = null
        pendingReason = null
        sessionRef.get()?.let { publishHud(it) }
        return act(action, confirmHighConsequence = true)
    }

    override suspend fun discardPendingAction(): ComputerActionResult {
        val had = pendingAction != null
        pendingAction = null
        pendingReason = null
        sessionRef.get()?.let { publishHud(it) }
        return ComputerActionResult(
            ComputerActionVerdict.Succeeded,
            if (had) "Pending high-consequence action discarded" else "No pending action",
        )
    }

    private suspend fun perform(session: ComputerUseSession, action: ComputerAction): ComputerActionResult {
        return when (action) {
            is ComputerAction.Tap -> performTap(session, action)
            is ComputerAction.InputText -> performInputText(session, action)
            is ComputerAction.PressKey -> performPressKey(session, action)
            is ComputerAction.Scroll -> performScroll(session, action)
            is ComputerAction.Drag -> {
                focusDenial(session)?.let { return deny(it) }
                MacOsComputerUseNative.drag(
                    action.startX, action.startY, action.endX, action.endY, action.durationMs,
                )
                ComputerActionResult(ComputerActionVerdict.Succeeded, "Drag completed (synthetic)")
            }
        }
    }

    private fun performTap(session: ComputerUseSession, action: ComputerAction.Tap): ComputerActionResult {
        val elementId = action.elementId
        if (elementId != null && !action.forceSynthetic) {
            val meta = parseElementMeta(MacOsComputerUseNative.elementMeta(elementId))
            if (meta.secure) {
                return deny("Refusing to activate a secure field")
            }
            val raw = MacOsComputerUseNative.pressElement(elementId)
            return parseActionResult(raw, elementId)
        }
        val x = action.x ?: return deny("tap requires element_id or x,y")
        val y = action.y ?: return deny("tap requires element_id or x,y")
        focusDenial(session)?.let { return deny(it) }
        MacOsComputerUseNative.click(x, y)
        return ComputerActionResult(
            ComputerActionVerdict.Unverified,
            "Synthetic click at $x,$y — verify with screenshot or ui_dump",
        )
    }

    private fun performInputText(session: ComputerUseSession, action: ComputerAction.InputText): ComputerActionResult {
        val elementId = action.elementId
        if (elementId != null) {
            val meta = parseElementMeta(MacOsComputerUseNative.elementMeta(elementId))
            if (meta.secure || HighConsequenceClassifier.isSecureField(meta.role, meta.subrole)) {
                return deny("Typing into secure fields is unconditionally blocked")
            }
            MacOsComputerUseNative.focusElement(elementId)
            // Prefer real typing for web/Electron reliability (§5).
            MacOsComputerUseNative.typeText(action.text)
            return ComputerActionResult(
                ComputerActionVerdict.Unverified,
                "Typed ${action.text.length} chars into element $elementId",
                elementId = elementId,
            )
        }
        focusDenial(session)?.let { return deny(it) }
        MacOsComputerUseNative.typeText(action.text)
        return ComputerActionResult(
            ComputerActionVerdict.Unverified,
            "Typed ${action.text.length} chars into focused element",
        )
    }

    private fun performPressKey(session: ComputerUseSession, action: ComputerAction.PressKey): ComputerActionResult {
        val code = macKeyCode(action.key) ?: return deny("Unknown key: ${action.key}")
        focusDenial(session)?.let { return deny(it) }
        val mods = action.modifiers.map { it.lowercase() }.toSet()
        MacOsComputerUseNative.pressKey(
            keyCode = code,
            shift = "shift" in mods,
            ctrl = "ctrl" in mods || "control" in mods,
            alt = "alt" in mods || "option" in mods,
            meta = "meta" in mods || "cmd" in mods || "command" in mods,
        )
        return ComputerActionResult(ComputerActionVerdict.Succeeded, "Pressed ${action.key}")
    }

    private fun performScroll(session: ComputerUseSession, action: ComputerAction.Scroll): ComputerActionResult {
        val elementId = action.elementId
        val (x, y) = if (elementId != null) {
            val meta = parseElementMeta(MacOsComputerUseNative.elementMeta(elementId))
            val b = meta.bounds ?: return deny("Element has no bounds for scroll")
            b.x + b.width / 2 to b.y + b.height / 2
        } else {
            focusDenial(session)?.let { return deny(it) }
            (action.x ?: return deny("scroll requires element_id or x,y")) to
                (action.y ?: return deny("scroll requires element_id or x,y"))
        }
        MacOsComputerUseNative.scroll(x, y, action.deltaX, action.deltaY)
        return ComputerActionResult(ComputerActionVerdict.Succeeded, "Scrolled at $x,$y")
    }

    /** Denial reason when global focus is outside the armed scope or in a secure field. */
    private fun focusDenial(session: ComputerUseSession): String? =
        focusedTargetDenial(session.scope, parseFocusedAppInfo(MacOsComputerUseNative.focusedAppInfo()))

    private fun evaluateConsequence(session: ComputerUseSession, action: ComputerAction): String? {
        when (action) {
            is ComputerAction.InputText -> {
                val elementId = action.elementId
                if (elementId != null) {
                    val meta = parseElementMeta(MacOsComputerUseNative.elementMeta(elementId))
                    if (meta.secure) return "Secure field blocked"
                }
            }
            is ComputerAction.Tap -> {
                val elementId = action.elementId
                if (elementId != null) {
                    val meta = parseElementMeta(MacOsComputerUseNative.elementMeta(elementId))
                    val verdict = HighConsequenceClassifier.classify(
                        label = meta.label,
                        role = meta.role,
                        extraLabels = session.extraHighConsequenceLabels,
                        unlabeledUnattended = !session.attended,
                    )
                    if (verdict.highConsequence) return verdict.reason
                }
            }
            else -> Unit
        }
        return null
    }

    private fun requireArmed(): ComputerUseSession {
        val session = sessionRef.get()
            ?: error("Computer use is not armed. Call computer_request_control first.")
        val elapsed = (clock() - session.startedAtEpochMs) / 1000
        if (elapsed > session.wallClockCapSeconds) {
            panic("wall-clock cap exceeded")
            error("Computer-use session expired (wall-clock cap ${session.wallClockCapSeconds}s).")
        }
        return session
    }

    private fun resolveTargetApp(session: ComputerUseSession, appName: String?): String {
        if (appName != null) {
            if (ComputerUseScopeDenylist.isDenied(appName)) {
                error(ComputerUseScopeDenylist.deniedReason(appName))
            }
            if (!session.scope.wholeDesktop &&
                session.scope.appNames.none { it.equals(appName, ignoreCase = true) }
            ) {
                error("App \"$appName\" is outside the armed scope ${session.scope.appNames}")
            }
            return appName
        }
        return session.scope.appNames.firstOrNull()
            ?: error("No app in scope — pass appName or arm with a scoped app set")
    }

    private fun scheduleAutoDisarm(session: ComputerUseSession) {
        disarmJob?.cancel()
        disarmJob = scope.launch {
            delay(session.wallClockCapSeconds * 1000L)
            mutex.withLock {
                if (sessionRef.get()?.id == session.id) {
                    clearSessionLocked("auto-disarm wall-clock")
                }
            }
        }
    }

    private fun clearSessionLocked(reason: String) {
        disarmJob?.cancel()
        disarmJob = null
        pendingAction = null
        pendingReason = null
        val previous = sessionRef.getAndSet(null)
        if (previous != null) {
            appendLogUnlocked(
                ComputerActionResult(ComputerActionVerdict.Succeeded, "Disarmed ($reason)"),
                summary = "disarm: $reason",
            )
        }
        _hud.value = null
    }

    private fun publishHud(session: ComputerUseSession) {
        _hud.value = ComputerUseHudState(
            sessionId = session.id,
            scopeAppNames = session.scope.appNames,
            attended = session.attended,
            startedAtEpochMs = session.startedAtEpochMs,
            ownerTaskId = session.ownerTaskId,
            actions = _actionLog.value.takeLast(30),
            pendingConfirmation = pendingReason,
        )
    }

    private fun appendLog(result: ComputerActionResult, action: ComputerAction) {
        val summary = when (action) {
            is ComputerAction.Tap ->
                "tap element=${action.elementId ?: "${action.x},${action.y}"}"
            is ComputerAction.InputText -> "input_text len=${action.text.length}"
            is ComputerAction.PressKey -> "press_key ${action.key}"
            is ComputerAction.Scroll -> "scroll"
            is ComputerAction.Drag -> "drag"
        }
        appendLogUnlocked(result, summary)
        sessionRef.get()?.let { publishHud(it) }
    }

    private fun appendLogUnlocked(result: ComputerActionResult, summary: String) {
        val entry = ComputerUseActionLogEntry(
            atEpochMs = clock(),
            summary = "$summary → ${result.verdict.name.lowercase()}: ${result.message}",
            verdict = result.verdict,
        )
        _actionLog.value = (_actionLog.value + entry).takeLast(200)
    }

    private fun deny(message: String) =
        ComputerActionResult(ComputerActionVerdict.Denied, message)
}

internal data class ElementMeta(
    val role: String? = null,
    val subrole: String? = null,
    val label: String? = null,
    val secure: Boolean = false,
    val bounds: HostElementBounds? = null,
)

internal fun parseElementMeta(raw: String): ElementMeta {
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return ElementMeta()
    return ElementMeta(
        role = obj["role"]?.jsonPrimitive?.contentOrNull,
        subrole = obj["subrole"]?.jsonPrimitive?.contentOrNull,
        label = obj["label"]?.jsonPrimitive?.contentOrNull,
        secure = obj["secure"]?.jsonPrimitive?.booleanOrNull == true,
        bounds = obj["b"]?.jsonPrimitive?.contentOrNull?.let { HostElementBounds.parse(it) },
    )
}

internal fun parseActionResult(raw: String, elementId: String?): ComputerActionResult {
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return ComputerActionResult(ComputerActionVerdict.Failed, raw)
    val verdict = when (obj["verdict"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
        "succeeded" -> ComputerActionVerdict.Succeeded
        "failed" -> ComputerActionVerdict.Failed
        "unverified" -> ComputerActionVerdict.Unverified
        "denied" -> ComputerActionVerdict.Denied
        else -> ComputerActionVerdict.Failed
    }
    return ComputerActionResult(
        verdict = verdict,
        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: raw,
        axErrorCode = obj["axError"]?.jsonPrimitive?.intOrNull,
        elementId = elementId ?: obj["elementId"]?.jsonPrimitive?.contentOrNull,
    )
}

internal fun parseDumpJson(raw: String, fallbackApp: String): HostAccessibilityTree {
    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return HostAccessibilityTree(appName = fallbackApp, elements = emptyList())
    if (obj["error"] != null) {
        error(obj["error"]!!.jsonPrimitive.content)
    }
    val elements = obj["elements"]?.jsonArray?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val kind = when (o["k"]?.jsonPrimitive?.contentOrNull) {
            "control" -> HostElementKind.Control
            "text" -> HostElementKind.Text
            else -> HostElementKind.Other
        }
        HostAccessibilityElement(
            id = o["i"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            role = o["r"]?.jsonPrimitive?.contentOrNull ?: "?",
            label = o["l"]?.jsonPrimitive?.contentOrNull,
            bounds = o["b"]?.jsonPrimitive?.contentOrNull?.let { HostElementBounds.parse(it) },
            pressable = o["p"]?.jsonPrimitive?.booleanOrNull == true,
            secure = o["s"]?.jsonPrimitive?.booleanOrNull == true,
            kind = kind,
            subrole = o["sr"]?.jsonPrimitive?.contentOrNull,
        )
    }.orEmpty()
    return HostAccessibilityTree(
        appName = obj["app"]?.jsonPrimitive?.contentOrNull ?: fallbackApp,
        elements = elements,
        truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true,
        payloadBytes = obj["payloadBytes"]?.jsonPrimitive?.intOrNull ?: 0,
        windowCount = obj["windowCount"]?.jsonPrimitive?.intOrNull ?: 0,
        windowsOpenOnScreen = obj["windowsOnScreen"]?.jsonPrimitive?.booleanOrNull ?: true,
        untrusted = true,
    )
}

internal fun parseDisplays(raw: String): List<DisplayGeometry> {
    val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        DisplayGeometry(
            id = o["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            x = o["x"]?.jsonPrimitive?.intOrNull ?: 0,
            y = o["y"]?.jsonPrimitive?.intOrNull ?: 0,
            width = o["w"]?.jsonPrimitive?.intOrNull ?: 0,
            height = o["h"]?.jsonPrimitive?.intOrNull ?: 0,
            scale = o["scale"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0,
            primary = o["primary"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}

/** Minimal macOS virtual key codes for common keys. */
internal fun macKeyCode(key: String): Int? = when (key.lowercase()) {
    "return", "enter" -> 36
    "tab" -> 48
    "space" -> 49
    "delete", "backspace" -> 51
    "escape", "esc" -> 53
    "left" -> 123
    "right" -> 124
    "down" -> 125
    "up" -> 126
    "a" -> 0; "s" -> 1; "d" -> 2; "f" -> 3; "h" -> 4; "g" -> 5
    "z" -> 6; "x" -> 7; "c" -> 8; "v" -> 9; "b" -> 11
    "q" -> 12; "w" -> 13; "e" -> 14; "r" -> 15; "y" -> 16; "t" -> 17
    "1" -> 18; "2" -> 19; "3" -> 20; "4" -> 21; "6" -> 22; "5" -> 23
    "9" -> 25; "7" -> 26; "8" -> 28; "0" -> 29
    "o" -> 31; "u" -> 32; "i" -> 34; "p" -> 35; "l" -> 37; "j" -> 38
    "k" -> 40; "n" -> 45; "m" -> 46
    else -> key.toIntOrNull()
}
