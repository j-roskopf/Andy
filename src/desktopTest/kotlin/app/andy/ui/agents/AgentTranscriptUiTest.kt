package app.andy.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.model.AgentEvent
import app.andy.ui.theme.AndyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentTranscriptUiTest {
    @Test
    fun firstVisitStartsAtLatestAndConversationRestoresItsOwnPosition() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            var conversationId by mutableStateOf("first")
            var events by mutableStateOf(
                (0..40).map { index ->
                    AgentEvent.UserMessage(atMillis = index.toLong(), text = "conversation row $index")
                },
            )

            setContent {
                AndyTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentTranscript(
                            events = events,
                            isActive = false,
                            restoreScrollKey = conversationId,
                            scrollMemory = memory,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("transcript-row-UserMessage-40-40").assertIsDisplayed()

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-12f)
            }
            waitForIdle()
            val saved = assertNotNull(memory.get("first"))
            assertFalse(saved.stickToBottom)
            assertTrue(saved.index > 0 || saved.offset > 0)

            runOnUiThread { conversationId = "second" }
            waitForIdle()
            onNodeWithTag("transcript-row-UserMessage-40-40").assertIsDisplayed()
            assertEquals(true, memory.get("second")?.stickToBottom)

            // New content can arrive while the first conversation is away. Restoration uses
            // the saved row key, not the now-stale numeric index.
            runOnUiThread {
                events = events + AgentEvent.UserMessage(atMillis = 41, text = "conversation row 41")
            }
            waitForIdle()
            runOnUiThread { conversationId = "first" }
            waitForIdle()
            val restored = assertNotNull(memory.get("first"))
            assertEquals(saved.anchorKey, restored.anchorKey)
            assertEquals(saved.offset, restored.offset)
            assertEquals(saved.index + 1, restored.index)
            assertFalse(restored.stickToBottom)
        }

    @Test
    fun streamingKeepsDetachedViewportFixedUntilLatestIsRequested() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            var events by mutableStateOf(
                (0..40).map { index ->
                    AgentEvent.UserMessage(atMillis = index.toLong(), text = "history row $index")
                } + AgentEvent.AssistantText(atMillis = 41, text = "stream start", isStreamDelta = true),
            )

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = events,
                        isActive = false,
                        restoreScrollKey = "streaming",
                        scrollMemory = memory,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            assertEquals(true, memory.get("streaming")?.stickToBottom)

            runOnUiThread {
                events = events.dropLast(1) + AgentEvent.AssistantText(
                    atMillis = 41,
                    text = buildString {
                        appendLine("stream start")
                        repeat(20) { appendLine("early streamed line $it") }
                    },
                    isStreamDelta = true,
                )
            }
            waitForIdle()
            val pinned = assertNotNull(memory.get("streaming"))
            assertEquals(true, pinned.stickToBottom)
            assertEquals(0, pinned.index)
            assertEquals(0, pinned.offset)

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-12f)
            }
            waitForIdle()
            val detached = assertNotNull(memory.get("streaming"))
            assertFalse(detached.stickToBottom)

            runOnUiThread {
                events = events.dropLast(1) + AgentEvent.AssistantText(
                    atMillis = 41,
                    text = buildString {
                        appendLine("stream start")
                        repeat(120) { appendLine("new streamed line $it") }
                    },
                    isStreamDelta = true,
                )
            }
            waitForIdle()
            assertEquals(detached, memory.get("streaming"))

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(100f)
            }
            waitForIdle()
            val relocked = assertNotNull(memory.get("streaming"))
            assertEquals(true, relocked.stickToBottom)
            assertEquals(0, relocked.index)
            assertEquals(0, relocked.offset)

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-12f)
            }
            waitForIdle()
            assertFalse(assertNotNull(memory.get("streaming")).stickToBottom)

            onNodeWithText("↓  latest").performClick()
            waitForIdle()
            val followed = assertNotNull(memory.get("streaming"))
            assertEquals(true, followed.stickToBottom)
            assertEquals(0, followed.index)
            assertEquals(0, followed.offset)
        }

    /** Some existing async service tests can leave one failure queued in coroutines-test. */
    private fun runTranscriptUiTest(block: ComposeUiTest.() -> Unit) {
        try {
            runDesktopComposeUiTest(width = 800, height = 500, block = block)
        } catch (error: IllegalStateException) {
            if (error.message?.contains("uncaught exceptions before the test started") != true) throw error
            runDesktopComposeUiTest(width = 800, height = 500, block = block)
        }
    }
}
