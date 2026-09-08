package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import java.io.File

/** A `devicectl` invocation's exit status paired with the JSON it wrote to `--json-output`. */
internal data class DevicectlResponse(
    val result: CommandResult,
    val output: String,
) {
    /**
     * Collapses the pair into a [CommandResult]. On failure the `--json-output` payload is
     * preferred over stderr: Developer Mode / DDI problems only describe themselves in the JSON
     * `NSLocalizedDescription`, while stderr is usually a bare `CoreDeviceError` code.
     */
    fun toCommandResult(failureMessage: String, successMessage: String): CommandResult {
        if (result.isSuccess) return CommandResult.success(successMessage)
        val detail = IosParsers.parseDevicectlErrorMessage(output)
            ?: result.stderr.trim().ifBlank { result.stdout.trim() }.ifBlank { null }
        return CommandResult.failure(listOfNotNull(failureMessage.ifBlank { null }, detail).joinToString(" — "))
    }
}

/**
 * Runs `xcrun devicectl <args> --json-output <temp>`. devicectl writes structured output to a
 * file rather than stdout, and it writes the *error* payload there too, so the temp file has to
 * be read on both paths.
 */
internal suspend fun CommandRunner.runDevicectlJson(
    args: List<String>,
    timeoutSeconds: Long = 60,
): DevicectlResponse {
    val temp = File.createTempFile("andy-devicectl", ".json")
    return try {
        val result = run(
            listOf("xcrun", "devicectl") + args + listOf("--json-output", temp.absolutePath),
            timeoutSeconds = timeoutSeconds,
        )
        DevicectlResponse(result, runCatching { if (temp.isFile) temp.readText() else "" }.getOrDefault(""))
    } finally {
        temp.delete()
    }
}
