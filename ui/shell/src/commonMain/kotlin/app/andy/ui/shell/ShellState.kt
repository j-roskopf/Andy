package app.andy.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.andy.BrowserNavCommand
import app.andy.AndyDestination
import app.andy.closeEmbeddedBrowser
import app.andy.currentTimeMillis
import app.andy.showsSideChat
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.SavedDockLayout
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.PairedWifiDevice
import app.andy.service.IosTargetRegistry
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.service.DhuSessionPhase
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OpenInvestigationRequest
import app.andy.service.TargetCapabilities
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.controls.ensureEmulatorOrientation
import app.andy.ui.inspector.InspectorState
import app.andy.ui.devices.reconnectPairedWifiDevice
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ShellState(
    private val services: AndyServices,
    private val scope: CoroutineScope,
) {
    var destination by mutableStateOf(AndyDestination.Devices)
        private set
    var devices by mutableStateOf<List<AndroidDevice>>(emptyList())
        private set
    var iosTargets by mutableStateOf<List<IosTarget>>(emptyList())
        private set
    var sdk by mutableStateOf(SdkDiscovery(null, null, null, null, null, listOf("SDK not scanned yet")))
        private set
    var selectedSerial by mutableStateOf<String?>(null)
        private set
    var selectedIosUdid by mutableStateOf<String?>(null)
        private set
    /** Shared Live/Controls hinge angle for foldable emulator previews (degrees, 0–180). */
    var foldableHingeAngle by mutableStateOf(180f)
        private set
    /**
     * Android Auto DHU intent, owned here rather than by Live. Live leaves composition on every
     * navigation, so a Live-scoped intent took the head unit down whenever the user visited
     * another tab. Cleared by the AA toggle or an intentional switch to another target.
     */
    var androidAutoSerial by mutableStateOf<String?>(null)
        private set
    val activeTargetId: String?
        get() = selectedIosUdid ?: selectedSerial

    val isIosSelection: Boolean
        get() = selectedIosUdid != null

    /** Feature surface for the active target; drives sidebar gating and Live chrome. */
    val targetCapabilities: TargetCapabilities
        get() {
            val udid = selectedIosUdid ?: return TargetCapabilities.Android
            val target = iosTargets.firstOrNull { it.udid == udid } ?: return TargetCapabilities.Simulator
            return TargetCapabilities.of(target)
        }

    var workspaceState by mutableStateOf(WorkspaceState())
        private set
    var workspaceLoaded by mutableStateOf(false)
        private set
    var networkRulesVisible by mutableStateOf(false)
        private set
    var networkLiveVisible by mutableStateOf(false)
        private set
    var performanceLiveVisible by mutableStateOf(false)
        private set
    var stoppingEmulatorSerial by mutableStateOf<String?>(null)
        private set
    var emulatorStopStatus by mutableStateOf("")
        private set
    var startingEmulatorName by mutableStateOf<String?>(null)
        private set
    var emulatorStartStatus by mutableStateOf("")
        private set
    var actionsConfig by mutableStateOf(ActionsConfig())
        private set
    var activeRunId by mutableStateOf<String?>(null)
        private set
    var terminalRunId by mutableStateOf<String?>(null)
        private set
    var docks by mutableStateOf(ShellDocks())
        private set
    var lastTerminalPlacement by mutableStateOf(DockPlacement.Right)
        private set
    /** Nav state per Browser [DockTab.id] — kept out of [DockTab] itself, mirroring how
     * Terminal state lives in [DockTab.terminalTree] but browser tabs aren't a split tree. */
    var browserPanes by mutableStateOf<Map<String, BrowserPaneState>>(emptyMap())
        private set
    /** Chat currently on screen in Agents/Projects — seeds a new side-chat pane. */
    var viewedAgentTaskId by mutableStateOf<String?>(null)
        private set
    var sideChatLaunchingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** In-app deep links for contextual agent actions (§5), separate from OS-level requests. */
    var pendingAgentTaskOpen by mutableStateOf<OpenAgentTaskRequest?>(null)
        private set
    var pendingInvestigationOpen by mutableStateOf<OpenInvestigationRequest?>(null)
        private set
    /** Settings category label to select on next Settings entry (e.g. "Proxy"). */
    var pendingSettingsCategory by mutableStateOf<String?>(null)
        private set
    private var startupTargetId: String? = null
    private var startupSelectionResolved = false
    private var handledTerminalRunId: String? = null
    private var paneIdSeq = 0
    private fun nextPaneId(prefix: String) = "$prefix-${paneIdSeq++}"

    val logcatState = LogcatState()
    val liveLogcatState = LogcatState()
    val inspectorState = InspectorState()
    val transfer = DeviceTransferCoordinator()

    fun navigateTo(value: AndyDestination) {
        if (value == AndyDestination.Tracing) {
            destination = AndyDestination.Performance
            updateWorkspace { it.copy(performanceTab = app.andy.model.PerformanceTab.Tracing.name) }
        } else {
            destination = value
        }
    }

    fun openProxySettings() {
        pendingSettingsCategory = "Proxy"
        navigateTo(AndyDestination.Settings)
    }

    fun consumeSettingsCategory(): String? {
        val value = pendingSettingsCategory
        pendingSettingsCategory = null
        return value
    }

    /** Opens the chat a contextual action just launched, in whichever destination owns it. */
    fun openAgentTask(request: OpenAgentTaskRequest) {
        pendingAgentTaskOpen = request
        navigateTo(if (request.projectId == null) AndyDestination.Agents else AndyDestination.Actions)
    }

    fun consumeAgentTaskOpen() {
        pendingAgentTaskOpen = null
    }

    /** Returns from a chat to the investigation, event, and playback position behind it. */
    fun openInvestigation(request: OpenInvestigationRequest) {
        pendingInvestigationOpen = request
        navigateTo(AndyDestination.Bugs)
    }

    fun consumeInvestigationOpen() {
        pendingInvestigationOpen = null
    }

    fun selectDevice(serial: String?) {
        if (selectedSerial != serial) foldableHingeAngle = 180f
        selectedSerial = serial
        if (serial != null) {
            selectedIosUdid = null
            clearAndroidAutoForTargetSwitch(serial)
        }
        persistSelectedTarget()
    }

    fun selectIosTarget(udid: String?) {
        selectedIosUdid = udid
        if (udid != null) {
            selectedSerial = null
            clearAndroidAutoForTargetSwitch(udid)
        }
        persistSelectedTarget()
    }

    /**
     * Sets (or clears) the Android Auto intent. Stopping runs on the shell scope so navigating
     * away mid-teardown can't cancel it; starting is driven by Live once the mirror is up.
     */
    fun updateAndroidAutoSerial(serial: String?) {
        if (androidAutoSerial == serial) return
        androidAutoSerial = serial
        if (serial == null) {
            scope.launch { services.dhu.stop() }
        }
    }

    /**
     * Drops DHU when the user moves to a *different* target. Transient offline flicker during USB
     * accessory renegotiation never routes through here, so an in-flight session survives it.
     */
    private fun clearAndroidAutoForTargetSwitch(targetId: String) {
        val current = androidAutoSerial ?: return
        if (targetId != current) updateAndroidAutoSerial(null)
    }

    fun updateFoldableHingeAngle(angle: Float) {
        foldableHingeAngle = if (angle.coerceIn(0f, 180f) < 90f) 0f else 180f
    }

    fun setDeviceLabel(targetId: String, label: String) {
        updateWorkspace { state ->
            state.copy(
                deviceLabels = if (label.isBlank()) {
                    state.deviceLabels - targetId
                } else {
                    state.deviceLabels + (targetId to label.trim())
                },
            )
        }
    }

    fun setDeviceNote(targetId: String, note: String) {
        updateWorkspace { state ->
            state.copy(
                deviceNotes = if (note.isBlank()) {
                    state.deviceNotes - targetId
                } else {
                    state.deviceNotes + (targetId to note)
                },
            )
        }
    }

    private fun persistSelectedTarget() {
        val targetId = activeTargetId
        if (workspaceState.selectedDeviceSerial == targetId) return
        updateWorkspace { it.copy(selectedDeviceSerial = targetId) }
    }

    private fun isTargetAvailable(targetId: String): Boolean {
        if (iosTargets.any { it.udid == targetId && it.isLiveReady }) return true
        return devices.any { it.serial == targetId && it.state == DeviceConnectionState.Online }
    }

    private fun applyTarget(targetId: String) {
        if (iosTargets.any { it.udid == targetId && it.isLiveReady }) {
            selectedIosUdid = targetId
            selectedSerial = null
        } else {
            if (selectedSerial != targetId) foldableHingeAngle = 180f
            selectedSerial = targetId
            selectedIosUdid = null
        }
    }

    private fun defaultOnlineAndroidSerial(): String? =
        devices.firstOrNull { it.state == DeviceConnectionState.Online }?.serial

    fun bootIosSimulator(target: IosTarget) {
        scope.launch {
            startingEmulatorName = target.displayName
            emulatorStartStatus = "Booting ${target.displayName}..."
            val result = services.iosDevices.boot(target.udid)
            if (!result.isSuccess) {
                emulatorStartStatus = result.stderr.ifBlank { result.stdout }
                startingEmulatorName = null
                return@launch
            }
            repeat(90) {
                refreshDevicesNow()
                val booted = iosTargets.firstOrNull { it.udid == target.udid }?.state == IosTargetState.Booted
                if (booted) {
                    emulatorStartStatus = "${target.displayName} is ready"
                    selectIosTarget(target.udid)
                    startingEmulatorName = null
                    return@launch
                }
                emulatorStartStatus = "${target.displayName} booting..."
                delay(1_000)
            }
            emulatorStartStatus = "${target.displayName} boot timed out — try Refresh"
            startingEmulatorName = null
        }
    }

    fun shutdownIosSimulator(target: IosTarget) {
        scope.launch {
            services.iosDevices.shutdown(target.udid)
            refreshDevicesNow()
        }
    }

    fun openIosInSimulatorApp(target: IosTarget) {
        scope.launch { services.iosDevices.openInSimulatorApp(target.udid) }
    }

    fun updateNetworkRulesVisible(value: Boolean) {
        networkRulesVisible = value
    }

    fun toggleNetworkRulesVisible() {
        networkRulesVisible = !networkRulesVisible
    }

    fun updateNetworkLiveVisible(value: Boolean) {
        networkLiveVisible = value
    }

    fun toggleNetworkLiveVisible() {
        networkLiveVisible = !networkLiveVisible
    }

    fun updatePerformanceLiveVisible(value: Boolean) {
        performanceLiveVisible = value
    }

    fun togglePerformanceLiveVisible() {
        performanceLiveVisible = !performanceLiveVisible
    }

    fun updateActiveRunId(value: String?) {
        activeRunId = value
    }

    /**
     * Placement icon: hide the pane if open; if it already has tabs, show it again;
     * if the landing chooser is already up for this placement, dismiss it;
     * otherwise show the landing chooser (empty pane).
     */
    fun onPlacementIconClick(placement: DockPlacement) {
        docks = docks.onPlacementIconClick(placement, destination.showsSideChat)
    }

    fun dismissDockLanding() {
        docks = docks.copy(landingFor = null)
    }

    fun openDockKind(placement: DockPlacement, kind: DockTabKind, newTerminal: Boolean = false) {
        when (kind) {
            DockTabKind.Live -> if (newTerminal) openNewLiveTab(placement) else openOrFocusLive(placement)
            DockTabKind.Logs -> docks = docks.update(placement) { it.withTab(DockTab.logs()) }
            DockTabKind.Terminal -> if (newTerminal) openNewTerminalTab(placement) else openOrFocusTerminal(placement)
            DockTabKind.Browser -> {
                val existing = docks.existingBrowserTab()?.let { (from, tabId) ->
                    docks.pane(from).tabs.firstOrNull { it.id == tabId }
                }
                val tab = existing ?: DockTab.browser(nextPaneId("browser")).also { created ->
                    browserPanes = browserPanes + (created.id to BrowserPaneState())
                }
                val discarded = (docks.right.tabs + docks.bottom.tabs)
                    .filter { it.kind == DockTabKind.Browser && it.id != tab.id }
                    .map { it.id }
                discarded.forEach { closeBrowserPane(it) }
                docks = docks.withBrowserExclusive(placement, tab)
            }
            DockTabKind.Chat -> {
                if (!destination.showsSideChat) return
                openSideChat(placement, forceNew = newTerminal)
            }
        }
    }

    /** Focus an existing Live tab in [placement], or open one seeded with the global target. */
    fun openOrFocusLive(placement: DockPlacement) {
        val pane = docks.pane(placement)
        val existing = pane.activeTab?.takeIf { it.kind == DockTabKind.Live }
            ?: pane.tabs.lastOrNull { it.kind == DockTabKind.Live }
        if (existing != null) {
            docks = docks.update(placement) { it.selectTab(existing.id) }
            return
        }
        openNewLiveTab(placement)
    }

    fun openNewLiveTab(
        placement: DockPlacement = DockPlacement.Right,
        seedWithActiveTarget: Boolean = true,
    ) {
        val targetId = activeTargetId.takeIf { seedWithActiveTarget }
        val title = targetId?.let { liveTabTitle(it) }
        val tab = DockTab.live(
            id = nextPaneId("live"),
            leafId = nextPaneId("live-leaf"),
            targetId = targetId,
            title = title,
        )
        docks = docks.update(placement) { it.withTab(tab) }
        reconcileLiveMirrorHolds()
    }

    /** Bind [targetId] to a Live leaf without changing the global device selection. */
    fun setLiveTabTarget(tabId: String, targetId: String?, leafId: String? = null) {
        val placement = liveTabPlacement(tabId) ?: return
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.kind != DockTabKind.Live) return
        val tree = tab.liveTree ?: return
        val resolvedLeafId = leafId
            ?: tab.focusedLiveLeafId?.takeIf { tree.findLeaf(it) != null }
            ?: tree.firstLeafId()
        val leaf = tree.findLeaf(resolvedLeafId) ?: return
        if (leaf.targetId == targetId) return
        // The native iOS hub owns a single decoder slot per kind (sim / physical). Two same-kind
        // iOS sources cannot run concurrently, so refuse to bind one if another dock live leaf
        // already holds that kind (re-binding this leaf's own target is already guarded above).
        val targetIosKind = iosTargets.firstOrNull { it.udid == targetId }?.kind
        if (targetIosKind != null &&
            targetIosKind in takenIosKinds(excludingTargetId = leaf.targetId)
        ) return
        val title = targetId?.let { liveTabTitle(it) }
        val nextTree = tree.mapLeaf(resolvedLeafId) { it.copy(targetId = targetId, title = title) }
        updateLiveTree(placement, tabId) { nextTree }
        focusLiveLeaf(placement, tabId, resolvedLeafId)
        reconcileLiveMirrorHolds()
    }

    fun focusLiveLeaf(placement: DockPlacement, tabId: String, leafId: String) {
        docks = docks.update(placement) { pane ->
            pane.copy(
                tabs = pane.tabs.map { tab ->
                    if (tab.id != tabId || tab.kind != DockTabKind.Live) {
                        tab
                    } else {
                        val leaf = tab.liveTree?.findLeaf(leafId)
                        tab.copy(
                            focusedLiveLeafId = leafId,
                            targetId = leaf?.targetId,
                            title = leaf?.title,
                        )
                    }
                },
            )
        }
    }

    /**
     * Visually splits the focused Live leaf into two panes (row or column). The new pane
     * starts unbound so the user picks a device. Distinct from [openNewLiveTab], which adds
     * another top-level dock tab.
     */
    fun splitLiveLeaf(placement: DockPlacement, tabId: String, leafId: String, axis: SplitAxis) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId } ?: return
        val tree = tab.liveTree ?: return
        if (tree.findLeaf(leafId) == null) return
        val newLeafId = nextPaneId("live-leaf")
        val newLeaf = LivePaneNode.Leaf(id = newLeafId)
        val nextTree = tree.split(leafId, nextPaneId("live-split"), axis, newLeaf)
        updateLiveTree(placement, tabId) { nextTree }
        focusLiveLeaf(placement, tabId, newLeafId)
    }

    fun closeLiveLeaf(placement: DockPlacement, tabId: String, leafId: String) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId } ?: return
        val tree = tab.liveTree ?: return
        val nextTree = tree.closeLeaf(leafId)
        if (nextTree == null) {
            docks = docks.update(placement) { it.closeTab(tabId) }
            reconcileLiveMirrorHolds()
            return
        }
        updateLiveTree(placement, tabId) { nextTree }
        val focusId = tab.focusedLiveLeafId?.takeIf { nextTree.findLeaf(it) != null }
            ?: nextTree.firstLeafId()
        focusLiveLeaf(placement, tabId, focusId)
        reconcileLiveMirrorHolds()
    }

    fun updateLiveSplitWeights(placement: DockPlacement, tabId: String, splitId: String, weights: List<Float>) {
        updateLiveTree(placement, tabId) { it.updateWeights(splitId, weights) }
    }

    private fun liveTabPlacement(tabId: String): DockPlacement? = when {
        docks.right.tabs.any { it.id == tabId } -> DockPlacement.Right
        docks.bottom.tabs.any { it.id == tabId } -> DockPlacement.Bottom
        else -> null
    }

    private fun updateLiveTree(
        placement: DockPlacement,
        tabId: String,
        transform: (LivePaneNode) -> LivePaneNode?,
    ) {
        docks = docks.update(placement) { pane ->
            val tab = pane.tabs.firstOrNull { it.id == tabId } ?: return@update pane
            val tree = tab.liveTree ?: return@update pane
            when (val next = transform(tree)) {
                null -> pane.closeTab(tabId)
                else -> {
                    val focusId = tab.focusedLiveLeafId?.takeIf { next.findLeaf(it) != null }
                        ?: next.firstLeafId()
                    val focused = next.findLeaf(focusId)
                    pane.copy(
                        tabs = pane.tabs.map {
                            if (it.id != tabId) {
                                it
                            } else {
                                it.copy(
                                    liveTree = next,
                                    focusedLiveLeafId = focusId,
                                    targetId = focused?.targetId,
                                    title = focused?.title,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    /** Pooled engine holds, one per bound Live leaf, tracked here to reconcile pause state. */
    private val liveHoldCounts = mutableMapOf<String, Int>()

    /**
     * Targets currently mirrored by the main Live destination or a pop-out. A dock Live leaf whose
     * target is paused must NOT hold a pooled engine — otherwise two scrcpy sessions (pooled + the
     * primary Live engine) feed the same Android serial. Fed from the desktop shell.
     */
    private var pausedLiveTargetIds: Set<String> = emptySet()

    /** Called from the desktop shell to refcount pooled mirror engines for Live dock tabs. */
    var onLiveMirrorHold: ((String) -> Unit)? = null
    var onLiveMirrorRelease: ((String) -> Unit)? = null

    private fun emitLiveHold(targetId: String) {
        liveHoldCounts[targetId] = (liveHoldCounts[targetId] ?: 0) + 1
        onLiveMirrorHold?.invoke(targetId)
    }

    private fun emitLiveRelease(targetId: String) {
        val next = (liveHoldCounts[targetId] ?: 1) - 1
        if (next <= 0) {
            liveHoldCounts.remove(targetId)
            onLiveMirrorRelease?.invoke(targetId)
        } else {
            liveHoldCounts[targetId] = next
        }
    }

    /** All device targets bound to Live dock leaves, in tree order (duplicates preserved). */
    fun currentLiveLeafTargetIds(): List<String> =
        (docks.right.tabs + docks.bottom.tabs)
            .filter { it.kind == DockTabKind.Live }
            .flatMap { it.liveTree?.targetIds().orEmpty() }

    /**
     * iOS kinds already bound to a live dock leaf other than [excludingTargetId]. The native hub
     * supports one decoder slot per kind, so a second same-kind iOS target cannot run concurrently.
     */
    fun takenIosKinds(excludingTargetId: String?): Set<IosTargetKind> =
        currentLiveLeafTargetIds()
            .filter { it != excludingTargetId }
            .mapNotNull { id -> iosTargets.firstOrNull { it.udid == id }?.kind }
            .toSet()

    /**
     * Marks targets taken over by the main Live destination or a pop-out. Paused leaves stop
     * holding their pooled engine so the takeover can connect without two sessions on one serial;
     * the hold is restored when the target is no longer paused.
     */
    fun setPausedLiveTargetIds(paused: Set<String>) {
        if (paused == pausedLiveTargetIds) return
        pausedLiveTargetIds = paused
        reconcileLiveMirrorHolds()
    }

    /**
     * Reconciles pooled engine holds against the current dock live tree minus paused targets.
     * Call after any structural live-tree change or a pause-state change.
     */
    private fun reconcileLiveMirrorHolds() {
        val desired = mutableMapOf<String, Int>()
        for (targetId in currentLiveLeafTargetIds()) {
            if (targetId in pausedLiveTargetIds) continue
            desired[targetId] = (desired[targetId] ?: 0) + 1
        }
        val ids = desired.keys + liveHoldCounts.keys
        for (id in ids) {
            val want = desired[id] ?: 0
            val have = liveHoldCounts[id] ?: 0
            repeat((want - have).coerceAtLeast(0)) { emitLiveHold(id) }
            repeat((have - want).coerceAtLeast(0)) { emitLiveRelease(id) }
        }
    }

    private fun liveTabTitle(targetId: String): String =
        workspaceState.deviceLabels[targetId]
            ?: devices.firstOrNull { it.serial == targetId }?.displayName
            ?: iosTargets.firstOrNull { it.udid == targetId }?.displayName
            ?: "Live"

    fun noteViewedAgentTaskId(taskId: String?) {
        viewedAgentTaskId = taskId
        if (taskId == null) return
        val parent = services.agentRuns.tasks.value.firstOrNull { it.id == taskId }
        val title = parent?.title?.let { "Side · $it".take(40) } ?: "Side chat"
        val nextRight = docks.right.bindUnstartedSideChats(taskId, title)
        val nextBottom = docks.bottom.bindUnstartedSideChats(taskId, title)
        if (nextRight !== docks.right || nextBottom !== docks.bottom) {
            docks = docks.copy(right = nextRight, bottom = nextBottom)
        }
    }

    fun openSideChat(placement: DockPlacement, forceNew: Boolean) {
        val parentId = viewedAgentTaskId
        if (!forceNew && parentId != null) {
            docks.existingChatTabForParent(parentId)?.let { (from, tabId) ->
                docks = docks.update(from) { it.selectTab(tabId) }
                return
            }
        }
        val parent = parentId?.let { id -> services.agentRuns.tasks.value.firstOrNull { it.id == id } }
        val tab = DockTab.chat(
            id = nextPaneId("chat"),
            parentChatTaskId = parentId,
            title = parent?.title?.let { "Side · $it".take(40) } ?: "Side chat",
        )
        docks = docks.update(placement) { it.withTab(tab) }
    }

    fun startSideChat(placement: DockPlacement, tabId: String, question: String, launch: SideChatLaunchConfig) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.kind != DockTabKind.Chat || tab.agentTaskId != null) return
        val parentId = tab.parentChatTaskId ?: viewedAgentTaskId ?: return
        val parent = services.agentRuns.tasks.value.firstOrNull { it.id == parentId } ?: return
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        sideChatLaunchingIds = sideChatLaunchingIds + tabId
        scope.launch {
            try {
                val child = services.agentRuns.createAndStart(
                    sideChatDraft(
                        parent = parent,
                        question = trimmed,
                        statuses = services.agentRuns.cliStatuses.value,
                        providerDefaults = services.agentRuns.providerDefaults.value,
                        launch = launch,
                    ),
                )
                docks = docks.update(placement) {
                    it.updateTab(tabId) { current ->
                        current.copy(agentTaskId = child.id, title = current.title ?: child.title)
                    }
                }
            } finally {
                sideChatLaunchingIds = sideChatLaunchingIds - tabId
            }
        }
    }

    /**
     * Opens [url] in an existing Browser dock tab if one is already open (right or bottom),
     * otherwise creates a new Browser tab on the right.
     */
    fun openUrlInBrowserTab(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        val existing = docks.existingBrowserTab()
        if (existing != null) {
            val (placement, tabId) = existing
            docks = docks.update(placement) { it.selectTab(tabId) }
            updateBrowserUrl(tabId, target)
            return
        }
        val tab = DockTab.browser(nextPaneId("browser"))
        browserPanes = browserPanes + (tab.id to BrowserPaneState(url = target))
        docks = docks.update(DockPlacement.Right) { it.withTab(tab) }
    }

    /** Address-bar submit or "Local" server click — records the URL and forwards a Go-To. */
    fun updateBrowserUrl(tabId: String, url: String) {
        browserPanes = browserPanes + (tabId to (browserPanes[tabId] ?: BrowserPaneState()).copy(url = url))
    }

    /** Persists the URL half of a nav command; Back/Forward/Refresh are surface-local and
     * don't need ShellState involvement beyond routing (see [BrowserPaneView]). */
    fun browserNav(tabId: String, command: BrowserNavCommand) {
        if (command is BrowserNavCommand.GoTo) updateBrowserUrl(tabId, command.url)
    }

    /** The platform [BrowserSurface] reports nav-state changes back up here after navigating. */
    fun updateBrowserNavState(tabId: String, url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) {
        val current = browserPanes[tabId] ?: return
        browserPanes = browserPanes + (tabId to current.copy(
            url = url,
            title = title,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            loading = loading,
        ))
    }

    private fun closeBrowserPane(tabId: String) {
        browserPanes = browserPanes - tabId
        if (browserPanes.isEmpty()) closeEmbeddedBrowser()
    }

    fun openOrFocusTerminal(placement: DockPlacement = lastTerminalPlacement) {
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull()
        if (project == null) {
            docks = docks.copy(landingFor = null)
            return
        }
        val runId = activeRunId?.takeIf { activeId ->
            // Prefer keeping the focused shell when it belongs to this project.
            services.actionRuns.running.value.any { it.runId == activeId && it.projectId == project.id }
        } ?: services.actionRuns.openShell(project)
        focusTerminalRun(runId, placement)
    }

    /**
     * The dock-level "+" — always spawns a brand-new top-level terminal workspace tab (its
     * own independent, single-pane split tree), never reusing or nesting into whatever's
     * currently open. Contrast [openNewTerminalTabInLeaf], which adds a session tab to one
     * specific existing pane instead.
     */
    fun openNewTerminalTab(placement: DockPlacement = lastTerminalPlacement) {
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull()
        if (project == null) {
            docks = docks.copy(landingFor = null)
            return
        }
        val runId = services.actionRuns.openShell(project)
        val tabId = nextPaneId("terminal-tab")
        val leafId = nextPaneId("leaf")
        lastTerminalPlacement = placement
        activeRunId = runId
        terminalRunId = runId
        handledTerminalRunId = runId
        val tree = TerminalPaneNode.Leaf(leafId, listOf(DockTab.terminal(runId)), "terminal:$runId")
        docks = docks.update(placement) { it.withTab(DockTab.terminalWorkspace(tabId, tree, leafId)) }
    }

    /** Reveals [runId]'s terminal tab wherever it lives, or opens a fresh top-level tab for it. */
    fun focusTerminalRun(runId: String, placement: DockPlacement = lastTerminalPlacement) {
        if (runId.isBlank()) return
        lastTerminalPlacement = placement
        activeRunId = runId
        terminalRunId = runId
        handledTerminalRunId = runId
        docks = docks.withTerminalExclusive(
            placement = placement,
            runId = runId,
            newTabId = nextPaneId("terminal-tab"),
            newLeafId = nextPaneId("leaf"),
        )
    }

    fun notifyTerminalRun(runId: String) {
        if (runId.isBlank()) return
        focusTerminalRun(runId, lastTerminalPlacement)
    }

    /** Resolves [activeRunId] from whichever leaf/tab last had focus in [placement]'s [tabId] workspace. */
    private fun syncActiveRunFromFocusedLeaf(placement: DockPlacement, tabId: String) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId } ?: return
        val leaf = tab.focusedTerminalLeafId?.let { tab.terminalTree?.findLeaf(it) }
        leaf?.activeTab?.runId?.let { activeRunId = it }
    }

    /** Mutates the [tabId] workspace's tree in place, dropping the whole tab if [transform] empties it. */
    private fun updateTerminalTree(placement: DockPlacement, tabId: String, transform: (TerminalPaneNode) -> TerminalPaneNode?) {
        docks = docks.update(placement) { pane ->
            val tab = pane.tabs.firstOrNull { it.id == tabId } ?: return@update pane
            val tree = tab.terminalTree ?: return@update pane
            when (val next = transform(tree)) {
                null -> pane.closeTab(tabId)
                else -> pane.copy(
                    tabs = pane.tabs.map {
                        if (it.id == tabId) {
                            it.copy(terminalTree = next, focusedTerminalLeafId = it.focusedTerminalLeafId?.takeIf { id -> next.findLeaf(id) != null } ?: next.firstLeafId())
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    /** Backs a leaf's own "+" button — spawns a fresh session and lands it in that exact leaf. */
    fun openNewTerminalTabInLeaf(placement: DockPlacement, tabId: String, leafId: String) {
        focusTerminalLeaf(placement, tabId, leafId)
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull() ?: return
        val runId = services.actionRuns.openShell(project)
        updateTerminalTree(placement, tabId) { it.addTab(leafId, DockTab.terminal(runId)) }
        lastTerminalPlacement = placement
        activeRunId = runId
        terminalRunId = runId
        handledTerminalRunId = runId
    }

    /** Click-to-focus target inside a terminal split tree. */
    fun focusTerminalLeaf(placement: DockPlacement, tabId: String, leafId: String) {
        docks = docks.update(placement) { pane ->
            pane.copy(tabs = pane.tabs.map { if (it.id == tabId) it.copy(focusedTerminalLeafId = leafId) else it })
        }
        syncActiveRunFromFocusedLeaf(placement, tabId)
    }

    fun selectLeafTab(placement: DockPlacement, tabId: String, leafId: String, innerTabId: String) {
        updateTerminalTree(placement, tabId) { it.selectTab(leafId, innerTabId) }
        focusTerminalLeaf(placement, tabId, leafId)
    }

    fun renameLeafTab(placement: DockPlacement, tabId: String, innerTabId: String, title: String) {
        updateTerminalTree(placement, tabId) { it.renameTab(innerTabId, title) }
    }

    /** Closes one terminal tab within a leaf without killing its PTY (servers keep running). */
    fun closeLeafTab(placement: DockPlacement, tabId: String, innerTabId: String) {
        val runId = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
            ?.terminalTree?.flattenTabs()?.firstOrNull { it.id == innerTabId }?.runId
        if (runId != null && activeRunId == runId) activeRunId = null
        updateTerminalTree(placement, tabId) { it.closeTab(innerTabId) }
        syncActiveRunFromFocusedLeaf(placement, tabId)
    }

    /** Closes a whole pane (every tab in it) without killing its PTYs. */
    fun closeLeaf(placement: DockPlacement, tabId: String, leafId: String) {
        val runIds = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
            ?.terminalTree?.findLeaf(leafId)?.tabs?.mapNotNull { it.runId }.orEmpty()
        if (activeRunId in runIds) activeRunId = null
        updateTerminalTree(placement, tabId) { it.closeLeaf(leafId) }
        syncActiveRunFromFocusedLeaf(placement, tabId)
    }

    /** The two split icons — always spawns a fresh interactive shell for the new pane. */
    fun splitLeaf(placement: DockPlacement, tabId: String, leafId: String, axis: SplitAxis) {
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull() ?: return
        val runId = services.actionRuns.openShell(project)
        val newLeafId = nextPaneId("leaf")
        val newLeaf = TerminalPaneNode.Leaf(newLeafId, listOf(DockTab.terminal(runId)), "terminal:$runId")
        updateTerminalTree(placement, tabId) { it.split(leafId, nextPaneId("split"), axis, newLeaf) }
        focusTerminalLeaf(placement, tabId, newLeafId)
        lastTerminalPlacement = placement
        activeRunId = runId
        terminalRunId = runId
        handledTerminalRunId = runId
    }

    fun updateSplitWeights(placement: DockPlacement, tabId: String, splitId: String, weights: List<Float>) {
        updateTerminalTree(placement, tabId) { it.updateWeights(splitId, weights) }
    }

    fun selectDockTab(placement: DockPlacement, tabId: String) {
        docks = docks.update(placement) { it.selectTab(tabId) }
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
        if (tab?.kind == DockTabKind.Terminal) {
            syncActiveRunFromFocusedLeaf(placement, tabId)
        }
    }

    fun renameDockTab(placement: DockPlacement, tabId: String, title: String) {
        docks = docks.update(placement) { it.renameTab(tabId, title) }
    }

    fun closeDockTab(placement: DockPlacement, tabId: String) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
        if (tab?.kind == DockTabKind.Terminal) {
            val runIds = tab.terminalTree?.flattenTabs()?.mapNotNull { it.runId }.orEmpty()
            if (activeRunId in runIds) activeRunId = null
        }
        if (tab?.kind == DockTabKind.Browser) {
            closeBrowserPane(tabId)
        }
        docks = docks.update(placement) { it.closeTab(tabId) }
        reconcileLiveMirrorHolds()
    }

    fun closeDock(placement: DockPlacement) {
        docks = docks.update(placement) { it.hide() }
    }

    private companion object {
        const val MaxSavedLayouts = 20
        const val MaxLayoutNameLength = 40
    }

    val savedLayouts: List<SavedDockLayout>
        get() = workspaceState.savedDockLayouts

    val canSaveDockLayout: Boolean
        get() = docks.right.tabs.isNotEmpty() || docks.bottom.tabs.isNotEmpty()

    val savedLayoutLimitReached: Boolean
        get() = workspaceState.savedDockLayouts.size >= MaxSavedLayouts

    /** "Layout 1", "Layout 2", … — first index not already taken (case-insensitive). */
    fun defaultDockLayoutName(): String {
        var index = 1
        while (savedLayouts.any { it.name.equals("Layout $index", ignoreCase = true) }) {
            index++
        }
        return "Layout $index"
    }

    fun saveDockLayout(name: String) {
        val trimmed = name.trim().take(MaxLayoutNameLength)
        if (trimmed.isEmpty()) return
        if (!canSaveDockLayout) return
        val existing = savedLayouts.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing == null && savedLayoutLimitReached) return
        val layout = docks.toSavedLayout(
            id = existing?.id ?: "layout-${currentTimeMillis()}-${paneIdSeq++}",
            name = trimmed,
            savedAtMillis = currentTimeMillis(),
            rightPaneWidth = workspaceState.rightDockPaneWidth,
            bottomPaneHeight = workspaceState.bottomDockPaneHeight,
            browserUrlOf = { browserPanes[it]?.url },
            projectIdOfRun = { runId ->
                services.actionRuns.running.value.firstOrNull { it.runId == runId }?.projectId
            },
        )
        updateWorkspace { ws ->
            val nextList = if (existing != null) {
                ws.savedDockLayouts.map { if (it.id == existing.id) layout else it }
            } else {
                ws.savedDockLayouts + layout
            }
            ws.copy(savedDockLayouts = nextList)
        }
    }

    fun loadDockLayout(layoutId: String) {
        val layout = workspaceState.savedDockLayouts.firstOrNull { it.id == layoutId } ?: return
        val restore = layout.toRestore(
            nextId = ::nextPaneId,
            openShell = { projectId ->
                restoreProject(projectId)?.let { services.actionRuns.openShell(it).takeIf(String::isNotBlank) }
            },
            isTargetAvailable = ::isTargetAvailable,
            isAgentTaskAlive = { id -> services.agentRuns.tasks.value.any { it.id == id } },
        )

        browserPanes = restore.browserPanes
        if (restore.browserPanes.isEmpty()) closeEmbeddedBrowser()

        docks = restore.docks
        reconcileLiveMirrorHolds()

        if (restore.focusedRunId != null) {
            activeRunId = restore.focusedRunId
            terminalRunId = restore.focusedRunId
            handledTerminalRunId = restore.focusedRunId
        } else {
            activeRunId = null
            terminalRunId = null
            handledTerminalRunId = null
        }
        restore.focusedTerminalPlacement?.let { lastTerminalPlacement = it }

        updateWorkspace {
            it.copy(
                rightDockPaneWidth = layout.rightPaneWidth,
                bottomDockPaneHeight = layout.bottomPaneHeight,
            )
        }
    }

    private fun restoreProject(projectId: String?): ActionProject? {
        if (!services.capabilities.hostAutomation) return null
        // A non-null ID must resolve to that exact project; a deleted project is dropped during
        // restore rather than silently remapped to the default (which is reserved for null).
        if (projectId != null) {
            return actionsConfig.projects.firstOrNull { it.id == projectId }
        }
        return actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull()
    }

    fun deleteDockLayout(layoutId: String) {
        updateWorkspace { it.copy(savedDockLayouts = it.savedDockLayouts.filterNot { l -> l.id == layoutId }) }
    }

    fun consumeTerminalRun(runningActions: List<RunningAction>) {
        val runId = terminalRunId ?: return
        val alreadyVisible = docks.right.tabOwningRun(runId) != null ||
            docks.bottom.tabOwningRun(runId) != null
        // Skip only when we've already opened this run *and* its tab is still present.
        // Closing the tab must not permanently suppress future reveals for the same runId.
        if (runId == handledTerminalRunId && alreadyVisible) return
        val run = runningActions.firstOrNull { it.runId == runId } ?: return
        handledTerminalRunId = runId
        activeRunId = runId
        rememberLastProject(run.projectId)
        docks = docks.withTerminalExclusive(
            placement = lastTerminalPlacement,
            runId = runId,
            newTabId = nextPaneId("terminal-tab"),
            newLeafId = nextPaneId("leaf"),
        )
    }

    fun pruneDockTerminalTabs(runningActions: List<RunningAction>) {
        val alive = runningActions.mapTo(mutableSetOf()) { it.runId }
        val nextRight = docks.right.withoutTerminalRuns(alive)
        val nextBottom = docks.bottom.withoutTerminalRuns(alive)
        if (nextRight == docks.right && nextBottom == docks.bottom) return
        docks = docks.copy(right = nextRight, bottom = nextBottom)
    }

    suspend fun refreshDevicesNow(): List<AndroidDevice> {
        val previousOnlineAndroid = devices
            .filter { it.state == DeviceConnectionState.Online }
            .mapTo(mutableSetOf()) { it.serial }
        sdk = services.devices.discoverSdk()
        devices = services.devices.listDevices()
        iosTargets = services.iosDevices.listTargets()
        IosTargetRegistry.update(iosTargets)
        if (!startupSelectionResolved) {
            startupSelectionResolved = true
            val saved = startupTargetId
            startupTargetId = null
            when {
                saved != null && isTargetAvailable(saved) -> applyTarget(saved)
                else -> {
                    selectedIosUdid = null
                    selectedSerial = defaultOnlineAndroidSerial()
                }
            }
            persistSelectedTarget()
            return devices
        }

        val dhuSession = services.dhu.session.value
        val pinnedForDhu = dhuSession?.serial?.takeIf {
            dhuSession.phase == DhuSessionPhase.Starting ||
                dhuSession.phase == DhuSessionPhase.Running ||
                dhuSession.phase == DhuSessionPhase.Stopping
        }
        val reconciled = reconcileDeviceSelection(
            previousOnlineAndroidSerials = previousOnlineAndroid,
            devices = devices,
            iosTargets = iosTargets,
            selectedSerial = selectedSerial,
            selectedIosUdid = selectedIosUdid,
            pinnedAndroidSerial = pinnedForDhu,
        )
        if (reconciled.selectedSerial != selectedSerial || reconciled.selectedIosUdid != selectedIosUdid) {
            if (reconciled.selectedSerial != selectedSerial) foldableHingeAngle = 180f
            selectedSerial = reconciled.selectedSerial
            selectedIosUdid = reconciled.selectedIosUdid
            // Selection only moves off a DHU serial once the session is no longer pinned above
            // (failed/stopped), so the intent is stale — drop it instead of restarting AA on the
            // device we just landed on.
            (reconciled.selectedIosUdid ?: reconciled.selectedSerial)
                ?.let { clearAndroidAutoForTargetSwitch(it) }
            persistSelectedTarget()
        }
        return devices
    }

    fun refreshDevices() {
        scope.launch {
            refreshDevicesNow()
            refreshActionsConfigNow()
        }
    }

    /** Re-reads global + repo action configs so newly added project actions appear. */
    private suspend fun refreshActionsConfigNow() {
        if (!services.capabilities.hostAutomation) return
        // While SSH-remoted, projects come from remoteActionsConfig — never clobber with local.
        if (services.remoteSession.isRemote) {
            services.remoteSession.remoteActionsConfig.value?.let { actionsConfig = it }
            return
        }
        runCatching { actionsConfig = services.actionConfig.load() }
    }

    fun stopEmulator(device: AndroidDevice) {
        if (device.kind != DeviceKind.Emulator || device.state != DeviceConnectionState.Online) return
        scope.launch {
            stoppingEmulatorSerial = device.serial
            services.mirror.disconnect(immediate = true)
            val result = services.avd.stopVirtualDevice(device.displayName)
            emulatorStopStatus = if (result.isSuccess) {
                result.stdout.ifBlank { "Stopped ${device.displayName}" }
            } else {
                result.stderr.ifBlank { result.stdout }
            }
            val refreshed = refreshDevicesNow()
            if (result.isSuccess && selectedSerial == device.serial) {
                selectDevice(
                    refreshed.firstOrNull {
                        it.serial != device.serial && it.state == DeviceConnectionState.Online
                    }?.serial,
                )
            }
            stoppingEmulatorSerial = null
        }
    }

    fun openStartedEmulator(previousSerials: Set<String>, avdName: String) {
        scope.launch {
            startingEmulatorName = avdName
            emulatorStartStatus = "Starting $avdName..."
            repeat(90) {
                val currentDevices = refreshDevicesNow()
                val started = currentDevices.firstOrNull {
                    it.kind == DeviceKind.Emulator &&
                        it.state == DeviceConnectionState.Online &&
                        it.serial !in previousSerials
                }
                if (started != null) {
                    emulatorStartStatus = "${started.displayName} online — waiting for boot…"
                    // adb Online is not boot_completed. Opening Live too early leaves a black,
                    // too-wide mirror until a manual reconnect.
                    val booted = awaitDeviceBootCompleted(started.serial)
                    if (booted) {
                        applyAvdInitialOrientation(avdName, started.serial)
                    }
                    val refreshed = refreshDevicesNow()
                    val ready = refreshed.firstOrNull { it.serial == started.serial } ?: started
                    selectDevice(ready.serial)
                    destination = AndyDestination.Live
                    emulatorStartStatus = if (booted) {
                        "${ready.displayName} is ready"
                    } else {
                        "${ready.displayName} is online (boot still finishing)"
                    }
                    startingEmulatorName = null
                    return@launch
                }
                emulatorStartStatus = "Starting $avdName... waiting for boot (${it + 1}/90)"
                delay(1_000)
            }
            emulatorStartStatus = "$avdName is still starting. Refresh devices when it finishes booting."
            startingEmulatorName = null
        }
    }

    private suspend fun awaitDeviceBootCompleted(serial: String, attempts: Int = 120): Boolean {
        repeat(attempts) {
            val result = runCatching {
                services.devices.shell(serial, listOf("getprop", "sys.boot_completed"))
            }.getOrNull()
            if (result?.stdout?.trim() == "1") {
                val size = runCatching {
                    services.devices.shell(serial, listOf("wm", "size"))
                }.getOrNull()
                if (size?.isSuccess == true && size.stdout.contains(Regex("""\d+x\d+"""))) {
                    return true
                }
            }
            delay(500)
        }
        return false
    }

    /**
     * Landscape AVDs store `hw.initialOrientation=landscape`, but Andy's hidden emulator
     * window often still boots a portrait framebuffer. Rotate via the emulator console so
     * Live opens with the requested display frame.
     */
    private suspend fun applyAvdInitialOrientation(avdName: String, serial: String) {
        val orientation = runCatching {
            services.avd.listVirtualDevices()
                .firstOrNull { namesMatchAvd(it.name, avdName) }
                ?.config
                ?.get("hw.initialOrientation")
        }.getOrNull() ?: return
        if (!orientation.equals("landscape", ignoreCase = true)) return
        emulatorStartStatus = "Orienting $avdName to landscape…"
        runCatching {
            services.devices.ensureEmulatorOrientation(serial, orientation)
        }
    }

    private fun namesMatchAvd(left: String, right: String): Boolean =
        left.replace('_', ' ').trim().equals(right.replace('_', ' ').trim(), ignoreCase = true)

    suspend fun initialize() {
        val saved = services.workspaceStore.load()
        workspaceState = saved
        startupTargetId = saved.selectedDeviceSerial
        if (services.capabilities.hostAutomation) {
            actionsConfig = services.actionConfig.load()
        }
        workspaceLoaded = true
        // While SSH-remoted, projects come from the remote ~/.andy/actions.toml (chats already
        // come from remote andyd). Swap back to local config on disconnect.
        scope.launch {
            services.remoteSession.remoteActionsConfig.collect { remote ->
                if (!services.capabilities.hostAutomation) return@collect
                actionsConfig = remote ?: runCatching { services.actionConfig.load() }.getOrDefault(ActionsConfig())
            }
        }
        if (services.capabilities.wifiPairing && saved.pairedWifiDevices.isNotEmpty()) {
            // Reconnect in the background so workspace load / first device refresh are not blocked.
            scope.launch {
                val discovery = services.devices.discoverSdk()
                if (!discovery.hasAdb) return@launch
                val mdnsReady = runCatching { services.devices.mdnsAvailable() }.getOrDefault(false)
                val mdnsServices = if (mdnsReady) {
                    runCatching { services.devices.listMdnsServices() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                saved.pairedWifiDevices.forEach { paired ->
                    launch {
                        runCatching {
                            withTimeout(8_000) {
                                val (result, endpoint) = reconnectPairedWifiDevice(services.devices, paired, mdnsServices)
                                if (result.isSuccess && endpoint != null && endpoint != paired.lastEndpoint) {
                                    updateWorkspace { state ->
                                        state.copy(
                                            pairedWifiDevices = state.pairedWifiDevices.map {
                                                if (it.id == paired.id) it.copy(lastEndpoint = endpoint) else it
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        refreshDevicesNow()
                    }
                }
            }
        }
        refreshDevices()
        scope.launch {
            services.devices.observeDevicePresence()
                .debounce(250)
                .collect {
                    refreshDevicesNow()
                }
        }
    }

    fun savePairedWifi(device: PairedWifiDevice) {
        updateWorkspace { state ->
            val without = state.pairedWifiDevices.filterNot {
                it.id == device.id ||
                    (device.mdnsInstanceName != null && it.mdnsInstanceName == device.mdnsInstanceName) ||
                    (device.lastEndpoint != null && it.lastEndpoint == device.lastEndpoint)
            }
            state.copy(pairedWifiDevices = without + device)
        }
    }

    fun forgetPairedWifi(id: String) {
        updateWorkspace { state ->
            state.copy(pairedWifiDevices = state.pairedWifiDevices.filterNot { it.id == id })
        }
    }

    fun reconnectPairedWifi(paired: PairedWifiDevice) {
        scope.launch {
            val (result, endpoint) = reconnectPairedWifiDevice(services.devices, paired)
            if (result.isSuccess && endpoint != null && endpoint != paired.lastEndpoint) {
                updateWorkspace { state ->
                    state.copy(
                        pairedWifiDevices = state.pairedWifiDevices.map {
                            if (it.id == paired.id) it.copy(lastEndpoint = endpoint) else it
                        },
                    )
                }
            }
            refreshDevicesNow()
        }
    }

    fun disconnectWifi(serial: String) {
        scope.launch {
            services.devices.disconnect(serial)
            refreshDevicesNow()
        }
    }

    fun syncActiveRun(runningActions: List<RunningAction>) {
        if (activeRunId == null || runningActions.none { it.runId == activeRunId }) {
            activeRunId = runningActions.lastOrNull()?.runId
        }
    }

    fun updateWorkspace(transform: (WorkspaceState) -> WorkspaceState) {
        val updated = transform(workspaceState).copy(selectedDeviceSerial = activeTargetId)
        workspaceState = updated
        scope.launch { services.workspaceStore.save(updated) }
    }

    fun persistActionsConfig(next: ActionsConfig) {
        actionsConfig = next
        scope.launch {
            if (services.remoteSession.isRemote) {
                services.remoteSession.saveRemoteActionsConfig(next)
            } else {
                services.actionConfig.save(next)
            }
        }
    }

    fun openLive(serial: String) {
        if (iosTargets.any { it.udid == serial }) {
            selectIosTarget(serial)
        } else {
            selectDevice(serial)
        }
        destination = AndyDestination.Live
    }

    fun runAction(project: ActionProject, action: ProjectAction) {
        updateWorkspace {
            it.copy(
                lastActionProjectId = project.id,
                lastActionId = action.id,
                lastActionIdsByProject = it.lastActionIdsByProject + (project.id to action.id),
            )
        }
        val runId = services.actionRuns.run(project, action)
        // Always reveal — re-runs of a still-active action reuse the same runId, so setting
        // terminalRunId alone won't re-fire consumeTerminalRun after the user closed the tab.
        // Stay on the current destination; only surface the run in the terminal dock.
        focusTerminalRun(runId)
    }

    fun rememberActionSelection(projectId: String, actionId: String?) {
        val resolvedActionId = resolveLastActionId(projectId, actionId)
        if (
            workspaceState.lastActionProjectId == projectId &&
            workspaceState.lastActionId == resolvedActionId
        ) {
            return
        }
        updateWorkspace {
            val rememberedMap = if (actionId != null && resolvedActionId != null) {
                it.lastActionIdsByProject + (projectId to resolvedActionId)
            } else {
                it.lastActionIdsByProject
            }
            it.copy(
                lastActionProjectId = projectId,
                lastActionId = resolvedActionId,
                lastActionIdsByProject = rememberedMap,
            )
        }
    }

    fun rememberLastProject(projectId: String) {
        val resolvedActionId = resolveLastActionId(projectId, actionId = null)
        if (
            workspaceState.lastActionProjectId == projectId &&
            workspaceState.lastActionId == resolvedActionId
        ) {
            return
        }
        updateWorkspace {
            it.copy(
                lastActionProjectId = projectId,
                lastActionId = resolvedActionId,
            )
        }
    }

    private fun resolveLastActionId(projectId: String, actionId: String?): String? {
        val projectActions = actionsConfig.projects.firstOrNull { it.id == projectId }?.actions.orEmpty()
        if (projectActions.isEmpty()) return null
        actionId?.takeIf { id -> projectActions.any { it.id == id } }?.let { return it }
        workspaceState.lastActionIdsByProject[projectId]
            ?.takeIf { id -> projectActions.any { it.id == id } }
            ?.let { return it }
        return projectActions.firstOrNull()?.id
    }

    fun stopAction(run: RunningAction) {
        services.actionRuns.stop(run.runId)
        activeRunId = run.runId
    }
}

@Composable
internal fun rememberShellState(services: AndyServices): ShellState {
    val scope = rememberCoroutineScope()
    return remember(services) { ShellState(services, scope) }
}
