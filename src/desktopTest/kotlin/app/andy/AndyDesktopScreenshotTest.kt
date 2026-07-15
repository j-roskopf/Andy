package app.andy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.ui.screenshots.AndyScreenshotApp
import app.andy.ui.screenshots.AndyScreenshotScenario
import com.github.takahirom.roborazzi.RoborazziOptions
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

class AndyDesktopScreenshotTest {
    // Sub-pixel antialiasing on CI runners can flip a few pixels against freshly
    // recorded baselines. Keep the threshold tight so real UI changes still fail.
    private val screenshotOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0002f),
    )
    private val routeScenarios = listOf(
        AndyScreenshotScenario.DevicesPopulated,
        AndyScreenshotScenario.CatalogImages,
        AndyScreenshotScenario.LiveMirror,
        AndyScreenshotScenario.AppsDetails,
        AndyScreenshotScenario.LogcatStream,
        AndyScreenshotScenario.IntentsDraft,
        AndyScreenshotScenario.DeviceFiles,
        AndyScreenshotScenario.ComputerFiles,
        AndyScreenshotScenario.NetworkCapture,
    )

    private val workspaceScenarios = listOf(
        AndyScreenshotScenario.AgentsCompletedDiff,
        AndyScreenshotScenario.SnapshotsPopulated,
        AndyScreenshotScenario.ControlsHardware,
        AndyScreenshotScenario.PerformanceSamples,
        AndyScreenshotScenario.DesignOverlay,
        AndyScreenshotScenario.AccessibilityHierarchy,
        AndyScreenshotScenario.BugsReplay,
        AndyScreenshotScenario.SettingsMcp,
        AndyScreenshotScenario.MirrorPopOut,
    )

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopDeviceAndNetworkSurfaces() = capture(routeScenarios)

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopWorkspaceAndDiagnosticsSurfaces() = capture(workspaceScenarios)

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectWorkflowList() = capture(listOf(AndyScreenshotScenario.ProjectsWorkflows))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectSpecDetail() = capture(listOf(AndyScreenshotScenario.ProjectsSpecDetail))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectBuildDetail() = capture(listOf(AndyScreenshotScenario.ProjectsBuildDetail))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectVerificationDetail() = capture(listOf(AndyScreenshotScenario.ProjectsVerification))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectBlockingReviewDetail() = capture(listOf(AndyScreenshotScenario.ProjectsReviewBlocking))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectDisabledReviewDetail() = capture(listOf(AndyScreenshotScenario.ProjectsReviewDisabled))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectProfiles() = capture(listOf(AndyScreenshotScenario.ProjectsProfiles))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectNewSpec() = capture(listOf(AndyScreenshotScenario.ProjectsNewSpec))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectNewBuild() = capture(listOf(AndyScreenshotScenario.ProjectsNewBuild))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectRunbook() = capture(listOf(AndyScreenshotScenario.ProjectsRunbook))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectScratchpad() = capture(listOf(AndyScreenshotScenario.ProjectsScratchpad))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopProjectScratchpadEditor() = capture(listOf(AndyScreenshotScenario.ProjectsScratchpadEditor))

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    private fun capture(scenarios: List<AndyScreenshotScenario>) {
        val previousRenderer = System.getProperty("andy.screenshot.renderer")
        System.setProperty("andy.screenshot.renderer", "compose")
        try {
            runDesktopComposeUiTest(width = 1365, height = 900) {
                scenarios.forEach { scenario ->
                    var redrawTick by mutableStateOf(0)
                    setContent {
                        Box(
                            Modifier.size(1365.dp, 900.dp).drawWithContent {
                                redrawTick
                                drawContent()
                            },
                        ) {
                            AndyScreenshotApp(scenario, ScreenshotServices.create())
                        }
                    }
                    waitForIdle()
                    scenario.projectInteraction?.let { label ->
                        onNodeWithText(label).performClick()
                        waitForIdle()
                    }
                    runOnUiThread { redrawTick++ }
                    waitForIdle()
                    val captureTarget = when (scenario) {
                        AndyScreenshotScenario.MirrorPopOut -> onRoot()
                        AndyScreenshotScenario.ProjectsSpecDetail,
                        AndyScreenshotScenario.ProjectsBuildDetail,
                        AndyScreenshotScenario.ProjectsVerification,
                        AndyScreenshotScenario.ProjectsReviewBlocking,
                        AndyScreenshotScenario.ProjectsReviewDisabled,
                        -> onNodeWithTag("project-task-dock")
                        AndyScreenshotScenario.ProjectsRunbook -> onNodeWithTag("project-canvas")
                        else -> onNode(isRoot() and hasAnyDescendant(hasText("devices")))
                    }
                    captureTarget.captureRoboImage(
                        filePath = "src/screenshotTest/roborazzi/${baselinePlatform()}/${scenario.fileName}",
                        roborazziOptions = screenshotOptions,
                    )
                }
            }
        } finally {
            if (previousRenderer == null) System.clearProperty("andy.screenshot.renderer")
            else System.setProperty("andy.screenshot.renderer", previousRenderer)
        }
    }

    private fun baselinePlatform(): String = when {
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").contains("windows", ignoreCase = true) -> "windows"
        else -> "linux"
    }

    private val AndyScreenshotScenario.projectInteraction: String?
        get() = when (this) {
            AndyScreenshotScenario.ProjectsProfiles -> "Profiles"
            AndyScreenshotScenario.ProjectsNewSpec -> "New spec"
            AndyScreenshotScenario.ProjectsNewBuild -> "New build"
            AndyScreenshotScenario.ProjectsScratchpadEditor -> "edit"
            else -> null
        }
}
