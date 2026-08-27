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
        assertEquals(3_000L, RESOURCE_EXHAUSTED_AUTO_RETRY_BACKOFF_MS)
        assertEquals("Continue where you left off.", CONNECTION_STALL_RETRY_PROMPT)
        assertTrue(CONNECTION_STALL_RETRY_PROMPT.isSilentConnectionRecoveryPrompt())
        assertFalse("keep going".isSilentConnectionRecoveryPrompt())
    }

    @Test
    fun detectsCursorProviderStallMessages() {
        assertTrue("Error: RetriableError: Connection stalled".isRetriableConnectionStallMessage())
        assertTrue("RetriableError: Connection stalled repeatedly".isRetriableConnectionStallMessage())
        assertTrue(
            "\n\nError: RetriableError: Connection stalled\n".isRetriableConnectionStallMessage(),
        )
        assertTrue(
            "Error: RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)"
                .isRetriableConnectionStallMessage(),
        )
        assertTrue(
            "RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)"
                .isRetriableConnectionStallMessage(),
        )
        assertTrue("Error: RetriableError: [resource_exhausted] Error".isRetriableConnectionStallMessage())
        assertTrue("RetriableError: [resource_exhausted] Error".isRetriableConnectionStallMessage())
        assertTrue(
            "\n\nError: RetriableError: [resource_exhausted] Error\n".isRetriableConnectionStallMessage(),
        )
    }

    @Test
    fun ignoresMentionsAndUnrelatedErrors() {
        assertFalse("connection stalled".isRetriableConnectionStallMessage())
        assertFalse("http/2 stream closed".isRetriableConnectionStallMessage())
        assertFalse(" stalled".isRetriableConnectionStallMessage())
        assertFalse(
            "connection stalled is a known, named failure mode".isRetriableConnectionStallMessage(),
        )
        assertFalse(
            "If you see literal `connection stalled` or `http/2 stream closed`, that's the provider"
                .isRetriableConnectionStallMessage(),
        )
        assertFalse("Error: ENOENT: no such file".isRetriableConnectionStallMessage())
        assertFalse("ACP prompt failed".isRetriableConnectionStallMessage())
        assertFalse("resource_exhausted".isRetriableConnectionStallMessage())
        assertFalse(
            "RetriableError: [resource_exhausted] is what Cursor showed last night"
                .isRetriableConnectionStallMessage(),
        )
        assertFalse(
            """
            Andy auto-retries by sending Continue where you left off.
            The regex matches connection stalled as a substring, which is the bug.
            """.trimIndent().hasRetriableConnectionStallError(),
        )
        assertFalse(
            """
            The error looks like:
                Error: RetriableError: Connection stalled
            """.trimIndent().hasRetriableConnectionStallError(),
        )
        assertFalse(
            "Here is the quoted form:\n> Error: RetriableError: Connection stalled"
                .hasRetriableConnectionStallError(),
        )
        val indentedQuote = """
            The error looks like:
                Error: RetriableError: Connection stalled
        """.trimIndent()
        assertEquals(indentedQuote, indentedQuote.stripTrailingConnectionStallError())
    }

    @Test
    fun trailingStallLineCountsAsTransportDrop() {
        val partial = "Here is the patch.\n\nError: RetriableError: Connection stalled"
        assertFalse(partial.isRetriableConnectionStallMessage())
        assertTrue(partial.hasRetriableConnectionStallError())
        assertEquals("Here is the patch.", partial.stripTrailingConnectionStallError())
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
    fun coalescesStreamDeltasBeforeDetectingStalls() {
        val events = listOf(
            AgentEvent.AssistantText(1, "Error: RetriableError: ", isStreamDelta = true),
            AgentEvent.AssistantText(2, "Connection stalled", isStreamDelta = true),
        )
        assertTrue(events.hasRetriableConnectionStall())
        assertFalse(
            listOf(AgentEvent.AssistantText(1, " stalled", isStreamDelta = true))
                .hasRetriableConnectionStall(),
        )
    }

    @Test
    fun mentionsInAssistantTextDoNotCountAsStalls() {
        val events = listOf(
            AgentEvent.UserMessage(1, "why am I getting connection stalled situations?"),
            AgentEvent.AssistantText(
                2,
                "connection stalled is a known failure mode. Andy matches `http/2 stream closed`.",
            ),
        )
        assertFalse(events.hasRetriableConnectionStall())
        assertFalse(shouldShowConnectionStallBanner(events, isActive = false))
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

    @Test
    fun resourceExhaustedCountsAsRetryableStall() {
        val events = listOf(
            AgentEvent.ToolCall(1, "read", "gradle"),
            AgentEvent.AssistantText(2, "Error: RetriableError: [resource_exhausted] Error"),
        )
        assertTrue(events.hasRetriableConnectionStall())
        assertTrue(events.hasRetriableResourceExhausted())
        assertTrue(shouldShowConnectionStallBanner(events, isActive = false))
        assertFalse(shouldShowConnectionStallBanner(events, isActive = true))
    }

    @Test
    fun ordinaryStallsAreNotResourceExhausted() {
        val events = listOf(
            AgentEvent.AssistantText(1, "Error: RetriableError: Connection stalled"),
        )
        assertTrue(events.hasRetriableConnectionStall())
        assertFalse(events.hasRetriableResourceExhausted())
    }
}
