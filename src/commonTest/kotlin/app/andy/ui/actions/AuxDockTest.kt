package app.andy.ui.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuxDockTest {
    @Test
    fun toggleOpensAndClosesSlot() {
        var docks = AuxDocks()
        docks = docks.toggle(DockPlacement.Right, DockKind.Terminal)
        assertEquals(DockKind.Terminal, docks.right)
        docks = docks.toggle(DockPlacement.Right, DockKind.Terminal)
        assertNull(docks.right)
    }

    @Test
    fun liveRequestedAtBottomDocksOnRight() {
        val docks = AuxDocks().show(DockPlacement.Bottom, DockKind.Live)
        assertNull(docks.bottom)
        assertEquals(DockKind.Live, docks.right)
    }

    @Test
    fun terminalAndLiveCanShareOppositePlacements() {
        val docks = AuxDocks()
            .show(DockPlacement.Right, DockKind.Live)
            .show(DockPlacement.Bottom, DockKind.Terminal)
        assertEquals(DockKind.Live, docks.right)
        assertEquals(DockKind.Terminal, docks.bottom)
    }

    @Test
    fun replacingKindInSameSlotClearsPrevious() {
        val docks = AuxDocks()
            .show(DockPlacement.Right, DockKind.Terminal)
            .show(DockPlacement.Right, DockKind.Live)
        assertEquals(DockKind.Live, docks.right)
        assertNull(docks.bottom)
    }
}
