package app.andy.ui.shell

import app.andy.service.RemoteSessionState
import app.andy.service.RemoteSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteSessionSidebarStateTest {
    @Test
    fun localSessionReportsLocalPhase() {
        val session = RemoteSessionState()
        val phase = hostPhaseOf(session, busy = false)
        assertEquals(HostPhase.Local, phase)
        assertEquals("Local", hostHeaderDetail(session, phase))
    }

    @Test
    fun connectedSessionNamesTheHost() {
        val session = RemoteSessionState(
            status = RemoteSessionStatus.Connected,
            target = "ada@garden-box",
        )
        val phase = hostPhaseOf(session, busy = false)
        assertEquals(HostPhase.Connected, phase)
        assertEquals("ada@garden-box", hostHeaderDetail(session, phase))
        assertEquals("Connected", hostStatusLabel(session, phase))
    }

    @Test
    fun localSwitchInFlightReadsAsSwitching() {
        val session = RemoteSessionState(status = RemoteSessionStatus.Local)
        val phase = hostPhaseOf(session, busy = true)
        assertEquals(HostPhase.Switching, phase)
        assertEquals("Switching…", hostHeaderDetail(session, phase))
    }

    @Test
    fun failedConnectSurfacesFailure() {
        val session = RemoteSessionState(
            status = RemoteSessionStatus.Error,
            target = "ada@build-rack",
            error = "ssh: connect to host build-rack port 22: Operation timed out",
        )
        val phase = hostPhaseOf(session, busy = false)
        assertEquals(HostPhase.Failed, phase)
        assertEquals("Not connected", hostHeaderDetail(session, phase))
        assertEquals("Connection failed", hostStatusLabel(session, phase))
    }

    @Test
    fun droppedTunnelKeepsTheReasonVisibleFromLocal() {
        // DesktopRemoteSessionService reports a dropped tunnel as Local + error.
        val session = RemoteSessionState(
            status = RemoteSessionStatus.Local,
            error = "SSH tunnel to ada@garden-box dropped. Reconnect to continue remotely.",
        )
        val phase = hostPhaseOf(session, busy = false)
        assertEquals(HostPhase.Failed, phase)
        assertEquals("Local", hostHeaderDetail(session, phase))
        assertEquals("Remote disconnected", hostStatusLabel(session, phase))
    }
}
