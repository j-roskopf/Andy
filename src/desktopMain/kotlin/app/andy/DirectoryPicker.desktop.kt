package app.andy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual suspend fun pickDirectory(initialDir: String?): String? = withContext(Dispatchers.IO) {
    val initial = initialDir?.takeIf { it.isNotBlank() }
    // nativeDirectoryPicker returns:
    //   PickerResult.Selected(path) – user picked something
    //   PickerResult.Cancelled       – native picker ran but user cancelled (do NOT fall back to Swing)
    //   null                         – no native picker available (fall back to Swing)
    when (val result = nativeDirectoryPicker(initial)) {
        is PickerResult.Selected -> result.path
        PickerResult.Cancelled -> null
        null -> swingDirectoryPicker(initial)
    }
}

private sealed class PickerResult {
    data class Selected(val path: String) : PickerResult()
    data object Cancelled : PickerResult()
}

private fun nativeDirectoryPicker(initialDir: String?): PickerResult? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> macDirectoryPicker(initialDir)
        os.contains("win") -> windowsDirectoryPicker(initialDir)
        os.contains("linux") -> linuxDirectoryPicker(initialDir)
        else -> null
    }
}

private fun macDirectoryPicker(initialDir: String?): PickerResult? {
    val defaultLocation = initialDir
        ?.takeIf { File(it).exists() }
        ?.let { """ default location POSIX file "${it.escapeAppleScript()}"""" }
        .orEmpty()
    val script = """
        set chosenFolder to choose folder with prompt "Choose folder"$defaultLocation
        POSIX path of chosenFolder
    """.trimIndent()
    return runPickerCommand(listOf("osascript", "-e", script))
}

private fun windowsDirectoryPicker(initialDir: String?): PickerResult? {
    val selectedPath = initialDir
        ?.takeIf { File(it).exists() }
        ?.let { "\$dialog.SelectedPath = '${it.escapePowerShellSingleQuoted()}'" }
        .orEmpty()
    val command = """
        Add-Type -AssemblyName System.Windows.Forms;
        ${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog;
        ${'$'}dialog.Description = 'Choose folder';
        $selectedPath
        if (${'$'}dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.WriteLine(${'$'}dialog.SelectedPath) }
    """.trimIndent()
    // Try powershell then pwsh; if both return null the tool isn't available
    val powershell = runPickerCommand(listOf("powershell", "-NoProfile", "-STA", "-Command", command))
    if (powershell != null) return powershell
    return runPickerCommand(listOf("pwsh", "-NoProfile", "-Command", command))
}

private fun linuxDirectoryPicker(initialDir: String?): PickerResult? {
    val zenity = mutableListOf("zenity", "--file-selection", "--directory", "--title=Choose folder")
    initialDir?.takeIf { it.isNotBlank() }?.let { zenity += "--filename=$it" }
    return runPickerCommand(zenity)
        ?: runPickerCommand(listOf("kdialog", "--getexistingdirectory", initialDir.orEmpty()))
}

/**
 * Runs an external picker command.
 * Returns:
 *   PickerResult.Selected  – process exited 0 and printed a non-blank path
 *   PickerResult.Cancelled – process launched and exited non-zero (user cancelled)
 *   null                   – process could not be launched (tool not installed)
 */
private fun runPickerCommand(command: List<String>): PickerResult? {
    val process = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }.getOrNull() ?: return null  // command not found / couldn't launch → tool unavailable

    if (!process.waitFor(24, TimeUnit.HOURS)) {
        process.destroyForcibly()
        return PickerResult.Cancelled
    }
    val output = process.inputStream.bufferedReader().readText().trim()
    return if (process.exitValue() == 0 && output.isNotBlank()) {
        PickerResult.Selected(output)
    } else {
        // Non-zero exit = user pressed Cancel in the native dialog
        PickerResult.Cancelled
    }
}

private fun swingDirectoryPicker(initialDir: String?): String? {
    var selected: File? = null
    val task = Runnable {
        val chooser = JFileChooser(initialDir?.let(::File)).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose folder"
            approveButtonText = "Choose"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.selectedFile
        }
    }
    if (SwingUtilities.isEventDispatchThread()) {
        task.run()
    } else {
        SwingUtilities.invokeAndWait(task)
    }
    return selected?.absolutePath
}

private fun String.escapeAppleScript(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.escapePowerShellSingleQuoted(): String = replace("'", "''")
