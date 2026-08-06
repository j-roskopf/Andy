package app.andy.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConnectionRecoveryTest {
    @Test
    fun detectsCursorProviderStallMessages() {
        assertTrue("Error: RetriableError: Connection stalled".isRetriableConnectionStallMessage())
        assertTrue("RetriableError: Connection stalled repeatedly".isRetriableConnectionStallMessage())
        assertTrue("connection stalled".isRetriableConnectionStallMessage())
        assertTrue(
            "Error: RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)"
                .isRetriableConnectionStallMessage(),
        )
        assertTrue(
            "RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)"
                .isRetriableConnectionStallMessage(),
        )
    }

    @Test
    fun ignoresUnrelatedErrors() {
        assertFalse("Error: ENOENT: no such file".isRetriableConnectionStallMessage())
        assertFalse("ACP prompt failed".isRetriableConnectionStallMessage())
    }

    @Test
    fun scansTranscriptEventsForStalls() {
        val events = listOf(
            AgentEvent.ToolCall(1, "read", "gradle"),
            AgentEvent.AssistantText(2, "Error: RetriableError: Connection stalled"),
        )
        assertTrue(events.hasRetriableConnectionStall())
        assertTrue(shouldShowConnectionStallBanner(events, isActive = false))
        assertFalse(shouldShowConnectionStallBanner(events, isActive = true))
    }
}
