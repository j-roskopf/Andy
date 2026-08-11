package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConnectionRecoveryTest {
    @Test
    fun autoRetryPolicyAllowsTwoBackoffAttempts() {
        assertEquals(2, MAX_CONNECTION_STALL_AUTO_RETRIES)
        assertEquals(1_000L, CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS)
        assertEquals("Continue where you left off.", CONNECTION_STALL_RETRY_PROMPT)
    }

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

    @Test
    fun stallDetectionIgnoresPriorTurnsAfterContinue() {
        val events = listOf(
            AgentEvent.UserMessage(1, "do the thing"),
            AgentEvent.AssistantText(2, "Error: RetriableError: Connection stalled"),
            AgentEvent.UserMessage(3, CONNECTION_STALL_RETRY_PROMPT),
            AgentEvent.AssistantText(4, "Picking up again."),
        )
        assertFalse(events.hasRetriableConnectionStall())
        assertFalse(shouldShowConnectionStallBanner(events, isActive = false))
    }

    @Test
    fun stallDetectionSeesRetryTurnFailure() {
        val events = listOf(
            AgentEvent.UserMessage(1, "do the thing"),
            AgentEvent.AssistantText(2, "Error: RetriableError: Connection stalled"),
            AgentEvent.UserMessage(3, CONNECTION_STALL_RETRY_PROMPT),
            AgentEvent.AssistantText(4, "Error: RetriableError: Connection stalled"),
        )
        assertTrue(events.hasRetriableConnectionStall())
        assertTrue(shouldShowConnectionStallBanner(events, isActive = false))
    }
}
