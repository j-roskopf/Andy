package app.andy.ui.shell

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ActionsConfig
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import app.andy.model.RunningAction
import app.andy.model.SavedDockLayout
import app.andy.model.SavedDockPane
import app.andy.model.SavedDockTab
import app.andy.model.SavedDockTabKind
import app.andy.model.SavedTerminalNode
import app.andy.model.SavedTerminalSession
import app.andy.service.ActionRunService
import app.andy.service.IosDeviceService
import app.andy.service.PlatformCapabilities
import app.andy.service.UnavailableActionRunService
import app.andy.service.UnavailableIosDeviceService
import app.andy.service.createUnavailableAndyServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellStateLayoutTest {
    private fun runningAction(runId: String, projectId: String = "p1") = RunningAction(
        runId = runId,
        projectId = projectId,
        actionId = "shell",
        actionName = "Terminal",
        icon = "terminal",
        command = "bash",
        cwd = "/tmp",
        status = ActionRunStatus.Running,
        startedAtMillis = 1000L,
    )

    @Test
    fun loadDockLayoutClearsTerminalRunStateWhenRestoredLayoutHasNoFocusedTerminal() {
        val baseServices = createUnavailableAndyServices()
        val scope = CoroutineScope(EmptyCoroutineContext)
        val state = ShellState(baseServices, scope)

        // Initial state: terminal tab is open and tracked
        state.focusTerminalRun("run-old", DockPlacement.Right)
        assertEquals("run-old", state.terminalRunId)
        assertEquals("run-old", state.activeRunId)

        // Saved layout has only Logs (no Terminal)
        val logsLayout = SavedDockLayout(
            id = "layout-logs",
            name = "Logs Only",
            bottom = SavedDockPane(
                tabs = listOf(SavedDockTab(kind = SavedDockTabKind.Logs)),
                activeTabIndex = 0,
                visible = true,
            ),
        )
        state.updateWorkspace { it.copy(savedDockLayouts = listOf(logsLayout)) }

        // Load the layout
        state.loadDockLayout("layout-logs")

        // Terminal run state must be cleared
        assertNull(state.terminalRunId)
        assertNull(state.activeRunId)

        // When a running action updates (e.g. from AndyShell), stale terminal run must not be revealed
        state.consumeTerminalRun(listOf(runningAction("run-old")))

        // Verify docks contain only the restored logs tab and no terminal tab was re-opened
        assertTrue(state.docks.right.tabs.isEmpty())
        assertEquals(listOf(DockTabKind.Logs), state.docks.bottom.tabs.map { it.kind })
    }

    @Test
    fun loadDockLayoutRetainsFocusedTerminalWhenRestored() {
        val baseServices = createUnavailableAndyServices()
        val services = baseServices.copy(
            capabilities = PlatformCapabilities.Desktop,
            actionRuns = object : ActionRunService by UnavailableActionRunService {
                override fun openShell(project: ActionProject): String = "run-restored"
            },
        )
        val scope = CoroutineScope(EmptyCoroutineContext)
        val state = ShellState(services, scope)
        state.persistActionsConfig(
            ActionsConfig(projects = listOf(ActionProject(id = "p1", name = "Project 1", contextDir = "/tmp"))),
        )

        // Prior run before layout load
        state.focusTerminalRun("run-prior", DockPlacement.Right)

        // Layout with a terminal session
        val terminalLayout = SavedDockLayout(
            id = "layout-term",
            name = "Terminal Only",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Leaf(
                            sessions = listOf(SavedTerminalSession(projectId = "p1", title = "Shell")),
                            activeSessionIndex = 0,
                        ),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )
        state.updateWorkspace { it.copy(savedDockLayouts = listOf(terminalLayout)) }

        state.loadDockLayout("layout-term")

        // Focused run must be updated to the fresh run, not prior
        assertEquals("run-restored", state.terminalRunId)
        assertEquals("run-restored", state.activeRunId)
    }

    @Test
    fun saveDockLayoutAllowsOverwriteAtLimit() {
        val state = ShellState(createUnavailableAndyServices(), CoroutineScope(EmptyCoroutineContext))

        // Populate with 20 layouts (maximum limit)
        val initialLayouts = (1..20).map { i ->
            SavedDockLayout(
                id = "layout-$i",
                name = "Layout $i",
                savedAtMillis = 1000L + i,
            )
        }
        state.updateWorkspace { it.copy(savedDockLayouts = initialLayouts) }
        assertTrue(state.savedLayoutLimitReached)

        // Open a tab so canSaveDockLayout is true
        state.openDockKind(DockPlacement.Right, DockTabKind.Logs)
        assertTrue(state.canSaveDockLayout)

        // Attempting to save a 21st distinct layout must be rejected (no-op)
        state.saveDockLayout("Layout 21")
        assertEquals(20, state.savedLayouts.size)
        assertNull(state.savedLayouts.firstOrNull { it.name == "Layout 21" })

        // Overwriting an existing layout by name at the limit must succeed in place
        state.saveDockLayout("Layout 1")
        assertEquals(20, state.savedLayouts.size)
        val overwritten = state.savedLayouts.first()
        assertEquals("layout-1", overwritten.id)
        assertEquals("Layout 1", overwritten.name)
        assertEquals(1, overwritten.right.tabs.size)
        assertEquals(SavedDockTabKind.Logs, overwritten.right.tabs.first().kind)

        // Case-insensitive overwrite also works at the limit
        state.saveDockLayout("layout 2")
        assertEquals(20, state.savedLayouts.size)
        val overwritten2 = state.savedLayouts.first { it.id == "layout-2" }
        assertEquals("layout 2", overwritten2.name)
    }

    @Test
    fun saveDockLayoutRequiresCanSave() {
        val state = ShellState(createUnavailableAndyServices(), CoroutineScope(EmptyCoroutineContext))
        assertFalse(state.canSaveDockLayout)

        state.saveDockLayout("Layout 1")
        assertTrue(state.savedLayouts.isEmpty())
    }

    @Test
    fun splitLiveLeafAndFocusUnboundClearsTargetIdAndTitle() {
        val state = ShellState(createUnavailableAndyServices(), CoroutineScope(EmptyCoroutineContext))
        state.updateWorkspace { it.copy(deviceLabels = mapOf("device-1" to "Pixel 8")) }

        state.openNewLiveTab(DockPlacement.Right, seedWithActiveTarget = false)
        val tab = state.docks.right.tabs.single()
        val initialLeafId = tab.liveTree!!.firstLeafId()

        state.setLiveTabTarget(tab.id, "device-1", initialLeafId)
        val boundTab = state.docks.right.tabs.single()
        assertEquals("device-1", boundTab.targetId)
        assertEquals("Pixel 8", boundTab.title)

        state.splitLiveLeaf(DockPlacement.Right, tab.id, initialLeafId, SplitAxis.Row)
        val splitTab = state.docks.right.tabs.single()

        val focusedLeafId = splitTab.focusedLiveLeafId
        assertNotNull(focusedLeafId)
        val focusedLeaf = splitTab.liveTree!!.findLeaf(focusedLeafId)
        assertNotNull(focusedLeaf)
        assertNull(focusedLeaf.targetId)
        assertNull(focusedLeaf.title)

        assertNull(splitTab.targetId)
        assertNull(splitTab.title)

        state.focusLiveLeaf(DockPlacement.Right, tab.id, initialLeafId)
        val refocusedTab = state.docks.right.tabs.single()
        assertEquals("device-1", refocusedTab.targetId)
        assertEquals("Pixel 8", refocusedTab.title)

        state.focusLiveLeaf(DockPlacement.Right, tab.id, focusedLeafId)
        val unboundRefocusedTab = state.docks.right.tabs.single()
        assertNull(unboundRefocusedTab.targetId)
        assertNull(unboundRefocusedTab.title)
    }

    @Test
    fun pausedLiveTargetReleasesPoolHoldAndRestoresOnUnpause() {
        val state = ShellState(createUnavailableAndyServices(), CoroutineScope(EmptyCoroutineContext))
        val held = mutableListOf<String>()
        val released = mutableListOf<String>()
        state.onLiveMirrorHold = { held.add(it) }
        state.onLiveMirrorRelease = { released.add(it) }

        state.openNewLiveTab(DockPlacement.Right, seedWithActiveTarget = false)
        val tab = state.docks.right.tabs.single()
        val leafId = tab.liveTree!!.firstLeafId()
        state.setLiveTabTarget(tab.id, "device-1", leafId)
        assertEquals(listOf("device-1"), held)
        assertTrue(released.isEmpty())

        // Pausing (main Live or pop-out takes over) must drop the pooled engine hold.
        state.setPausedLiveTargetIds(setOf("device-1"))
        assertEquals(listOf("device-1"), released)

        // Unpausing must restore the hold.
        state.setPausedLiveTargetIds(emptySet())
        assertEquals(listOf("device-1", "device-1"), held)
        assertEquals(listOf("device-1"), released)
    }

    @Test
    fun setLiveTabTargetRejectsSecondSameKindIosTarget() = runBlocking {
        val iosDevices = object : IosDeviceService by UnavailableIosDeviceService {
            override suspend fun listTargets() = listOf(
                IosTarget(udid = "sim-1", displayName = "Sim 1", kind = IosTargetKind.Simulator, state = IosTargetState.Booted),
                IosTarget(udid = "sim-2", displayName = "Sim 2", kind = IosTargetKind.Simulator, state = IosTargetState.Booted),
                IosTarget(
                    udid = "dev-1", displayName = "iPhone", kind = IosTargetKind.Physical,
                    state = IosTargetState.Unknown, transport = IosTransport.Usb,
                ),
            )
        }
        val services = createUnavailableAndyServices().copy(iosDevices = iosDevices)
        val state = ShellState(services, CoroutineScope(EmptyCoroutineContext))
        state.refreshDevicesNow()

        state.openNewLiveTab(DockPlacement.Right, seedWithActiveTarget = false)
        val tab = state.docks.right.tabs.single()
        val leaf1 = tab.liveTree!!.firstLeafId()
        state.setLiveTabTarget(tab.id, "sim-1", leaf1)

        state.splitLiveLeaf(DockPlacement.Right, tab.id, leaf1, SplitAxis.Row)
        val tabId = state.docks.right.tabs.single().id
        val leaf2 = state.docks.right.tabs.single().focusedLiveLeafId!!

        // A second simulator (same native decoder slot) must be rejected.
        state.setLiveTabTarget(tabId, "sim-2", leaf2)
        assertNull(state.docks.right.tabs.single().liveTree!!.findLeaf(leaf2)!!.targetId)

        // A physical device (different kind) is allowed alongside the simulator.
        state.setLiveTabTarget(tabId, "dev-1", leaf2)
        assertEquals("dev-1", state.docks.right.tabs.single().liveTree!!.findLeaf(leaf2)!!.targetId)
    }

    @Test
    fun dockLayoutMenuPermitsSaveActionAtCapacityLimitForOverwrite() {
        val state = ShellState(createUnavailableAndyServices(), CoroutineScope(EmptyCoroutineContext))

        val initialLayouts = (1..20).map { i ->
            SavedDockLayout(
                id = "layout-$i",
                name = "Layout $i",
                savedAtMillis = 1000L + i,
            )
        }
        state.updateWorkspace { it.copy(savedDockLayouts = initialLayouts) }
        state.openDockKind(DockPlacement.Right, DockTabKind.Logs)
        assertTrue(state.canSaveDockLayout)
        assertTrue(state.savedLayoutLimitReached)

        val menu = DockLayoutMenu(
            layouts = state.savedLayouts,
            canSave = state.canSaveDockLayout,
            atLimit = state.savedLayoutLimitReached,
            defaultName = state::defaultDockLayoutName,
            onSave = state::saveDockLayout,
            onLoad = state::loadDockLayout,
            onDelete = state::deleteDockLayout,
        )
        assertTrue(menu.canSave)
        assertTrue(menu.atLimit)
        assertTrue(menu.isSaveActionEnabled)

        val underLimitMenu = menu.copy(atLimit = false)
        assertTrue(underLimitMenu.isSaveActionEnabled)

        val emptyDocksMenu = menu.copy(canSave = false, atLimit = false)
        assertFalse(emptyDocksMenu.isSaveActionEnabled)
    }
}
