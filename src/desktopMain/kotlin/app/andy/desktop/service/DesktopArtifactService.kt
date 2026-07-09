package app.andy.desktop.service

import app.andy.service.ArtifactService
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

class DesktopArtifactService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val mirror: MirrorEngine,
) : ArtifactService {
    override suspend fun saveScreenshot(serial: String, suggestedName: String): CommandResult = withContext(Dispatchers.IO) {
        val target = chooseSaveFile(suggestedName) ?: return@withContext CommandResult.failure("Screenshot save canceled")
        val bytes = mirror.screenshot(serial) ?: return@withContext CommandResult.failure("Screenshot failed")
        runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            CommandResult.success("Saved screenshot to ${target.absolutePath}")
        }.getOrElse { CommandResult.failure(it.message ?: "Screenshot save failed") }
    }

    override suspend fun saveBugReport(serial: String, suggestedName: String): CommandResult = withContext(Dispatchers.IO) {
        val target = chooseSaveFile(suggestedName) ?: return@withContext CommandResult.failure("Bug report save canceled")
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        target.parentFile?.mkdirs()
        runner.run(listOf(adb, "-s", serial, "bugreport", target.absolutePath), 180)
    }

    private fun chooseSaveFile(suggestedName: String): File? {
        var selected: File? = null
        val task = Runnable {
            val chooser = JFileChooser().apply {
                selectedFile = File(suggestedName)
                dialogTitle = "Save ${suggestedName.substringBeforeLast('.', suggestedName)}"
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            SwingUtilities.invokeAndWait(task)
        }
        return selected
    }
}
