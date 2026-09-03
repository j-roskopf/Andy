package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentSkill
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.promptWithSkillHints
import app.andy.ui.components.ChatBubbleGroup
import app.andy.ui.components.ChatBubbleSender
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.latestPlanHasPendingEntries
import app.andy.model.planTextFromAcpTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTranscriptTest {
    @Test
    fun suppressLatestMatchingUserMessageHidesOnlyNewestMatch() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "hello"),
            AgentEvent.AssistantText(atMillis = 2, text = "hi"),
            AgentEvent.UserMessage(atMillis = 3, text = "hello"),
        )
        val suppressed = suppressLatestMatchingUserMessage(events, "hello")
        assertEquals(2, suppressed.size)
        assertIs<AgentEvent.UserMessage>(suppressed[0])
        assertEquals("hello", (suppressed[0] as AgentEvent.UserMessage).text)
        assertIs<AgentEvent.AssistantText>(suppressed[1])
        assertEquals(events, suppressLatestMatchingUserMessage(events, null))
        assertEquals(events, suppressLatestMatchingUserMessage(events, "missing"))
    }

    @Test
    fun chatBubbleSenderIgnoresSilentRecoveryPrompts() {
        assertEquals(null, AgentEvent.UserMessage(atMillis = 1, text = CONNECTION_STALL_RETRY_PROMPT).chatBubbleSenderOrNull())
        assertEquals(ChatBubbleSender.User, AgentEvent.UserMessage(atMillis = 1, text = "hello").chatBubbleSenderOrNull())
        assertEquals(ChatBubbleSender.Assistant, AgentEvent.AssistantText(atMillis = 1, text = "hi").chatBubbleSenderOrNull())
    }

    @Test
    fun transcriptChatBubbleGroupClustersConsecutiveSameSenderMessages() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "one"),
            AgentEvent.UserMessage(atMillis = 2, text = "two"),
            AgentEvent.AssistantText(atMillis = 3, text = "reply"),
            AgentEvent.UserMessage(atMillis = 4, text = "solo"),
        )
        val items = transcriptDisplayItems(events)

        assertEquals(ChatBubbleGroup.First, transcriptChatBubbleGroup(items, 0))
        assertEquals(ChatBubbleGroup.Last, transcriptChatBubbleGroup(items, 1))
        assertEquals(ChatBubbleGroup.Single, transcriptChatBubbleGroup(items, 2))
        assertEquals(ChatBubbleGroup.Single, transcriptChatBubbleGroup(items, 3))
    }

    @Test
    fun transcriptChatBubbleGroupBreaksAcrossToolActivity() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "one"),
            AgentEvent.ToolCall(atMillis = 2, toolName = "Read", summary = "file.kt"),
            AgentEvent.UserMessage(atMillis = 3, text = "two"),
        )
        val items = transcriptDisplayItems(events)

        assertEquals(ChatBubbleGroup.Single, transcriptChatBubbleGroup(items, 0))
        assertEquals(ChatBubbleGroup.Single, transcriptChatBubbleGroup(items, 2))
    }

    @Test
    fun storedPromptIsHiddenWhenTranscriptAlreadyContainsUserTurn() {
        assertFalse(
            shouldDisplayOriginalPrompt(
                events = listOf(AgentEvent.UserMessage(atMillis = 1, text = "hello")),
                originalPrompt = "hello",
                originalImagePaths = emptyList(),
            ),
        )
    }

    @Test
    fun storedPromptWithImagesIsHiddenWhenTranscriptContainsCliFormattedUserTurn() {
        val prompt = "can we use this for the git icon in the new chat window?"
        val imagePaths = listOf("/Users/joer/.andy/agent-images/dropped-12722960277110303733.png")
        assertFalse(
            shouldDisplayOriginalPrompt(
                events = listOf(
                    AgentEvent.UserMessage(
                        atMillis = 1,
                        text = "$prompt\n\nAttached image file (inspect these as part of the task):\n- ${imagePaths.single()}\n",
                        imagePaths = imagePaths,
                    ),
                ),
                originalPrompt = prompt,
                originalImagePaths = imagePaths,
            ),
        )
    }

    @Test
    fun storedImageOnlyPromptIsHiddenWhenTranscriptContainsMatchingUserTurn() {
        val imagePaths = listOf("/tmp/screenshot.png")
        assertFalse(
            shouldDisplayOriginalPrompt(
                events = listOf(
                    AgentEvent.UserMessage(
                        atMillis = 1,
                        text = "Attached image files (inspect as part of the task): ${imagePaths.single()}",
                        imagePaths = imagePaths,
                    ),
                ),
                originalPrompt = "",
                originalImagePaths = imagePaths,
            ),
        )
    }

    @Test
    fun storedPromptWithSkillsIsHiddenWhenTranscriptContainsCliFormattedUserTurn() {
        val prompt = "/gh-ship-pr"
        val skills = listOf(AgentSkill(name = "gh-ship-pr", description = "", path = "/tmp/gh-ship-pr/SKILL.md"))
        assertFalse(
            shouldDisplayOriginalPrompt(
                events = listOf(
                    AgentEvent.UserMessage(
                        atMillis = 1,
                        text = promptWithSkillHints(prompt, skills),
                        skills = skills,
                    ),
                ),
                originalPrompt = prompt,
                originalImagePaths = emptyList(),
                originalSkills = skills,
            ),
        )
    }

    @Test
    fun skillOnlyUserMessageDisplaysSkillLinksWithoutDuplicateText() {
        val skills = listOf(AgentSkill(name = "gh-ship-pr", description = "", path = "/tmp/gh-ship-pr/SKILL.md"))
        val event = AgentEvent.UserMessage(atMillis = 1, text = "/gh-ship-pr", skills = skills)
        assertEquals("", userMessageDisplayText(event))
    }

    @Test
    fun storedPromptStillVisibleWhenFollowUpExistsButOriginalMissing() {
        assertTrue(
            shouldDisplayOriginalPrompt(
                events = listOf(
                    AgentEvent.UserMessage(atMillis = 1, text = "Implement the plan."),
                ),
                originalPrompt = "1. can we redo the top chrome nav",
                originalImagePaths = emptyList(),
            ),
        )
    }

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
    fun reverseTranscriptBottomIsIndexZeroWithNoOffset() {
        assertTrue(transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
        assertTrue(transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 1))
        assertTrue(!transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 2))
        assertTrue(!transcriptIsAtBottom(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun scrollMemoryKeepsIndependentConversationPositions() {
        val memory = TranscriptScrollMemory()
        val first = TranscriptScrollPosition(index = 8, offset = 14, stickToBottom = false)
        val second = TranscriptScrollPosition(index = 0, offset = 0, stickToBottom = true)

        memory.save("first", first)
        memory.save("second", second)

        assertEquals(first, memory.get("first"))
        assertEquals(second, memory.get("second"))
        memory.remove("first")
        assertEquals(null, memory.get("first"))
        assertEquals(second, memory.get("second"))
    }

    @Test
    fun firstConversationVisitHasNoSavedPositionAndDefaultsToLiveEdge() {
        val memory = TranscriptScrollMemory()

        assertEquals(null, memory.get("new-chat"))
        assertTrue(
            transcriptIsAtBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            ),
        )
    }

    @Test
    fun streamDeltaKeysStayStableWhileTextGrows() {
        val short = AgentEvent.AssistantText(atMillis = 10, text = "Hel", isStreamDelta = true)
        val long = short.copy(text = "Hello world")
        val shortKey = transcriptEventKey(0, short)
        val longKey = transcriptEventKey(0, long)
        assertEquals(shortKey, longKey)
    }

    @Test
    fun toolGroupKeyStaysStableAsToolsAccumulate() {
        val first = listOf(
            AgentEvent.ToolCall(atMillis = 2, toolName = "Grep", summary = "a"),
            AgentEvent.ToolResult(atMillis = 3, toolName = "Grep", summary = "ok", isError = false),
        )
        val grown = first + AgentEvent.ToolCall(atMillis = 4, toolName = "Read", summary = "b")
        assertEquals(
            transcriptDisplayItemKey(TranscriptDisplayItem.ToolCalls(1, first)),
            transcriptDisplayItemKey(TranscriptDisplayItem.ToolCalls(1, grown)),
        )
    }

    @Test
    fun planTextFromAcpTranscriptUsesLastAssistantMessage() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "plan this"),
            AgentEvent.AssistantText(atMillis = 2, text = "Looking at the repo...", isStreamDelta = false),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Read", summary = "README"),
            AgentEvent.AssistantText(atMillis = 4, text = "## Plan\n\n1. First\n2. Second", isStreamDelta = true),
        )

        assertEquals("## Plan\n\n1. First\n2. Second", planTextFromAcpTranscript(events))
    }

    @Test
    fun planTextFromAcpTranscriptPrefersTaskResultFinalText() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "draft", isStreamDelta = false),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "## Final plan\n\nStep one"),
        )

        assertEquals("## Final plan\n\nStep one", planTextFromAcpTranscript(events))
    }

    @Test
    fun planTextFromAcpTranscriptPrefersStructuredPlanMarkdown() {
        val events = listOf(
            AgentEvent.PlanUpdate(atMillis = 1, entries = emptyList(), markdown = "## Plan\n\n1. First"),
            AgentEvent.AssistantText(atMillis = 2, text = "fallback", isStreamDelta = false),
        )

        assertEquals("## Plan\n\n1. First", planTextFromAcpTranscript(events))
    }

    @Test
    fun latestPlanHasPendingEntriesDetectsCursorCreatePlan() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "drafting", isStreamDelta = false),
            AgentEvent.PlanUpdate(
                atMillis = 2,
                entries = listOf(
                    AgentPlanEntry("Add Settings CLI panel", "pending"),
                    AgentPlanEntry("Wire update service", "pending"),
                ),
            ),
        )
        assertTrue(latestPlanHasPendingEntries(events))
    }

    @Test
    fun latestPlanHasPendingEntriesIgnoresPlanAfterLaterUserTurn() {
        val events = listOf(
            AgentEvent.PlanUpdate(
                atMillis = 1,
                entries = listOf(AgentPlanEntry("Ship feature", "pending")),
            ),
            AgentEvent.UserMessage(atMillis = 2, text = "Implement the plan."),
            AgentEvent.AssistantText(atMillis = 3, text = "working", isStreamDelta = false),
        )
        assertFalse(latestPlanHasPendingEntries(events))
    }

    @Test
    fun latestPlanHasPendingEntriesIgnoresCompletedOrClearedPlans() {
        assertFalse(
            latestPlanHasPendingEntries(
                listOf(
                    AgentEvent.PlanUpdate(
                        atMillis = 1,
                        entries = listOf(AgentPlanEntry("Done item", "completed")),
                    ),
                ),
            ),
        )
        assertFalse(
            latestPlanHasPendingEntries(
                listOf(AgentEvent.PlanUpdate(atMillis = 1, entries = emptyList())),
            ),
        )
        assertTrue(
            latestPlanHasPendingEntries(
                listOf(
                    AgentEvent.PlanUpdate(
                        atMillis = 1,
                        entries = emptyList(),
                        markdown = "## Plan\n\n1. First",
                    ),
                ),
            ),
        )
        assertTrue(
            latestPlanHasPendingEntries(
                listOf(
                    AgentEvent.PlanUpdate(
                        atMillis = 1,
                        entries = listOf(AgentPlanEntry("Old", "completed")),
                    ),
                    AgentEvent.PlanUpdate(
                        atMillis = 2,
                        entries = listOf(AgentPlanEntry("New", "pending")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun coalesceAcpTranscriptEventsFoldsManyStreamDeltas() {
        val deltas = (1..4_000).map { index ->
            AgentEvent.AssistantText(atMillis = index.toLong(), text = "x", isStreamDelta = true)
        } + listOf(
            AgentEvent.AssistantText(atMillis = 4_001, text = "\n\n## 1. First step", isStreamDelta = true),
            AgentEvent.AssistantText(atMillis = 4_002, text = "\n\n## 2. Second step", isStreamDelta = true),
            AgentEvent.AssistantText(atMillis = 4_003, text = "\n\n## 3. Third step", isStreamDelta = true),
        )

        val coalesced = coalesceAcpTranscriptEvents(deltas)
        val assistant = coalesced.filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(1, assistant.size)
        assertTrue(assistant.single().text.contains("## 1. First step"))
        assertTrue(assistant.single().text.contains("## 3. Third step"))
        assertEquals(1, coalesced.size)
    }

    @Test
    fun coalesceKeepsStreamStartTimestamp() {
        val merged = coalesceAgentStreamDeltas(
            existing = listOf(AgentEvent.AssistantText(atMillis = 10, text = "Hel", isStreamDelta = true)),
            incoming = listOf(AgentEvent.AssistantText(atMillis = 11, text = "lo", isStreamDelta = true)),
        )
        val text = assertIs<AgentEvent.AssistantText>(merged.single())
        assertEquals(10, text.atMillis)
        assertEquals("Hello", text.text)
    }

    @Test
    fun coalesceCollapsesConsecutivePlanUpdatesIntoLatestSnapshot() {
        val coalesced = coalesceAcpTranscriptEvents(
            listOf(
                AgentEvent.PlanUpdate(
                    atMillis = 1,
                    entries = listOf(AgentPlanEntry("Add resolveHost()", "pending")),
                ),
                AgentEvent.PlanUpdate(
                    atMillis = 2,
                    entries = listOf(
                        AgentPlanEntry("Add resolveHost()", "pending"),
                        AgentPlanEntry("Update suggestNetworkAccessHosts()", "pending"),
                    ),
                ),
            ),
        )

        val plan = assertIs<AgentEvent.PlanUpdate>(coalesced.single())
        assertEquals(2, plan.entries.size)
        assertEquals("Update suggestNetworkAccessHosts()", plan.entries.last().content)
    }

    @Test
    fun coalesceKeepsPlanUpdatesSeparateAcrossABarrier() {
        val coalesced = coalesceAcpTranscriptEvents(
            listOf(
                AgentEvent.PlanUpdate(atMillis = 1, entries = listOf(AgentPlanEntry("First plan", "pending"))),
                AgentEvent.UserMessage(atMillis = 2, text = "Implement the plan."),
                AgentEvent.PlanUpdate(atMillis = 3, entries = listOf(AgentPlanEntry("Second plan", "pending"))),
            ),
        )

        assertEquals(2, coalesced.filterIsInstance<AgentEvent.PlanUpdate>().size)
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
    fun toolDetailsPreserveMarkdownAndFencePlainCode() {
        val markdown = "### Result\n\n- first\n- second"
        assertEquals(markdown, toolDetailMarkdown(markdown))

        val code = "fun main() {\n    println(\"hello\")\n}"
        assertEquals(
            "```kotlin\n$code\n```",
            toolDetailMarkdown(code, "src/Main.kt"),
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

    @Test
    fun fileChangesEventKeyIsStablePerBatch() {
        val event = AgentEvent.FileChanges(
            atMillis = 1,
            batchId = "batch-42",
            baselineTree = "abc",
            snapshot = AgentThreadChangeSnapshot(AgentChangeSummary(emptyList()), emptyMap()),
        )
        assertEquals("file-changes-3-batch-42", transcriptEventKey(3, event))
    }
}
