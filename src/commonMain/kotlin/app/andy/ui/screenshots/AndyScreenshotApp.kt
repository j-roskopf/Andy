package app.andy.ui.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.AndyApp
import app.andy.AndyDestination
import app.andy.AndyMirrorPopOut
import app.andy.service.AndyServices

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
    AgentsCompletedDiff("desktop-agents-completed-diff.png", AndyDestination.Agents),
    SnapshotsPopulated("desktop-snapshots-populated.png", AndyDestination.Snapshots),
    ControlsHardware("desktop-controls-hardware.png", AndyDestination.Controls),
    PerformanceSamples("desktop-performance-samples.png", AndyDestination.Performance),
    DesignOverlay("desktop-design-overlay.png", AndyDestination.Design),
    AccessibilityHierarchy("desktop-accessibility-hierarchy.png", AndyDestination.Accessibility),
    BugsReplay("desktop-bugs-replay.png", AndyDestination.Bugs),
    SettingsMcp("desktop-settings-mcp.png", AndyDestination.Settings),
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
                else -> null
            },
        )
    }
}

/** Values shared by the harness and its desktop fakes; deliberately stable. */
internal object ScreenshotFixture {
    const val serial = "emulator-5554"
    const val nowMillis = 1_735_689_600_000L // 2025-01-01T00:00:00Z
}
