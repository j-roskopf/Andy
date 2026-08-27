package app.andy.desktop.service

import app.andy.service.DeviceService

import app.andy.desktop.parser.AndroidParsers
import app.andy.domain.filterInvisible
import app.andy.model.AccessibilityNode
import app.andy.model.HierarchyOptions
import app.andy.model.HierarchySnapshot
import app.andy.model.HierarchySource
import app.andy.service.ViewHierarchyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier-1/tier-2 view hierarchy capture (§D.3): `uiautomator dump` (semantics tree, richer
 * text/content-description) enriched with `dumpsys activity top`'s unmerged view tree — the view
 * classes and ids Compose collapses out of the accessibility tree — matched by bounds + class
 * name in [AndroidParsers.mergeViewHierarchy]. `dumpsys window` supplies window z-order
 * separately for the layer view.
 */
class DesktopViewHierarchyService(
    private val runner: CommandRunner,
    private val devices: DeviceService,
) : ViewHierarchyService {
    override suspend fun capture(serial: String, options: HierarchyOptions): Result<HierarchySnapshot> =
        withContext(Dispatchers.IO) {
            val adb = devices.adbPath() ?: return@withContext Result.failure(Exception("ADB not found"))

            val dumpArgs = buildList {
                add(adb); add("-s"); add(serial); add("shell"); add("uiautomator"); add("dump")
                if (options.compressed) add("--compressed")
                add("/sdcard/andy_view_hierarchy.xml")
            }
            runner.run(dumpArgs, 10)
            val xmlResult = runner.run(listOf(adb, "-s", serial, "exec-out", "cat", "/sdcard/andy_view_hierarchy.xml"), 10)
            val uiautomatorRoot = xmlResult.stdout.takeIf { it.isNotBlank() }
                ?.let { xml -> runCatching { AndroidParsers.parseAccessibilityXml(xml) }.getOrNull() }

            val activityTopOutput = devices.shell(serial, listOf("dumpsys", "activity", "top")).stdout
            val activityTopRoot = runCatching {
                AndroidParsers.parseActivityTopHierarchy(activityTopOutput)
                    ?.let { AndroidParsers.attachScrollOffsetsFromActivityTop(activityTopOutput, it) }
            }.getOrNull()

            val useUnmerged = options.unmergedSemantics && activityTopRoot != null
            var root: AccessibilityNode? = when {
                useUnmerged -> activityTopRoot
                uiautomatorRoot != null -> AndroidParsers.mergeViewHierarchy(uiautomatorRoot, activityTopRoot)
                else -> activityTopRoot
            }
            if (root == null) {
                return@withContext Result.failure(
                    Exception("No view hierarchy available (uiautomator dump and dumpsys activity top both failed)"),
                )
            }
            if (!options.includeInvisible) {
                root = root.filterInvisible() ?: root
            }

            val source = when {
                useUnmerged -> HierarchySource.Dumpsys
                uiautomatorRoot != null && activityTopRoot != null -> HierarchySource.Merged
                uiautomatorRoot != null -> HierarchySource.Uiautomator
                else -> HierarchySource.Dumpsys
            }

            val windowsOutput = devices.shell(serial, listOf("dumpsys", "window", "windows")).stdout
                .ifBlank { devices.shell(serial, listOf("dumpsys", "window")).stdout }
            val windows = runCatching { AndroidParsers.parseDumpsysWindow(windowsOutput) }.getOrDefault(emptyList())

            val sizeParts = AndroidParsers.parseWmSize(devices.shell(serial, listOf("wm", "size")).stdout)?.split("x")
            val displayWidth = sizeParts?.getOrNull(0)?.toIntOrNull() ?: 0
            val displayHeight = sizeParts?.getOrNull(1)?.toIntOrNull() ?: 0

            Result.success(
                HierarchySnapshot(
                    root = root,
                    capturedAtMillis = System.currentTimeMillis(),
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    source = source,
                    windows = windows,
                ),
            )
        }
}
