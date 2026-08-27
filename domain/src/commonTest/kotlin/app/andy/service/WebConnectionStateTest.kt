package app.andy.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebConnectionStateTest {
    @Test
    fun automaticWebSocketConnectionOnlyRunsBeforeAUserChoosesATransport() {
        assertTrue(WebConnectionState().shouldAutoConnectWebSocket())
        assertFalse(
            WebConnectionState(
                transport = WebConnectionTransport.WebUsb,
                status = "Connection failed",
                error = "Device is in use",
            ).shouldAutoConnectWebSocket(),
        )
        assertFalse(
            WebConnectionState(
                transport = WebConnectionTransport.WebSocket,
                status = "Connection failed",
            ).shouldAutoConnectWebSocket(),
        )
    }
}
