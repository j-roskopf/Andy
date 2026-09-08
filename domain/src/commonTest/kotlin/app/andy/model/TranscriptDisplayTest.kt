package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptDisplayTest {
    @Test
    fun completionOwnsDuplicateFinalAssistantText() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "All set."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(listOf(events.last()), transcriptDisplayEvents(events))
    }

    @Test
    fun distinctAssistantTextRemainsVisibleBeforeCompletion() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "I checked the files."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(events, transcriptDisplayEvents(events))
    }

    @Test
    fun adjacentStreamChunksRenderAsOneAssistantMessage() {
        val first = AgentEvent.AssistantText(atMillis = 1, text = "Hey! What", isStreamDelta = true)
        val second = AgentEvent.AssistantText(atMillis = 2, text = " are we working on today?", isStreamDelta = true)

        val displayed = transcriptDisplayEvents(listOf(first, second))

        assertEquals(listOf(first.copy(text = "Hey! What are we working on today?")), displayed)
    }

    @Test
    fun thinkingAndToolStaySeparateWhenActivityCollapseDisabled() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.Thinking(atMillis = 2, text = "Need to search the repo"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.AssistantText(atMillis = 4, text = "Done."),
        )

        val items = transcriptDisplayItems(events, collapseActivityBetweenMessages = false)

        assertEquals(4, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        assertIs<TranscriptDisplayItem.Event>(items[1])
        assertTrue(items[1] is TranscriptDisplayItem.Event && (items[1] as TranscriptDisplayItem.Event).event is AgentEvent.Thinking)
        assertIs<TranscriptDisplayItem.Event>(items[2])
        assertTrue(items[2] is TranscriptDisplayItem.Event && (items[2] as TranscriptDisplayItem.Event).event is AgentEvent.ToolCall)
        assertIs<TranscriptDisplayItem.Event>(items[3])
    }

    @Test
    fun collapseActivityBetweenMessagesGroupsThinkingAndSingleTool() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.Thinking(atMillis = 2, text = "Need to search the repo"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.AssistantText(atMillis = 4, text = "Done."),
        )

        val items = transcriptDisplayItems(events, collapseActivityBetweenMessages = true)

        assertEquals(3, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        val group = assertIs<TranscriptDisplayItem.ToolCalls>(items[1])
        assertEquals(2, group.events.size)
        assertIs<TranscriptDisplayItem.Event>(items[2])
    }

    @Test
    fun keepThinkingOnTimelineLeavesThoughtsOutOfCollapsedToolGroups() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.Thinking(atMillis = 2, text = "Need to search the repo"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.ToolCall(atMillis = 4, toolName = "Read", summary = "file.kt"),
            AgentEvent.Thinking(atMillis = 5, text = "That matches"),
            AgentEvent.ToolCall(atMillis = 6, toolName = "Edit", summary = "file.kt"),
            AgentEvent.AssistantText(atMillis = 7, text = "Done."),
        )

        val items = transcriptDisplayItems(
            events,
            collapseActivityBetweenMessages = true,
            keepThinkingOnTimeline = true,
        )

        assertEquals(6, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0]).also {
            assertIs<AgentEvent.UserMessage>(it.event)
        }
        assertIs<TranscriptDisplayItem.Event>(items[1]).also {
            assertIs<AgentEvent.Thinking>(it.event)
        }
        val firstTools = assertIs<TranscriptDisplayItem.ToolCalls>(items[2])
        assertEquals(2, firstTools.events.size)
        assertTrue(firstTools.events.none { it is AgentEvent.Thinking })
        assertIs<TranscriptDisplayItem.Event>(items[3]).also {
            assertIs<AgentEvent.Thinking>(it.event)
        }
        assertIs<TranscriptDisplayItem.Event>(items[4]).also {
            assertIs<AgentEvent.ToolCall>(it.event)
        }
        assertIs<TranscriptDisplayItem.Event>(items[5]).also {
            assertIs<AgentEvent.AssistantText>(it.event)
        }
    }

    @Test
    fun autoExpandTreatsUnsetKeysAsExpanded() {
        assertTrue(transcriptActivityExpanded("tool-1", emptySet(), autoExpand = true))
        assertFalse(transcriptActivityExpanded("tool-1", setOf("tool-1"), autoExpand = true))
    }

    @Test
    fun manualExpandRequiresExplicitKey() {
        assertFalse(transcriptActivityExpanded("tool-1", emptySet(), autoExpand = false))
        assertTrue(transcriptActivityExpanded("tool-1", setOf("tool-1"), autoExpand = false))
    }

    @Test
    fun compactToolCallsGroupsConsecutiveToolEvents() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.ToolCall(atMillis = 2, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Todo", summary = "update"),
            AgentEvent.ToolResult(atMillis = 4, toolName = "Grep", summary = "matched", isError = false),
            AgentEvent.AssistantText(atMillis = 5, text = "Done."),
        )

        val items = transcriptDisplayItems(events)

        assertEquals(3, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        val group = assertIs<TranscriptDisplayItem.ToolCalls>(items[1])
        assertEquals(3, group.events.size)
        assertEquals(1, group.startIndex)
        assertIs<TranscriptDisplayItem.Event>(items[2])
    }

    @Test
    fun compactToolCallsLeavesSingleToolAsEvent() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "Read", summary = "file.kt"),
            AgentEvent.AssistantText(atMillis = 2, text = "Looks good."),
        )

        val items = transcriptDisplayItems(events)

        assertEquals(2, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        assertTrue(items[0] is TranscriptDisplayItem.Event && (items[0] as TranscriptDisplayItem.Event).event is AgentEvent.ToolCall)
    }

    @Test
    fun acpWhitespaceRawChunksRecoverAndCoalesceIntoAssistantResponse() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "In Minneapolis today (Monday, August", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 2, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 3, text = "3,", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 4, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 5, text = "2026)", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 6, line = "Text(text=\\n\\n, annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 7, text = "- highs possibly in the", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 8, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 9, text = "90s", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 10, line = "Text(text=\\n\\n, annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 11, text = "Stay hydrated.", isStreamDelta = true),
        )

        val displayed = transcriptDisplayEvents(events)
        val assistant = displayed.filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(1, assistant.size)
        assertEquals(
            "In Minneapolis today (Monday, August 3, 2026)\n\n- highs possibly in the 90s\n\nStay hydrated.",
            assistant.single().text,
        )
    }

    @Test
    fun streamCoalescingStillBreaksAcrossToolCalls() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "first", isStreamDelta = true),
            AgentEvent.ToolCall(atMillis = 2, toolName = "search", summary = "weather"),
            AgentEvent.AssistantText(atMillis = 3, text = "second", isStreamDelta = true),
        )

        val displayed = transcriptDisplayEvents(events).filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(listOf("first", "second"), displayed.map { it.text })
    }

    @Test
    fun compactToolActivityHeadlineSummarizesEmptyEditCalls() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1,
                toolName = "Edit File",
                summary = "{}",
                kind = AgentToolKind.Edit,
            ),
            AgentEvent.ToolCall(
                atMillis = 2,
                toolName = "Edit File",
                summary = "{}",
                kind = AgentToolKind.Edit,
            ),
        )

        val headline = compactToolActivityHeadline(events)

        assertEquals("edited 2 files", headline)
    }

    /**
     * cursor-agent titles a shell call with the command and reports every kind as Other, so this
     * group used to be headlined "read 1 file" — the eight commands went unmentioned.
     */
    @Test
    fun compactToolActivityHeadlineCountsCommandsTitledWithTheirCommand() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1,
                toolName = "Read",
                summary = "440\t                        when (active?.kind) {",
                detail = "- **file path:** src/commonMain/kotlin/app/andy/ui/shell/ShellDocks.kt\n" +
                    "- **offset:** 440\n- **limit:** 220\n```\n440\twhen (active?.kind) {\n```",
                kind = AgentToolKind.Other,
                locations = listOf("src/commonMain/kotlin/app/andy/ui/shell/ShellDocks.kt"),
            ),
        ) + (2..9).map { index ->
            AgentEvent.ToolCall(
                atMillis = index.toLong(),
                toolName = "grep -rl \"CloseTab\" src | head -30",
                summary = "ShellDocks.kt",
                detail = "- **command:** grep -rl \"CloseTab\" src | head -30\n```console\nShellDocks.kt\n```",
                kind = AgentToolKind.Other,
            )
        }

        assertEquals("read 1 file, ran 8 commands", compactToolActivityHeadline(events))
    }

    @Test
    fun compactToolActivityHeadlineNamesSearchesAndCountsWhatItCannotName() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "grep", summary = "PointerButton", kind = AgentToolKind.Search),
            AgentEvent.ToolCall(atMillis = 2, toolName = "Find", summary = "*.kt", kind = AgentToolKind.Search),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Read File", summary = "ShellDocks.kt", kind = AgentToolKind.Read),
            AgentEvent.ToolCall(atMillis = 4, toolName = "Andy MCP · tap", summary = "x=10, y=20"),
        )

        assertEquals(
            "read 1 file, searched 2 times, 1 other tool call",
            compactToolActivityHeadline(events),
        )
    }

    @Test
    fun compactToolActivityHeadlineUsesActionPhrasesForSparseSingleCalls() {
        assertEquals(
            "Edited file",
            compactToolActivityHeadline(
                listOf(
                    AgentEvent.ToolCall(
                        atMillis = 1,
                        toolName = "Edit",
                        summary = "",
                        kind = AgentToolKind.Edit,
                    ),
                ),
            ),
        )
        assertEquals(
            "Ran command",
            compactToolActivityHeadline(
                listOf(
                    AgentEvent.ToolCall(
                        atMillis = 1,
                        toolName = "Terminal",
                        summary = "",
                        kind = AgentToolKind.Execute,
                    ),
                ),
            ),
        )
        assertEquals(
            "Edited SettingsScreen.kt",
            compactToolActivityHeadline(
                listOf(
                    AgentEvent.ToolCall(
                        atMillis = 1,
                        toolName = "Edit",
                        summary = "SettingsScreen.kt",
                        kind = AgentToolKind.Edit,
                    ),
                ),
            ),
        )
        assertEquals(
            "Ran ./gradlew desktopTest",
            compactToolActivityHeadline(
                listOf(
                    AgentEvent.ToolCall(
                        atMillis = 1,
                        toolName = "Terminal",
                        summary = "./gradlew desktopTest",
                        kind = AgentToolKind.Execute,
                    ),
                ),
            ),
        )
    }

    @Test
    fun compactToolActivityHeadlineUsesSpawningCopyForTaskTools() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1,
                toolName = "Task",
                summary = "description=Review PR #717, subagent_type=explore",
                detail = """{"description":"Review PR #717","prompt":"Review PR #717","subagent_type":"explore"}""",
            ),
            AgentEvent.ToolCall(
                atMillis = 2,
                toolName = "Task",
                summary = "description=Review PR #717, subagent_type=explore",
                detail = """{"description":"Review PR #717","prompt":"Review PR #717","subagent_type":"explore"}""",
            ),
            AgentEvent.ToolCall(
                atMillis = 3,
                toolName = "Task",
                summary = "description=Review PR #717, subagent_type=explore",
                detail = """{"description":"Review PR #717","prompt":"Review PR #717","subagent_type":"explore"}""",
            ),
            AgentEvent.ToolCall(
                atMillis = 4,
                toolName = "Task",
                summary = "description=Review PR #717, subagent_type=explore",
                detail = """{"description":"Review PR #717","prompt":"Review PR #717","subagent_type":"explore"}""",
            ),
        )

        assertEquals("Spawning 4 agents", compactToolActivityHeadline(events))
    }

    @Test
    fun genericToolNameNeverPrefixesTheHeadline() {
        assertEquals(
            "totalMatches=45, truncated=false",
            toolBlockHeadline(
                name = "tool",
                summary = "totalMatches=45, truncated=false",
                kind = null,
                locations = emptyList(),
            ),
        )
    }

    @Test
    fun genericToolNameWithoutArgumentsFallsBackToKindOrCallPhrase() {
        assertEquals(
            "Searched",
            toolBlockHeadline(name = "tool", summary = "", kind = AgentToolKind.Search, locations = emptyList()),
        )
        assertEquals(
            "Tool call",
            toolBlockHeadline(name = "tool", summary = "", kind = null, locations = emptyList()),
        )
    }

    /** Providers put whole command results in `summary`; a headline is a label, not a payload. */
    @Test
    fun commandResultHeadlineCollapsesToOneShortLine() {
        val diff = buildString {
            appendLine("diff --git a/src/Main.kt b/src/Main.kt")
            appendLine("--- a/src/Main.kt")
            appendLine("+++ b/src/Main.kt")
            appendLine("@@ -1,2 +1,2 @@")
            appendLine("-    println(\"old\")")
            appendLine("+    println(\"new\")")
            repeat(200) { appendLine("+    line $it") }
        }

        val headline = toolBlockHeadline(
            name = "tool",
            summary = "exitCode=0, stdout=$diff",
            kind = null,
            locations = emptyList(),
        )

        assertFalse(headline.contains('\n'))
        assertTrue(headline.length <= 161, "headline was ${headline.length} chars")
        assertTrue(headline.startsWith("exitCode=0, stdout=diff --git"))
        assertTrue(headline.endsWith("…"))
    }

    @Test
    fun contentFreeToolRowsAreOmittedAndOnlyCounted() {
        assertTrue(toolRowShowsNothing("tool", "", "", emptyList(), hasImages = false))
        assertTrue(toolRowShowsNothing("tool", "No details", "No details", emptyList(), hasImages = false))
        assertFalse(toolRowShowsNothing("tool", "", "", listOf("src/Main.kt"), hasImages = false))
        assertFalse(toolRowShowsNothing("tool", "", "", emptyList(), hasImages = true))
        assertFalse(toolRowShowsNothing("tool", "", "", emptyList(), hasImages = false, isFailure = true))
        assertFalse(toolRowShowsNothing("grep", "", "", emptyList(), hasImages = false))

        val bookkeeping = (1..3).map { index ->
            AgentEvent.ToolCall(atMillis = index.toLong(), toolName = "tool", summary = "", detail = "")
        }
        assertEquals("3 tool calls", compactToolActivityHeadline(bookkeeping))
        assertEquals(
            "Ran ./gradlew desktopTest",
            compactToolActivityHeadline(
                bookkeeping + AgentEvent.ToolCall(
                    atMillis = 4,
                    toolName = "Terminal",
                    summary = "./gradlew desktopTest",
                    kind = AgentToolKind.Execute,
                ),
            ),
        )
    }

    @Test
    fun connectionStallErrorsAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "read", summary = "gradle"),
            AgentEvent.AssistantText(atMillis = 2, text = "Error: RetriableError: Connection stalled"),
            AgentEvent.TaskError(atMillis = 3, message = "RetriableError: Connection stalled"),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertIs<AgentEvent.ToolCall>(displayed.single())
    }

    @Test
    fun stallMentionsStayVisibleInTranscriptDisplay() {
        val events = listOf(
            AgentEvent.AssistantText(
                atMillis = 1,
                text = "connection stalled is a known failure mode, including `http/2 stream closed`.",
            ),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertEquals(events.single(), displayed.single())
    }

    @Test
    fun trailingStallLineIsStrippedButPriorOutputStays() {
        val events = listOf(
            AgentEvent.AssistantText(
                atMillis = 1,
                text = "Here is the patch.\n\nError: RetriableError: Connection stalled",
            ),
        )

        val displayed = transcriptDisplayEvents(events)
        val text = displayed.single() as AgentEvent.AssistantText
        assertEquals("Here is the patch.", text.text)
    }

    @Test
    fun http2CancelErrorsAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "read", summary = "gradle"),
            AgentEvent.AssistantText(
                atMillis = 2,
                text = "Error: RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)",
            ),
            AgentEvent.TaskError(
                atMillis = 3,
                message = "RetriableError: [canceled] http/2 stream closed with error code CANCEL (0x8)",
            ),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertIs<AgentEvent.ToolCall>(displayed.single())
    }

    @Test
    fun resourceExhaustedErrorsAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "read", summary = "gradle"),
            AgentEvent.AssistantText(
                atMillis = 2,
                text = "Error: RetriableError: [resource_exhausted] Error",
            ),
            AgentEvent.TaskError(
                atMillis = 3,
                message = "RetriableError: [resource_exhausted] Error",
            ),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertIs<AgentEvent.ToolCall>(displayed.single())
    }

    @Test
    fun silentContinuePromptsAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "ship it"),
            AgentEvent.AssistantText(atMillis = 2, text = "Error: RetriableError: [resource_exhausted] Error"),
            AgentEvent.UserMessage(atMillis = 3, text = CONNECTION_STALL_RETRY_PROMPT),
            AgentEvent.AssistantText(atMillis = 4, text = "Picking up again."),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(2, displayed.size)
        assertEquals("ship it", (displayed[0] as AgentEvent.UserMessage).text)
        assertEquals("Picking up again.", (displayed[1] as AgentEvent.AssistantText).text)
    }

    @Test
    fun undoneFileChangesAreHiddenFromTranscriptDisplay() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", 2, 1))),
            diffs = emptyMap(),
        )
        val events = listOf(
            AgentEvent.FileChanges(atMillis = 1, batchId = "batch-1", baselineTree = "abc", snapshot = snapshot, undone = true),
            AgentEvent.FileChanges(atMillis = 2, batchId = "batch-2", baselineTree = "def", snapshot = snapshot),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertEquals("batch-2", (displayed.single() as AgentEvent.FileChanges).batchId)
    }

    @Test
    fun consecutiveFileChangesAreMergedForDisplay() {
        val snapshotA = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 2, 0))),
            diffs = emptyMap(),
        )
        val snapshotB = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/B.kt", 1, 1))),
            diffs = emptyMap(),
        )
        val events = listOf(
            AgentEvent.FileChanges(atMillis = 1, batchId = "batch-1", baselineTree = "abc", snapshot = snapshotA),
            AgentEvent.FileChanges(atMillis = 2, batchId = "batch-2", baselineTree = "abc", snapshot = snapshotB),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        val merged = displayed.single() as AgentEvent.FileChanges
        assertEquals(listOf("batch-1", "batch-2"), merged.groupedBatchIds)
        assertEquals(listOf("src/A.kt", "src/B.kt"), merged.snapshot.summary.files.map { it.path })
    }

    @Test
    fun fileChangesSeparatedByActivityAreMergedWithinTurn() {
        val snapshotA = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 2, 0))),
            diffs = emptyMap(),
        )
        val snapshotB = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/B.kt", 1, 1))),
            diffs = emptyMap(),
        )
        val events = listOf(
            AgentEvent.FileChanges(atMillis = 1, batchId = "batch-1", baselineTree = "abc", snapshot = snapshotA),
            AgentEvent.Thinking(atMillis = 2, text = "planning next edit"),
            AgentEvent.ToolCall(
                atMillis = 3,
                toolName = "Read",
                summary = "src/B.kt",
                detail = "",
                kind = AgentToolKind.Read,
                state = AgentToolState.Completed,
            ),
            AgentEvent.FileChanges(atMillis = 4, batchId = "batch-2", baselineTree = "abc", snapshot = snapshotB),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.filterIsInstance<AgentEvent.FileChanges>().size)
        val merged = displayed.filterIsInstance<AgentEvent.FileChanges>().single()
        assertEquals(listOf("batch-1", "batch-2"), merged.groupedBatchIds)
        assertEquals(listOf("src/A.kt", "src/B.kt"), merged.snapshot.summary.files.map { it.path })
    }

    @Test
    fun emptyFileChangesAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "/gh-ship-pr"),
            AgentEvent.FileChanges(
                atMillis = 2,
                batchId = "batch-empty",
                baselineTree = "abc",
                snapshot = AgentThreadChangeSnapshot(AgentChangeSummary(emptyList()), emptyMap()),
            ),
        )

        assertFalse(transcriptDisplayEvents(events).any { it is AgentEvent.FileChanges })
    }

    @Test
    fun fileChangesInSeparateTurnsAreNotMerged() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 1, 0))),
            diffs = emptyMap(),
        )
        val events = listOf(
            AgentEvent.FileChanges(atMillis = 1, batchId = "batch-1", baselineTree = "abc", snapshot = snapshot),
            AgentEvent.UserMessage(atMillis = 2, text = "follow up"),
            AgentEvent.FileChanges(atMillis = 3, batchId = "batch-2", baselineTree = "abc", snapshot = snapshot),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(2, displayed.filterIsInstance<AgentEvent.FileChanges>().size)
    }

    @Test
    fun openTurnFileChangesAreHiddenWhileTurnIsActive() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 1, 0))),
            diffs = emptyMap(),
        )
        val prior = AgentEvent.FileChanges(atMillis = 1, batchId = "batch-1", baselineTree = "abc", snapshot = snapshot)
        val events = listOf(
            prior,
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = null),
            AgentEvent.UserMessage(atMillis = 3, text = "edit more"),
            AgentEvent.FileChanges(atMillis = 4, batchId = "batch-2", baselineTree = "abc", snapshot = snapshot),
        )

        val displayed = transcriptDisplayEvents(events, hideOpenTurnFileChanges = true)
        val cards = displayed.filterIsInstance<AgentEvent.FileChanges>()
        assertEquals(1, cards.size)
        assertEquals("batch-1", cards.single().batchId)
    }

    @Test
    fun openTurnFileChangesAreHiddenOnFirstTurnWhileActive() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 1, 0))),
            diffs = emptyMap(),
        )
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "edit"),
            AgentEvent.FileChanges(atMillis = 2, batchId = "batch-1", baselineTree = "abc", snapshot = snapshot),
        )

        assertFalse(
            transcriptDisplayEvents(events, hideOpenTurnFileChanges = true)
                .any { it is AgentEvent.FileChanges },
        )
        assertTrue(
            transcriptDisplayEvents(events, hideOpenTurnFileChanges = false)
                .any { it is AgentEvent.FileChanges },
        )
    }
}
