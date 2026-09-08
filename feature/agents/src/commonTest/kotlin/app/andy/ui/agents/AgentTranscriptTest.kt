package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentSkill
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.TranscriptDisplayItem
import app.andy.model.promptWithSkillHints
import app.andy.ui.components.ChatBubbleGroup
import app.andy.ui.components.ChatBubbleSender
import app.andy.model.AgentPlanEntry
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.latestPlanHasPendingEntries
import app.andy.model.planTextFromAcpTranscript
import app.andy.model.transcriptDisplayItems
import app.andy.model.withLinkedChildSpawnItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTranscriptTest {
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
    fun fileChangesEventKeyIsStablePerBatch() {
        val event = AgentEvent.FileChanges(
            atMillis = 1,
            batchId = "batch-42",
            baselineTree = "abc",
            snapshot = AgentThreadChangeSnapshot(AgentChangeSummary(emptyList()), emptyMap()),
        )
        assertEquals("file-changes-3-batch-42", transcriptEventKey(3, event))
    }

    @Test
    fun withLinkedChildSpawnItemsInsertsAtChildCreatedTime() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 10, text = "advise me"),
            AgentEvent.AssistantText(atMillis = 20, text = "spinning up"),
            AgentEvent.ToolCall(
                atMillis = 30,
                toolName = "MCP: tool",
                summary = "MCP: tool",
                detail = "MCP: tool\n- **success:** true",
            ),
            AgentEvent.AssistantText(atMillis = 50, text = "advisor finished"),
        )
        val base = transcriptDisplayItems(events)
        val child = app.andy.model.AgentTask(
            id = "task-child00001",
            title = "[Advisor] strategy",
            prompt = "You ARE the advisor.",
            agent = app.andy.model.AgentKind.Codex,
            createdAtMillis = 35,
            parentChatTaskId = "task-parent0001",
        )
        val merged = withLinkedChildSpawnItems(base, listOf(child))
        val spawnIndex = merged.indexOfFirst { it is TranscriptDisplayItem.ChildSpawns }
        assertTrue(spawnIndex >= 0)
        // Inserted after the opaque MCP tool (30) and before the later assistant text (50).
        assertTrue(spawnIndex > 0)
        assertIs<TranscriptDisplayItem.ChildSpawns>(merged[spawnIndex])
        assertEquals("task-child00001", (merged[spawnIndex] as TranscriptDisplayItem.ChildSpawns).tasks.single().id)
        assertEquals(
            "child-spawns-task-child00001",
            transcriptDisplayItemKey(merged[spawnIndex]),
        )
    }
}
