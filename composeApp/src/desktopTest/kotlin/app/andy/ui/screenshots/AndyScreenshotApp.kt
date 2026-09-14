package app.andy.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.AndyApp
import app.andy.AndyDestination
import app.andy.AndyMirrorPopOut
import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.AgentSkill
import app.andy.model.TimelineAxis
import app.andy.model.TimelineRowKind
import app.andy.model.buildChatTimeline
import app.andy.model.timelineBrushSelection
import app.andy.model.timelineFilterRows
import app.andy.model.timelineRowDetail
import app.andy.service.AndyServices
import app.andy.ui.agents.ChatTimelineDetailPane
import app.andy.ui.agents.ChatTimelineView
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyTheme

/**
 * A stable catalog for desktop visual regression captures. Each scenario is an
 * intentional product state, rather than a test that happens to navigate there.
 */
internal enum class AndyScreenshotScenario(
    val fileName: String,
    val destination: AndyDestination? = null,
) {
    DevicesPopulated("desktop-devices-populated.png", AndyDestination.Devices),
    CatalogImages("desktop-catalog-images.png", AndyDestination.Catalog),
    LiveMirror("desktop-live-mirror.png", AndyDestination.Live),
    AppsDetails("desktop-apps-details.png", AndyDestination.Apps),
    LogcatStream("desktop-logcat-stream.png", AndyDestination.Logcat),
    IntentsDraft("desktop-intents-draft.png", AndyDestination.Intents),
    DeviceFiles("desktop-device-files.png", AndyDestination.Files),
    SharedPreferences("desktop-shared-preferences.png", AndyDestination.Files),
    AppDatabase("desktop-app-database.png", AndyDestination.Files),
    ComputerFiles("desktop-computer-files.png", AndyDestination.ComputerFiles),
    NetworkCapture("desktop-network-capture.png", AndyDestination.Network),
    ProjectsWorkflows("desktop-projects-workflows.png", AndyDestination.Actions),
    ProjectsSpecDetail("desktop-projects-spec-detail.png", AndyDestination.Actions),
    ProjectsBuildDetail("desktop-projects-build-detail.png", AndyDestination.Actions),
    ProjectsVerification("desktop-projects-verification.png", AndyDestination.Actions),
    ProjectsReviewBlocking("desktop-projects-review-blocking.png", AndyDestination.Actions),
    ProjectsReviewDisabled("desktop-projects-review-disabled.png", AndyDestination.Actions),
    ProjectsProfiles("desktop-projects-profiles.png", AndyDestination.Actions),
    ProjectsNewSpec("desktop-projects-new-spec.png", AndyDestination.Actions),
    ProjectsNewBuild("desktop-projects-new-build.png", AndyDestination.Actions),
    ProjectsRunbook("desktop-projects-runbook.png", AndyDestination.Actions),
    ProjectsSessions("desktop-projects-sessions.png", AndyDestination.Actions),
    ProjectsScratchpad("desktop-projects-scratchpad.png", AndyDestination.Actions),
    ProjectsScratchpadEditor("desktop-projects-scratchpad-editor.png", AndyDestination.Actions),
    ProjectsKanbanBoard("desktop-projects-kanban-board.png", AndyDestination.Actions),
    ProjectsAutomations("desktop-projects-automations.png", AndyDestination.Actions),
    AgentsCompletedDiff("desktop-agents-completed-diff.png", AndyDestination.Agents),
    /** Isolated timeline surface — avoids Agents inbox/composer navigation for a stable baseline. */
    AgentsTimeline("desktop-agents-timeline.png"),
    SnapshotsPopulated("desktop-snapshots-populated.png", AndyDestination.Snapshots),
    ControlsHardware("desktop-controls-hardware.png", AndyDestination.Controls),
    PerformanceSamples("desktop-performance-samples.png", AndyDestination.Performance),
    TracingPerfetto("desktop-tracing-perfetto.png", AndyDestination.Performance),
    DesignOverlay("desktop-design-overlay.png", AndyDestination.Design),
    InspectorHierarchy("desktop-inspector-hierarchy.png", AndyDestination.Inspector),
    BugsReplay("desktop-bugs-replay.png", AndyDestination.Bugs),
    RecordingsExport("desktop-recordings-export.png", AndyDestination.Recordings),
    SettingsMcp("desktop-settings-mcp.png", AndyDestination.Settings),
    SettingsNetworkAccess("desktop-settings-network-access.png", AndyDestination.Settings),
    HostRemoteConnected("desktop-host-remote-connected.png", AndyDestination.Devices),
    MirrorPopOut("desktop-mirror-pop-out.png"),
}

/**
 * Shared app entry point used by every desktop screenshot test. The caller owns
 * the services so production code never receives a screenshot/testing mode.
 */
@Composable
internal fun AndyScreenshotApp(
    scenario: AndyScreenshotScenario,
    services: AndyServices,
    modifier: Modifier = Modifier,
) {
    when (scenario) {
        AndyScreenshotScenario.MirrorPopOut -> AndyMirrorPopOut(
            services = services,
            serial = ScreenshotFixture.serial,
            deviceName = "Pixel 8 API 36",
            controlsVisible = true,
        )
        AndyScreenshotScenario.AgentsTimeline -> AgentsTimelineScreenshot(modifier.fillMaxSize())
        else -> AndyApp(
            services = services,
            requestedDestination = scenario.destination,
            contentTopPadding = 0.dp,
            initialProjectTaskId = when (scenario) {
                AndyScreenshotScenario.ProjectsSpecDetail -> "spec-checkout"
                AndyScreenshotScenario.ProjectsBuildDetail -> "build-checkout"
                AndyScreenshotScenario.ProjectsVerification -> "verify-checkout"
                AndyScreenshotScenario.ProjectsReviewBlocking -> "review-checkout"
                AndyScreenshotScenario.ProjectsReviewDisabled -> "review-search"
                else -> null
            },
            initialProjectTab = when (scenario) {
                AndyScreenshotScenario.ProjectsWorkflows -> "tasks"
                AndyScreenshotScenario.ProjectsRunbook -> "runbook"
                AndyScreenshotScenario.ProjectsSessions -> "sessions"
                AndyScreenshotScenario.ProjectsScratchpad,
                AndyScreenshotScenario.ProjectsScratchpadEditor,
                -> "scratchpad"
                AndyScreenshotScenario.ProjectsAutomations -> "automations"
                else -> null
            },
        )
    }
}

@Composable
private fun AgentsTimelineScreenshot(modifier: Modifier = Modifier) {
    val now = ScreenshotFixture.nowMillis
    val events = remember {
        listOf(
            AgentEvent.SessionStarted(now - 39_500, sessionId = "sess-1", model = "gpt-5.2-codex"),
            AgentEvent.UserMessage(
                atMillis = now - 39_000,
                text = "Fix the empty postal code validation and add a regression test.",
                skills = listOf(AgentSkill("compose-expert", "Compose UI guidance", "/skills/compose-expert/SKILL.md")),
            ),
            AgentEvent.ToolCall(
                atMillis = now - 31_000,
                toolName = "rg",
                summary = "Find validation reducer",
                detail = """{"pattern":"postalCode"}""" + AcpToolCallPresentation.DetailSeparator + "CheckoutReducer.kt",
                toolCallId = "call-1",
                startedAtMillis = now - 31_500,
                endedAtMillis = now - 31_000,
            ),
            AgentEvent.AssistantText(now - 5_000, "Updated the reducer and added a focused test."),
            AgentEvent.TaskResult(
                atMillis = now - 3_000,
                success = true,
                finalText = "Checkout validation is covered.",
                costUsd = 0.18,
                inputTokens = 2420,
                outputTokens = 860,
                durationMs = 36_000,
            ),
        )
    }
    val model = remember(events) { buildChatTimeline(events) }
    var selected by remember { mutableStateOf(model.rows.firstOrNull { it.kind == TimelineRowKind.Tool }?.key) }
    var brushStart by remember { mutableStateOf<Float?>(0f) }
    var brushEnd by remember { mutableStateOf<Float?>(0.45f) }
    val brushed = remember(model, brushStart, brushEnd) {
        val start = brushStart ?: return@remember emptySet()
        val end = brushEnd ?: return@remember emptySet()
        timelineBrushSelection(model, TimelineAxis.Calls, start, end, durationMode = false)
    }
    val detail = remember(selected, model, events) {
        val row = model.rows.firstOrNull { it.key == selected } ?: return@remember null
        val event = row.eventIndex.takeIf { it >= 0 }?.let { events.getOrNull(it) }
        timelineRowDetail(row, event, model.turns.firstOrNull { it.number == row.turnNumber })
    }

    AndyTheme {
        Column(
            modifier
                .background(AndyColors.PaneBg)
                .fillMaxSize(),
        ) {
            Row(Modifier.weight(1f).fillMaxSize()) {
                ChatTimelineView(
                    model = model,
                    axis = TimelineAxis.Calls,
                    onAxisChange = {},
                    durationMode = false,
                    onDurationModeChange = {},
                    selectedRowKey = selected,
                    onSelectRow = { selected = it },
                    brushedKeys = brushed,
                    brushStart = brushStart,
                    brushEnd = brushEnd,
                    onBrushChange = { start, end ->
                        brushStart = start
                        brushEnd = end
                    },
                    searchQuery = "",
                    onSearchQueryChange = {},
                    matchingKeys = timelineFilterRows(model, ""),
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
                detail?.let {
                    ChatTimelineDetailPane(
                        detail = it,
                        onClose = { selected = null },
                        modifier = Modifier.width(420.dp).fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Values shared by the harness and its desktop fakes; deliberately stable. */
internal object ScreenshotFixture {
    const val serial = "emulator-5554"
    const val nowMillis = 1_735_689_600_000L // 2025-01-01T00:00:00Z
}
