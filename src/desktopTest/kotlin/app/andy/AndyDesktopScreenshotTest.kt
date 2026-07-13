package app.andy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.andy.ui.screenshots.AndyScreenshotApp
import app.andy.ui.screenshots.AndyScreenshotScenario
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

class AndyDesktopScreenshotTest {
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
        AndyScreenshotScenario.ProjectsPopulated,
        AndyScreenshotScenario.ProjectsActions,
        AndyScreenshotScenario.ProjectsNotes,
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
    private fun capture(scenarios: List<AndyScreenshotScenario>) {
        val previousRenderer = System.getProperty("andy.screenshot.renderer")
        System.setProperty("andy.screenshot.renderer", "compose")
        try {
            runDesktopComposeUiTest(width = 1365, height = 900) {
                scenarios.forEach { scenario ->
                    setContent {
                        Box(Modifier.size(1365.dp, 900.dp)) {
                            AndyScreenshotApp(scenario, ScreenshotServices.create())
                        }
                    }
                    waitForIdle()
                    scenario.projectCanvasTab?.let { tab ->
                        onNodeWithText(tab).performClick()
                        waitForIdle()
                    }
                    onRoot().captureRoboImage(filePath = "src/screenshotTest/roborazzi/${baselinePlatform()}/${scenario.fileName}")
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

    private val AndyScreenshotScenario.projectCanvasTab: String?
        get() = when (this) {
            AndyScreenshotScenario.ProjectsActions -> "actions"
            AndyScreenshotScenario.ProjectsNotes -> "notes"
            else -> null
        }
}
