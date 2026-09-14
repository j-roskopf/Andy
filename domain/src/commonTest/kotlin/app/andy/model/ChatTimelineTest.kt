package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatTimelineTest {
    @Test
    fun buildsRowsTurnsAndExpandsContextFromSkillsAndAttachments() {
        val events = listOf(
            AgentEvent.SessionStarted(atMillis = 1_000, sessionId = "s1", model = "gpt-test"),
            AgentEvent.UserMessage(
                atMillis = 2_000,
                text = "hello",
                skills = listOf(AgentSkill(name = "compose-expert", description = "Compose help", path = "/skills/compose")),
                attachments = listOf(
                    AgentAttachment(
                        id = "att-1",
                        displayName = "notes.txt",
                        byteCount = 2048,
                        sha256 = "abc",
                    ),
                ),
            ),
            AgentEvent.AssistantText(atMillis = 3_000, text = "Hi there"),
            AgentEvent.ToolCall(
                atMillis = 4_000,
                toolName = "bash",
                summary = """{"command":"pwd"}""",
                detail = """{"command":"pwd"}""" + AcpToolCallPresentation.DetailSeparator + "/tmp",
                toolCallId = "tc-1",
                startedAtMillis = 3_500,
                endedAtMillis = 4_000,
            ),
            AgentEvent.TaskResult(
                atMillis = 5_000,
                success = true,
                finalText = "done",
                inputTokens = 10,
                outputTokens = 20,
                durationMs = 3_000,
            ),
            AgentEvent.UserMessage(atMillis = 6_000, text = "follow up"),
            AgentEvent.AssistantText(atMillis = 7_000, text = "ok"),
        )

        val model = buildChatTimeline(events)

        assertEquals(2, model.turns.size)
        assertEquals(1, model.turns[0].number)
        assertEquals(2, model.turns[1].number)
        assertEquals(1, model.turns[0].toolCallCount)
        assertEquals(10, model.turns[0].inputTokens)
        assertEquals(20, model.turns[0].outputTokens)
        assertEquals(1, model.totalCalls)

        val kinds = model.rows.map { it.kind }
        assertTrue(TimelineRowKind.System in kinds)
        assertTrue(TimelineRowKind.User in kinds)
        assertTrue(TimelineRowKind.Context in kinds)
        assertTrue(TimelineRowKind.Assistant in kinds)
        assertTrue(TimelineRowKind.Tool in kinds)
        assertTrue(TimelineRowKind.Result in kinds)

        val contextTitles = model.rows.filter { it.kind == TimelineRowKind.Context }.map { it.title }
        assertTrue(contextTitles.any { it.contains("compose-expert") })
        assertTrue(contextTitles.any { it.contains("notes.txt") })

        val tool = model.rows.first { it.kind == TimelineRowKind.Tool }
        assertEquals("TOOL", tool.badge)
        assertEquals("/tmp", tool.resultPreview)
        assertFalse(tool.timingApproximate)
        assertEquals(3_500, tool.startMillis)
        assertEquals(4_000, tool.endMillis)
    }

    @Test
    fun legacyToolTimingFallsBackToGapApproximation() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1_000, text = "hi"),
            AgentEvent.ToolCall(
                atMillis = 2_000,
                toolName = "read",
                summary = "file",
                detail = "file",
                // no started/ended — legacy row
            ),
            AgentEvent.AssistantText(atMillis = 3_000, text = "done"),
        )
        val model = buildChatTimeline(events)
        val tool = model.rows.first { it.kind == TimelineRowKind.Tool }
        assertTrue(tool.timingApproximate)
        assertEquals(1_000, tool.startMillis) // previous event
        assertEquals(2_000, tool.endMillis)
    }

    @Test
    fun brushSelectionUsesDurationToggleAndStepAxes() {
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
        assertEquals(2, model.totalCalls)
        assertEquals(4, model.rows.size)

        val earlyDuration = timelineBrushSelection(model, TimelineAxis.Turns, 0f, 0.4f, durationMode = true)
        assertTrue(earlyDuration.any { it.startsWith("user-") || it.startsWith("tool-1") })
        assertFalse(earlyDuration.contains("tool-2"))

        // Duration off: equal-width step cells filling 0..1 (4 rows → 0.25 each).
        val firstHalfSteps = timelineBrushSelection(model, TimelineAxis.Turns, 0f, 0.49f, durationMode = false)
        assertTrue(model.rows.filter { it.stepIndex <= 1 }.all { it.key in firstHalfSteps })
        assertTrue(model.rows.filter { it.stepIndex >= 2 }.none { it.key in firstHalfSteps })

        val firstHalfCalls = timelineBrushSelection(model, TimelineAxis.Calls, 0f, 0.49f, durationMode = false)
        assertTrue("tool-1" in firstHalfCalls)
        assertFalse("tool-2" in firstHalfCalls)
    }

    @Test
    fun nonDurationProjectionTilesEqualWidthCellsAcrossFullSpan() {
        val model = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 0, text = "a"),
                AgentEvent.AssistantText(atMillis = 10, text = "b"),
                AgentEvent.ToolCall(
                    atMillis = 20,
                    toolName = "t",
                    summary = "x",
                    detail = "x",
                    toolCallId = "1",
                    startedAtMillis = 15,
                    endedAtMillis = 20,
                ),
            ),
        )
        assertEquals(3, model.rows.size)
        model.rows.forEachIndexed { index, row ->
            val (start, end) = row.projectedInterval(model, TimelineAxis.Turns, durationMode = false)
            assertEquals(index / 3f, start, 1e-4f)
            assertEquals((index + 1) / 3f, end, 1e-4f)
            val (cStart, cEnd) = row.projectedInterval(model, TimelineAxis.Calls, durationMode = false)
            assertEquals(start, cStart, 1e-4f)
            assertEquals(end, cEnd, 1e-4f)
        }
        assertEquals(0f, model.rows.first().projectedInterval(model, TimelineAxis.Turns, false).first, 1e-4f)
        assertEquals(1f, model.rows.last().projectedInterval(model, TimelineAxis.Turns, false).second, 1e-4f)
    }

    @Test
    fun durationProjectionPacksByRelativeDurationWithoutIdleGaps() {
        val model = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 0, text = "a"),
                AgentEvent.AssistantText(atMillis = 100, text = "think"),
                AgentEvent.ToolCall(
                    atMillis = 300,
                    toolName = "bash",
                    summary = "pwd",
                    detail = "pwd",
                    toolCallId = "1",
                    startedAtMillis = 200,
                    endedAtMillis = 300,
                ),
            ),
        )
        val user = model.rows.first { it.kind == TimelineRowKind.User }
        val assistant = model.rows.first { it.kind == TimelineRowKind.Assistant }
        val tool = model.rows.first { it.kind == TimelineRowKind.Tool }

        // Display intervals still capture per-step duration for weighting.
        assertEquals(0, user.displayStartMillis)
        assertEquals(100, user.displayEndMillis)
        assertEquals(100, assistant.displayStartMillis)
        assertEquals(200, assistant.displayEndMillis)
        assertEquals(200, tool.displayStartMillis)
        assertEquals(300, tool.displayEndMillis)

        val (u0, u1) = user.projectedInterval(model, TimelineAxis.Turns, durationMode = true)
        val (a0, a1) = assistant.projectedInterval(model, TimelineAxis.Turns, durationMode = true)
        val (t0, t1) = tool.projectedInterval(model, TimelineAxis.Turns, durationMode = true)

        // Contiguous pack covering the full axis — no idle holes between steps.
        assertEquals(0f, u0, 1e-4f)
        assertEquals(u1, a0, 1e-4f)
        assertEquals(a1, t0, 1e-4f)
        assertEquals(1f, t1, 1e-4f)
        // Equal 100ms weights → equal thirds.
        assertEquals(1f / 3f, u1 - u0, 1e-4f)
        assertEquals(1f / 3f, a1 - a0, 1e-4f)
        assertEquals(1f / 3f, t1 - t0, 1e-4f)
    }

    @Test
    fun durationProjectionGivesLongerStepsMoreWidth() {
        val model = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 0, text = "hi"),
                AgentEvent.ToolCall(
                    atMillis = 1_000,
                    toolName = "slow",
                    summary = "work",
                    detail = "work",
                    toolCallId = "1",
                    startedAtMillis = 100,
                    endedAtMillis = 1_000,
                ),
                AgentEvent.AssistantText(atMillis = 1_100, text = "done"),
            ),
        )
        val user = model.rows.first { it.kind == TimelineRowKind.User }
        val tool = model.rows.first { it.kind == TimelineRowKind.Tool }
        val assistant = model.rows.first { it.kind == TimelineRowKind.Assistant }
        val (_, u1) = user.projectedInterval(model, TimelineAxis.Calls, durationMode = true)
        val (t0, t1) = tool.projectedInterval(model, TimelineAxis.Calls, durationMode = true)
        val (a0, a1) = assistant.projectedInterval(model, TimelineAxis.Calls, durationMode = true)
        assertTrue(t1 - t0 > u1 - 0f)
        assertTrue(t1 - t0 > a1 - a0)
        assertEquals(u1, t0, 1e-4f)
        assertEquals(t1, a0, 1e-4f)
        assertEquals(1f, a1, 1e-4f)
    }

    @Test
    fun filterMatchesTitleAndResultPreview() {
        val model = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 1, text = "hello world"),
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "bash",
                    summary = "pwd",
                    detail = """{"command":"pwd"}""" + AcpToolCallPresentation.DetailSeparator + "/Users/demo",
                    toolCallId = "x",
                    startedAtMillis = 2,
                    endedAtMillis = 3,
                ),
            ),
        )
        assertEquals(model.rows.size, timelineFilterRows(model, "").size)
        val hello = timelineFilterRows(model, "HELLO")
        assertTrue(hello.any { it.startsWith("user-") })
        val path = timelineFilterRows(model, "/Users/demo")
        assertTrue(path.contains("tool-x"))
        assertTrue(timelineFilterRows(model, "nope").isEmpty())
    }

    @Test
    fun toolCallDetailIncludesKindLocationsAndFullDetail() {
        val event = AgentEvent.ToolCall(
            atMillis = 10,
            toolName = "bash",
            summary = "pwd",
            detail = """{"command":"pwd"}""" + AcpToolCallPresentation.DetailSeparator + "/tmp",
            toolCallId = "call-9",
            kind = AgentToolKind.Execute,
            state = AgentToolState.Completed,
            locations = listOf("/Users/demo/repo"),
            startedAtMillis = 5,
            endedAtMillis = 10,
        )
        val model = buildChatTimeline(listOf(AgentEvent.UserMessage(1, "go"), event))
        val row = model.rows.first { it.kind == TimelineRowKind.Tool }
        val detail = timelineRowDetail(row, event, model.turns.first())
        assertTrue(detail.summary.contains("Execute"))
        assertTrue(detail.summary.contains("call-9"))
        assertTrue(detail.summary.contains("/Users/demo/repo"))
        assertEquals("""{"command":"pwd"}""", detail.payload)
        assertEquals("/tmp", detail.result)
        assertEquals("Tool call timestamps", detail.timing?.sourceLabel)
        assertEquals(5L, detail.timing?.durationMs)
    }

    @Test
    fun userAndContextDetailsExpandSkillsAndAttachments() {
        val skill = AgentSkill(
            name = "compose-expert",
            description = "Compose help",
            path = "/skills/compose/SKILL.md",
            userInvocable = true,
        )
        val attachment = AgentAttachment(
            id = "att-1",
            displayName = "notes.txt",
            byteCount = 2048,
            sha256 = "abcdef1234567890",
            relativePath = ".andy/t/attachments/notes.txt",
        )
        val event = AgentEvent.UserMessage(
            atMillis = 2,
            text = "hello",
            skills = listOf(skill),
            attachments = listOf(attachment),
            imagePaths = listOf("/tmp/shot.png"),
        )
        val model = buildChatTimeline(listOf(event))
        val userRow = model.rows.first { it.kind == TimelineRowKind.User }
        val userDetail = timelineRowDetail(userRow, event)
        assertTrue(userDetail.summary.contains("compose-expert"))
        assertTrue(userDetail.summary.contains("/skills/compose/SKILL.md"))
        assertTrue(userDetail.summary.contains("notes.txt"))
        assertTrue(userDetail.summary.contains("/tmp/shot.png"))

        val skillRow = model.rows.first { it.key.startsWith("ctx-skill-") }
        val skillDetail = timelineRowDetail(skillRow, event)
        assertTrue(skillDetail.summary.contains("Compose help"))
        assertEquals("/skills/compose/SKILL.md", skillDetail.payload)

        val attachRow = model.rows.first { it.key.startsWith("ctx-attach-") }
        val attachDetail = timelineRowDetail(attachRow, event)
        assertTrue(attachDetail.summary.contains("att-1"))
        assertTrue(attachDetail.summary.contains("abcdef1234567890"))
    }

    @Test
    fun taskResultDetailIncludesCostAndTokens() {
        val event = AgentEvent.TaskResult(
            atMillis = 9,
            success = true,
            finalText = "all good",
            costUsd = 0.12,
            costIsEstimated = true,
            inputTokens = 100,
            outputTokens = 40,
            durationMs = 1500,
        )
        val model = buildChatTimeline(listOf(AgentEvent.UserMessage(1, "go"), event))
        val row = model.rows.first { it.kind == TimelineRowKind.Result }
        val detail = timelineRowDetail(row, event)
        assertTrue(detail.summary.contains("0.12"))
        assertTrue(detail.summary.contains("estimated"))
        assertTrue(detail.summary.contains("100"))
        assertTrue(detail.summary.contains("all good"))
        assertTrue(detail.payload?.contains("costUsd=0.12") == true)
        assertEquals("all good", detail.result)
        assertEquals(1500L, detail.timing?.durationMs)
    }

    @Test
    fun fileChangesDetailListsPathsAndCounts() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(
                files = listOf(
                    AgentFileChange(path = "test1.txt", additions = 3, deletions = 0),
                    AgentFileChange(path = "src/Main.kt", additions = 10, deletions = 2),
                ),
            ),
            diffs = mapOf(
                "test1.txt" to AgentFileDiff(
                    path = "test1.txt",
                    lines = listOf(
                        DiffLine(DiffLineKind.Addition, "hello"),
                        DiffLine(DiffLineKind.Addition, "world"),
                    ),
                ),
            ),
        )
        val event = AgentEvent.FileChanges(
            atMillis = 5,
            batchId = "batch-1",
            baselineTree = "abc",
            snapshot = snapshot,
        )
        val model = buildChatTimeline(listOf(AgentEvent.UserMessage(1, "edit"), event))
        val row = model.rows.first { it.kind == TimelineRowKind.FileChanges }
        val detail = timelineRowDetail(row, event)
        assertTrue(detail.summary.contains("test1.txt"))
        assertTrue(detail.summary.contains("src/Main.kt"))
        assertTrue(detail.summary.contains("+13") || detail.summary.contains("`+13`"))
        assertTrue(detail.payload?.contains("test1.txt") == true)
        assertTrue(detail.result?.contains("```diff") == true)
        assertTrue(detail.result?.contains("+hello") == true)
        assertTrue(row.title.contains("File changes") || row.resultPreview?.contains("2 files") == true)
    }

    @Test
    fun coalescesStreamDeltasIntoSingleAssistantRow() {
        val model = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 1, text = "hi"),
                AgentEvent.AssistantText(atMillis = 2, text = "Hel", isStreamDelta = true),
                AgentEvent.AssistantText(atMillis = 3, text = "lo", isStreamDelta = true),
            ),
        )
        assertEquals(1, model.rows.count { it.kind == TimelineRowKind.Assistant })
        assertEquals("Hello", model.rows.first { it.kind == TimelineRowKind.Assistant }.title)
    }

    @Test
    fun suppressesResultRowWhenMatchingStoredCallExists() {
        val paired = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 1, text = "hi"),
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "bash",
                    summary = "pwd",
                    detail = "pwd",
                    toolCallId = "call-1",
                    startedAtMillis = 1,
                    endedAtMillis = 2,
                ),
                AgentEvent.ToolResult(atMillis = 3, toolName = "bash", summary = "pwd", detail = "/tmp", isError = false),
            ),
        )
        assertEquals(1, paired.rows.count { it.kind == TimelineRowKind.Tool })
        assertEquals(1, paired.turns.first().toolCallCount)

        val orphan = buildChatTimeline(
            listOf(
                AgentEvent.UserMessage(atMillis = 1, text = "hi"),
                AgentEvent.ToolResult(atMillis = 3, toolName = "bash", summary = "pwd", detail = "/tmp", isError = false),
            ),
        )
        assertTrue(orphan.rows.any { it.key == "tool-result-1" })
    }
}
