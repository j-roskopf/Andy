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

actual suspend fun pickFiles(initialDir: String?, allowMultiple: Boolean): List<String> = withContext(Dispatchers.IO) {
    val initial = initialDir?.takeIf { it.isNotBlank() }
    when (val result = nativeFilePicker(initial, allowMultiple)) {
        is MultiPickerResult.Selected -> result.paths
        MultiPickerResult.Cancelled -> emptyList()
        null -> swingFilePicker(initial, allowMultiple)
    }
}

actual fun downloadsDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val candidate = when {
        os.contains("win") -> {
            val userProfile = System.getenv("USERPROFILE")
            userProfile?.let { File(it, "Downloads") }?.takeIf { it.exists() || it.mkdirs() }
                ?: File(home, "Downloads")
        }
        else -> File(home, "Downloads")
    }
    if (!candidate.exists()) {
        candidate.mkdirs()
    }
    return candidate.absolutePath
}

actual fun uniqueLocalPath(directory: String, fileName: String): String {
    val dir = File(directory)
    dir.mkdirs()
    val base = File(fileName).name
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    val ext = if (dot > 0) base.substring(dot) else ""
    var candidate = File(dir, base)
    var index = 1
    while (candidate.exists()) {
        candidate = File(dir, "$stem ($index)$ext")
        index++
    }
    return candidate.absolutePath
}

private sealed class PickerResult {
    data class Selected(val path: String) : PickerResult()
    data object Cancelled : PickerResult()
}

private sealed class MultiPickerResult {
    data class Selected(val paths: List<String>) : MultiPickerResult()
    data object Cancelled : MultiPickerResult()
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

private fun nativeFilePicker(initialDir: String?, allowMultiple: Boolean): MultiPickerResult? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> macFilePicker(initialDir, allowMultiple)
        os.contains("win") -> windowsFilePicker(initialDir, allowMultiple)
        os.contains("linux") -> linuxFilePicker(initialDir, allowMultiple)
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

private fun macFilePicker(initialDir: String?, allowMultiple: Boolean): MultiPickerResult? {
    val defaultLocation = initialDir
        ?.takeIf { File(it).exists() }
        ?.let { """ default location POSIX file "${it.escapeAppleScript()}"""" }
        .orEmpty()
    val multiple = if (allowMultiple) " with multiple selections allowed" else ""
    val script = if (allowMultiple) {
        """
            set chosenFiles to choose file with prompt "Choose files"$defaultLocation$multiple
            set output to ""
            repeat with f in chosenFiles
                set output to output & POSIX path of f & linefeed
            end repeat
            return output
        """.trimIndent()
    } else {
        """
            set chosenFile to choose file with prompt "Choose file"$defaultLocation
            POSIX path of chosenFile
        """.trimIndent()
    }
    return runMultiPickerCommand(listOf("osascript", "-e", script))
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

private fun windowsFilePicker(initialDir: String?, allowMultiple: Boolean): MultiPickerResult? {
    val initial = initialDir
        ?.takeIf { File(it).exists() }
        ?.let { "\$dialog.InitialDirectory = '${it.escapePowerShellSingleQuoted()}'" }
        .orEmpty()
    val multi = if (allowMultiple) "\$dialog.Multiselect = \$true;" else ""
    val command = """
        Add-Type -AssemblyName System.Windows.Forms;
        ${'$'}dialog = New-Object System.Windows.Forms.OpenFileDialog;
        ${'$'}dialog.Title = 'Choose files';
        ${'$'}dialog.CheckFileExists = ${'$'}true;
        $multi
        $initial
        if (${'$'}dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
          foreach (${'$'}f in ${'$'}dialog.FileNames) { [Console]::Out.WriteLine(${'$'}f) }
        }
    """.trimIndent()
    val powershell = runMultiPickerCommand(listOf("powershell", "-NoProfile", "-STA", "-Command", command))
    if (powershell != null) return powershell
    return runMultiPickerCommand(listOf("pwsh", "-NoProfile", "-Command", command))
}

private fun linuxDirectoryPicker(initialDir: String?): PickerResult? {
    val zenity = mutableListOf("zenity", "--file-selection", "--directory", "--title=Choose folder")
    initialDir?.takeIf { it.isNotBlank() }?.let { zenity += "--filename=$it" }
    return runPickerCommand(zenity)
        ?: runPickerCommand(listOf("kdialog", "--getexistingdirectory", initialDir.orEmpty()))
}

private fun linuxFilePicker(initialDir: String?, allowMultiple: Boolean): MultiPickerResult? {
    val zenity = mutableListOf("zenity", "--file-selection", "--title=Choose files")
    if (allowMultiple) zenity += "--multiple"
    initialDir?.takeIf { it.isNotBlank() }?.let { zenity += "--filename=$it" }
    val zenityResult = runMultiPickerCommand(zenity, splitOn = "|")
    if (zenityResult != null) return zenityResult
    val kdialog = mutableListOf("kdialog", "--getopenfilename", initialDir.orEmpty().ifBlank { "." })
    if (allowMultiple) kdialog += "--multiple"
    return runMultiPickerCommand(kdialog, splitOn = " ")
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

private fun runMultiPickerCommand(command: List<String>, splitOn: String? = null): MultiPickerResult? {
    val process = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }.getOrNull() ?: return null

    if (!process.waitFor(24, TimeUnit.HOURS)) {
        process.destroyForcibly()
        return MultiPickerResult.Cancelled
    }
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.exitValue() != 0 || output.isBlank()) return MultiPickerResult.Cancelled
    val paths = if (splitOn != null && !output.contains('\n')) {
        output.split(splitOn).map { it.trim() }.filter { it.isNotBlank() }
    } else {
        output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }
    return if (paths.isEmpty()) MultiPickerResult.Cancelled else MultiPickerResult.Selected(paths)
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

private fun swingFilePicker(initialDir: String?, allowMultiple: Boolean): List<String> {
    var selected: List<String> = emptyList()
    val task = Runnable {
        val chooser = JFileChooser(initialDir?.let(::File)).apply {
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = allowMultiple
            dialogTitle = if (allowMultiple) "Choose files" else "Choose file"
            approveButtonText = "Choose"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selected = if (allowMultiple) {
                chooser.selectedFiles.map { it.absolutePath }
            } else {
                listOfNotNull(chooser.selectedFile?.absolutePath)
            }
        }
    }
    if (SwingUtilities.isEventDispatchThread()) {
        task.run()
    } else {
        SwingUtilities.invokeAndWait(task)
    }
    return selected
}

private fun String.escapeAppleScript(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.escapePowerShellSingleQuoted(): String = replace("'", "''")
