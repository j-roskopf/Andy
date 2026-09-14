package app.andy.ui.agents

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.TimelineAxis
import app.andy.model.TimelineDetail
import app.andy.model.buildChatTimeline
import app.andy.model.timelineBrushSelection
import app.andy.model.timelineFilterRows
import app.andy.model.timelineRowDetail
import app.andy.ui.theme.AndyTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatTimelineUiTest {
    @Test
    fun clickingToolRowOpensDetailPaneWithPayloadAndResult() =
        runTimelineUiTest {
            val events = listOf(
                AgentEvent.UserMessage(atMillis = 1, text = "run pwd"),
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "bash",
                    summary = "pwd",
                    detail = """{"command":"pwd"}""" + AcpToolCallPresentation.DetailSeparator + "/Users/demo",
                    toolCallId = "call-1",
                    startedAtMillis = 1,
                    endedAtMillis = 2,
                ),
            )
            val model = buildChatTimeline(events)
            var selected by mutableStateOf<String?>(null)
            var detail by mutableStateOf<TimelineDetail?>(null)

            setContent {
                AndyTheme {
                    Row(Modifier.fillMaxSize()) {
                        ChatTimelineView(
                            model = model,
                            axis = TimelineAxis.Calls,
                            onAxisChange = {},
                            durationMode = false,
                            onDurationModeChange = {},
                            selectedRowKey = selected,
                            onSelectRow = { key ->
                                selected = key
                                val row = model.rows.firstOrNull { it.key == key }
                                detail = row?.let {
                                    timelineRowDetail(
                                        it,
                                        events.getOrNull(it.eventIndex),
                                        model.turns.firstOrNull(),
                                    )
                                }
                            },
                            brushedKeys = emptySet(),
                            brushStart = null,
                            brushEnd = null,
                            onBrushChange = { _, _ -> },
                            searchQuery = "",
                            onSearchQueryChange = {},
                            matchingKeys = timelineFilterRows(model, ""),
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                        detail?.let {
                            ChatTimelineDetailPane(
                                detail = it,
                                onClose = {
                                    detail = null
                                    selected = null
                                },
                                modifier = Modifier.width(320.dp).fillMaxSize(),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("chat-timeline-row:tool-call-1").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("chat-timeline-row:tool-call-1").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("chat-timeline-detail").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("chat-timeline-detail").assertIsDisplayed()
            assertTrue(detail?.payload?.contains("command") == true)
            assertTrue(detail?.result?.contains("/Users/demo") == true)

            onNodeWithText("Result").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("chat-timeline-detail-body").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("chat-timeline-detail-body").assertIsDisplayed()
            assertTrue(detail?.result?.contains("/Users/demo") == true)
        }

    @Test
    fun brushingLanesHighlightsExpectedRows() =
        runTimelineUiTest {
            val events = listOf(
                AgentEvent.UserMessage(atMillis = 0, text = "a"),
                AgentEvent.ToolCall(
                    atMillis = 100,
                    toolName = "t1",
                    summary = "one",
                    detail = "one",
                    toolCallId = "1",
                    startedAtMillis = 50,
                    endedAtMillis = 100,
                ),
                AgentEvent.UserMessage(atMillis = 200, text = "b"),
                AgentEvent.ToolCall(
                    atMillis = 300,
                    toolName = "t2",
                    summary = "two",
                    detail = "two",
                    toolCallId = "2",
                    startedAtMillis = 250,
                    endedAtMillis = 300,
                ),
            )
            val model = buildChatTimeline(events)
            var brushStart by mutableStateOf<Float?>(null)
            var brushEnd by mutableStateOf<Float?>(null)

            fun brushedKeys(): Set<String> {
                val start = brushStart ?: return emptySet()
                val end = brushEnd ?: return emptySet()
                return timelineBrushSelection(model, TimelineAxis.Calls, start, end, durationMode = true)
            }

            setContent {
                AndyTheme {
                    ChatTimelineView(
                        model = model,
                        axis = TimelineAxis.Calls,
                        onAxisChange = {},
                        durationMode = true,
                        onDurationModeChange = {},
                        selectedRowKey = null,
                        onSelectRow = {},
                        brushedKeys = brushedKeys(),
                        brushStart = brushStart,
                        brushEnd = brushEnd,
                        onBrushChange = { start, end ->
                            brushStart = start
                            brushEnd = end
                        },
                        searchQuery = "",
                        onSearchQueryChange = {},
                        matchingKeys = timelineFilterRows(model, ""),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("chat-timeline-lanes").fetchSemanticsNodes().isNotEmpty()
            }
            // Drive the brush the same way the lane overlay does (fraction range → row keys).
            runOnUiThread {
                brushStart = 0f
                brushEnd = 0.4f
            }
            waitUntil(timeoutMillis = 5_000) {
                val keys = brushedKeys()
                keys.isNotEmpty() && keys.any { it.startsWith("tool-1") || it.startsWith("user-") }
            }
            val keys = brushedKeys()
            assertTrue(keys.any { it.startsWith("tool-1") || it.startsWith("user-") })
            assertTrue(keys.none { it == "tool-2" })
        }

    /** Some existing async service tests can leave one failure queued in coroutines-test. */
    private fun runTimelineUiTest(block: ComposeUiTest.() -> Unit) {
        try {
            runDesktopComposeUiTest(width = 1000, height = 700) { block() }
        } catch (error: IllegalStateException) {
            if (error.message?.contains("uncaught exceptions before the test started") != true) throw error
            runDesktopComposeUiTest(width = 1000, height = 700) { block() }
        }
    }
}
