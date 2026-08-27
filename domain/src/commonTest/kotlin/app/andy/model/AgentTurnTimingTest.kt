package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTurnTimingTest {
    @Test
    fun durationUsesCurrentTurnUserMessage() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1_000, text = "first"),
            AgentEvent.TaskResult(atMillis = 4_000, success = true, finalText = null, durationMs = 3_000),
            AgentEvent.UserMessage(atMillis = 10_000, text = "follow-up"),
            AgentEvent.AssistantText(atMillis = 11_000, text = "working"),
        )
        assertEquals(5_000, turnWorkedDurationMs(events, startedAtMillis = 1_000, finishedAtMillis = 15_000))
    }

    @Test
    fun durationFallsBackToStartedAtWhenThereIsNoUserMessage() {
        assertEquals(8_000, turnWorkedDurationMs(emptyList(), startedAtMillis = 2_000, finishedAtMillis = 10_000))
        assertNull(turnWorkedDurationMs(emptyList(), startedAtMillis = null, finishedAtMillis = 10_000))
        assertNull(turnWorkedDurationMs(emptyList(), startedAtMillis = 2_000, finishedAtMillis = null))
    }

    @Test
    fun turnCompletionResultIsSkippedWhenAResultIsAlreadyLast() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "hi"),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = null, durationMs = 1),
        )
        assertNull(
            turnCompletionResult(
                events = events,
                startedAtMillis = 1,
                finishedAtMillis = 3,
                success = true,
            ),
        )
    }

    @Test
    fun turnCompletionResultRecordsDurationFromTheLastUserMessage() {
        val events = listOf(AgentEvent.UserMessage(atMillis = 1_000, text = "hi"))
        val result = turnCompletionResult(
            events = events,
            startedAtMillis = 500,
            finishedAtMillis = 4_000,
            success = true,
        )
        assertEquals(3_000, result?.durationMs)
        assertEquals(true, result?.success)
    }
}
