package app.andy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual suspend fun pickDirectory(initialDir: String?): String? = withContext(Dispatchers.IO) {
    val initial = initialDir?.takeIf { it.isNotBlank() }
    nativeDirectoryPicker(initial) ?: swingDirectoryPicker(initial)
}

private fun nativeDirectoryPicker(initialDir: String?): String? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> macDirectoryPicker(initialDir)
        os.contains("win") -> windowsDirectoryPicker(initialDir)
        os.contains("linux") -> linuxDirectoryPicker(initialDir)
        else -> null
    }
}

private fun macDirectoryPicker(initialDir: String?): String? {
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

private fun windowsDirectoryPicker(initialDir: String?): String? {
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
    return runPickerCommand(listOf("powershell", "-NoProfile", "-STA", "-Command", command))
        ?: runPickerCommand(listOf("pwsh", "-NoProfile", "-Command", command))
}

private fun linuxDirectoryPicker(initialDir: String?): String? {
    val zenity = mutableListOf("zenity", "--file-selection", "--directory", "--title=Choose folder")
    initialDir?.takeIf { it.isNotBlank() }?.let { zenity += "--filename=$it" }
    return runPickerCommand(zenity)
        ?: runPickerCommand(listOf("kdialog", "--getexistingdirectory", initialDir.orEmpty()))
}

private fun runPickerCommand(command: List<String>): String? = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(24, TimeUnit.HOURS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotBlank() }
}.getOrNull()

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
