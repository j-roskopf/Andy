package app.andy.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
    fun onlyUserMessagesUseChatBubbles() =
        runTranscriptUiTest {
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "user prompt"),
                            AgentEvent.AssistantText(atMillis = 2, text = "agent response"),
                            AgentEvent.TaskResult(
                                atMillis = 3,
                                success = true,
                                finalText = "completed response",
                                durationMs = 125_000,
                            ),
                        ),
                        isActive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("user-message-bubble").assertIsDisplayed()
            assertTrue(onAllNodesWithTag("agent-message-bubble").fetchSemanticsNodes().isEmpty())
            onNodeWithText("user prompt").assertIsDisplayed()
            onNodeWithText("Worked for 2:05").assertIsDisplayed()
            onNodeWithText("completed response").assertIsDisplayed()
        }

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

            onNodeWithText("↓ latest").performClick()
            waitForIdle()
            val followed = assertNotNull(memory.get("streaming"))
            assertEquals(true, followed.stickToBottom)
            assertEquals(0, followed.index)
            assertEquals(0, followed.offset)
        }

    @Test
    fun scrollToLatestRequestJumpsToLiveEdgeAfterDetaching() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            var events by mutableStateOf(
                (0..40).map { index ->
                    AgentEvent.UserMessage(atMillis = index.toLong(), text = "conversation row $index")
                },
            )
            var scrollToLatestRequest by mutableStateOf(0)

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = events,
                        isActive = true,
                        restoreScrollKey = "follow-up",
                        scrollMemory = memory,
                        scrollToLatestRequest = scrollToLatestRequest,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-12f)
            }
            waitForIdle()
            assertFalse(assertNotNull(memory.get("follow-up")).stickToBottom)

            runOnUiThread {
                events = events + AgentEvent.UserMessage(atMillis = 41, text = "follow-up message")
                scrollToLatestRequest++
            }
            waitForIdle()

            val restored = assertNotNull(memory.get("follow-up"))
            assertEquals(true, restored.stickToBottom)
            assertEquals(0, restored.index)
            assertEquals(0, restored.offset)
            onNodeWithTag("transcript-row-UserMessage-41-41").assertIsDisplayed()
        }

    /** Persisted rows keep the provider's raw payload, so the transcript must format it on render. */
    @Test
    fun toolRowsShowNeitherRawJsonNorTheGenericToolLabel() =
        runTranscriptUiTest {
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "search the repo"),
                            AgentEvent.ToolResult(
                                atMillis = 2,
                                toolName = "tool",
                                summary = "totalMatches=45, truncated=false",
                                detail = """{"totalMatches":45,"truncated":false}""",
                                isError = false,
                            ),
                            AgentEvent.ToolResult(
                                atMillis = 3,
                                toolName = "tool",
                                summary = "",
                                detail = "",
                                isError = false,
                            ),
                        ),
                        isActive = false,
                        autoExpandToolSections = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithText("totalMatches=45, truncated=false").assertIsDisplayed()
            // A call carrying only an id has nothing to say, so it gets no row at all.
            assertTrue(onAllNodesWithText("Tool call", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("{", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("tool:", substring = true).fetchSemanticsNodes().isEmpty())
        }

    /** A shell result wraps its diff in JSON; the row must show the diff, not the transport. */
    @Test
    fun commandResultDiffRendersInTheDiffViewer() =
        runTranscriptUiTest {
            val diffText = """
                diff --git a/src/Main.kt b/src/Main.kt
                index c0fcac9..7b39bbc 100644
                --- a/src/Main.kt
                +++ b/src/Main.kt
                @@ -1,3 +1,3 @@
                 fun main() {
                -    println("old output")
                +    println("new output")
                 }
            """.trimIndent()
            val stdout = "warning before patch\n$diffText\nwarning after patch"
            val payload =
                """{"exitCode":7,"stdout":"${stdout.replace("\n", "\\n").replace("\"", "\\\"")}","stderr":"formatter warning"}"""

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "show me the diff"),
                            AgentEvent.ToolCall(
                                atMillis = 2,
                                toolName = "tool",
                                summary = "exitCode=0, stdout=$diffText",
                                detail = payload,
                            ),
                        ),
                        isActive = false,
                        autoExpandToolSections = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onAllNodesWithText("println(\"new output\")", substring = true)
                .fetchSemanticsNodes()
                .let { assertTrue(it.isNotEmpty(), "diff body was not rendered") }
            assertTrue(onAllNodesWithText("\"stdout\"", substring = true).fetchSemanticsNodes().isEmpty())
            onNodeWithText("warning before patch", substring = true).assertExists()
            onNodeWithText("warning after patch", substring = true).assertExists()
            onNodeWithText("formatter warning", substring = true).assertExists()
            onNodeWithText("exitCode:", substring = true).assertExists()
            // The row used to render the entire payload as one 4 KB line of text.
            val longestRendered = onAllNodesWithText("", substring = true)
                .fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .maxOfOrNull { it.text.length }
                ?: 0
            assertTrue(longestRendered <= 200, "rendered a $longestRendered-character blob")
        }

    @Test
    fun pendingInputRendersOnLiveEdge() =
        runTranscriptUiTest {
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "spec brief"),
                            AgentEvent.AssistantText(atMillis = 2, text = "What frame-rate behavior should this task ship?"),
                        ),
                        isActive = false,
                        pendingContent = {
                            androidx.compose.material3.Text(
                                "DECISION NEEDED",
                                modifier = Modifier.testTag("pending-input"),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("pending-input").assertIsDisplayed()
        }

    @Test
    fun trailingContentRendersOnLiveEdge() =
        runTranscriptUiTest {
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "spec brief"),
                            AgentEvent.AssistantText(atMillis = 2, text = "Updated the reducer."),
                        ),
                        isActive = false,
                        trailingContent = {
                            androidx.compose.material3.Text(
                                "Edited 2 files",
                                modifier = Modifier.testTag("trailing-content"),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("trailing-content").assertIsDisplayed()
        }

    @Test
    fun userMessagesRenderAsPlainText() =
        runTranscriptUiTest {
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(
                                atMillis = 1,
                                text = "# heading\n**bold** and `code`",
                            ),
                        ),
                        isActive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithText("# heading\n**bold** and `code`").assertIsDisplayed()
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
