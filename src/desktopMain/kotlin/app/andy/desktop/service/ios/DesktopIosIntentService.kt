package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.IntentDraft
import app.andy.model.IntentMode
import app.andy.service.CommandResult
import app.andy.service.IntentService

/**
 * iOS URL-scheme opener via `simctl openurl`. Activity/service/broadcast modes are Android-only.
 */
class DesktopIosIntentService(
    private val runner: CommandRunner,
) : IntentService {
    override fun buildCommand(draft: IntentDraft): List<String> {
        val url = draft.dataUri.ifBlank { draft.action }
        return listOf("xcrun", "simctl", "openurl", "<udid>", url)
    }

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult {
        if (draft.mode != IntentMode.DeepLink && draft.mode != IntentMode.Activity) {
            return CommandResult.failure("iOS simulators only support URL schemes (Deep Link mode)")
        }
        val url = draft.dataUri.ifBlank { draft.action }
        if (url.isBlank()) return CommandResult.failure("URL is required")
        return runner.run(listOf("xcrun", "simctl", "openurl", serial, url))
    }
}
