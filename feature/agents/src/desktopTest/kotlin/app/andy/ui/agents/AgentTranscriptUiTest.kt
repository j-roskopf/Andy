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
            // Forward layout appends at the end, so earlier row indices stay stable.
            assertEquals(saved.index, restored.index)
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
            // Growth while detached must not re-arm follow.
            val afterStream = assertNotNull(memory.get("streaming"))
            assertFalse(afterStream.stickToBottom)
            assertEquals(detached.anchorKey, afterStream.anchorKey)

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(1_000_000f)
            }
            waitForIdle()
            // Lazy composition may not reach the live edge after a single jump; keep nudging so
            // the final row is composed, then wait for the wheel/scroll settle to re-arm follow.
            repeat(20) {
                if (memory.get("streaming")?.stickToBottom == true) return@repeat
                onNodeWithTag("transcript-list").performMouseInput {
                    moveTo(center)
                    scroll(200_000f)
                }
                waitForIdle()
            }
            waitUntil(timeoutMillis = 5_000) {
                memory.get("streaming")?.stickToBottom == true
            }
            assertEquals(true, assertNotNull(memory.get("streaming")).stickToBottom)

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-12f)
            }
            waitForIdle()
            assertFalse(assertNotNull(memory.get("streaming")).stickToBottom)

            onNodeWithText("↓ latest").performClick()
            waitForIdle()
            assertEquals(true, assertNotNull(memory.get("streaming")).stickToBottom)

            // More stream tokens while following must keep stick armed and newest text visible.
            runOnUiThread {
                events = events.dropLast(1) + AgentEvent.AssistantText(
                    atMillis = 41,
                    text = buildString {
                        appendLine("stream start")
                        repeat(200) { appendLine("post-follow streamed line $it") }
                    },
                    isStreamDelta = true,
                )
            }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("post-follow streamed line 199", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertEquals(true, assertNotNull(memory.get("streaming")).stickToBottom)
            onNodeWithText("post-follow streamed line 199", substring = true).assertIsDisplayed()
        }

    @Test
    fun followLiveWithThinkingIndicatorKeepsNewestTokensVisible() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            var events by mutableStateOf(
                listOf(
                    AgentEvent.UserMessage(atMillis = 1, text = "write a long answer"),
                    AgentEvent.AssistantText(
                        atMillis = 2,
                        text = buildString {
                            repeat(60) { appendLine("early body line $it") }
                        },
                        isStreamDelta = true,
                    ),
                ),
            )

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = events,
                        isActive = true,
                        showThinkingIndicator = true,
                        restoreScrollKey = "follow-thinking",
                        scrollMemory = memory,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                repeat(20) { scroll(-80f) }
            }
            waitForIdle()
            assertFalse(assertNotNull(memory.get("follow-thinking")).stickToBottom)

            onNodeWithText("↓ follow live").performClick()
            waitForIdle()
            repeat(3) {
                mainClock.advanceTimeByFrame()
                waitForIdle()
            }
            assertEquals(true, assertNotNull(memory.get("follow-thinking")).stickToBottom)

            runOnUiThread {
                events = events.dropLast(1) + AgentEvent.AssistantText(
                    atMillis = 2,
                    text = buildString {
                        repeat(60) { appendLine("early body line $it") }
                        repeat(120) { appendLine("live token $it") }
                    },
                    isStreamDelta = true,
                )
            }
            waitForIdle()
            repeat(5) {
                mainClock.advanceTimeByFrame()
                waitForIdle()
            }
            assertEquals(true, assertNotNull(memory.get("follow-thinking")).stickToBottom)
            onNodeWithText("live token 119", substring = true).assertIsDisplayed()
        }

    @Test
    fun growingStreamWhileDetachedKeepsScrolledTextPinned() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            val startMarker = "STREAM_START_MARKER_UNIQUE"
            var events by mutableStateOf(
                listOf(
                    AgentEvent.UserMessage(atMillis = 1, text = "write a long answer"),
                    AgentEvent.AssistantText(
                        atMillis = 2,
                        text = buildString {
                            appendLine(startMarker)
                            repeat(80) { appendLine("early body line $it with enough width to wrap in the chat column") }
                        },
                        isStreamDelta = true,
                    ),
                ),
            )

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = events,
                        isActive = true,
                        showThinkingIndicator = true,
                        restoreScrollKey = "growing-message",
                        scrollMemory = memory,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            repeat(30) {
                onNodeWithTag("transcript-list").performMouseInput {
                    moveTo(center)
                    scroll(-80f)
                }
                waitForIdle()
            }
            onNodeWithText(startMarker, substring = true).assertIsDisplayed()
            val detached = assertNotNull(memory.get("growing-message"))
            assertFalse(detached.stickToBottom)

            repeat(10) { chunk ->
                runOnUiThread {
                    events = events.dropLast(1) + AgentEvent.AssistantText(
                        atMillis = 2,
                        text = buildString {
                            appendLine(startMarker)
                            repeat(80) {
                                appendLine("early body line $it with enough width to wrap in the chat column")
                            }
                            repeat((chunk + 1) * 20) {
                                appendLine("late streamed continuation $it that must not yank the viewport")
                            }
                        },
                        isStreamDelta = true,
                    )
                }
                waitForIdle()
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("late streamed continuation 199", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            waitForIdle()

            val afterStream = assertNotNull(memory.get("growing-message"))
            assertFalse(afterStream.stickToBottom)
            assertEquals(detached.anchorKey, afterStream.anchorKey)
            // Forward layout grows the streaming row downward from the top anchor — the start
            // of the message you scrolled to must remain on screen without compensation loops.
            onNodeWithText(startMarker, substring = true).assertIsDisplayed()
        }

    @Test
    fun contentDrivenJumpToLiveEdgeDoesNotRearmFollowWhileDetached() =
        runTranscriptUiTest {
            val memory = TranscriptScrollMemory()
            var events by mutableStateOf(
                (0..40).map { index ->
                    AgentEvent.UserMessage(atMillis = index.toLong(), text = "history row $index")
                } + AgentEvent.AssistantText(
                    atMillis = 41,
                    text = buildString {
                        appendLine("stream start")
                        repeat(40) { appendLine("body line $it") }
                    },
                    isStreamDelta = true,
                ),
            )

            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = events,
                        isActive = true,
                        restoreScrollKey = "no-rearm",
                        scrollMemory = memory,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            onNodeWithTag("transcript-list").performMouseInput {
                moveTo(center)
                scroll(-20f)
            }
            waitForIdle()
            val detached = assertNotNull(memory.get("no-rearm"))
            assertFalse(detached.stickToBottom)

            // Grow the live message a lot; follow must stay off even if layout fidgets.
            repeat(8) { step ->
                runOnUiThread {
                    events = events.dropLast(1) + AgentEvent.AssistantText(
                        atMillis = 41,
                        text = buildString {
                            appendLine("stream start")
                            repeat(40 + step * 30) { appendLine("body line $it") }
                        },
                        isStreamDelta = true,
                    )
                }
                waitForIdle()
            }

            val after = assertNotNull(memory.get("no-rearm"))
            assertFalse(after.stickToBottom)
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

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("println(\"new output\")", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue(onAllNodesWithText("\"stdout\"", substring = true).fetchSemanticsNodes().isEmpty())
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("warning before patch", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
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
    fun pendingInputRendersPinnedBelowTranscript() =
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
            onNodeWithTag("pending-task-input").assertIsDisplayed()
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
    fun editedFilesCardRendersInline() =
        runTranscriptUiTest {
            val snapshot = app.andy.model.AgentThreadChangeSnapshot(
                summary = app.andy.model.AgentChangeSummary(
                    listOf(
                        app.andy.model.AgentFileChange("composeApp/src/wasmJsMain/resources/index.html", 6, 6),
                        app.andy.model.AgentFileChange("core/platform/src/commonMain/kotlin/Platform.kt", 3, 0),
                    ),
                ),
                diffs = emptyMap(),
            )
            setContent {
                AndyTheme {
                    AgentTranscript(
                        events = listOf(
                            AgentEvent.UserMessage(atMillis = 1, text = "update platform"),
                            AgentEvent.FileChanges(
                                atMillis = 2,
                                batchId = "batch-1",
                                baselineTree = "abc",
                                snapshot = snapshot,
                            ),
                            AgentEvent.AssistantText(atMillis = 3, text = "Updated the platform files."),
                        ),
                        isActive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("edited-files-card").assertIsDisplayed()
            onNodeWithText("Edited 2 files").assertIsDisplayed()
            onNodeWithTag("edited-files-review").assertIsDisplayed()
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
