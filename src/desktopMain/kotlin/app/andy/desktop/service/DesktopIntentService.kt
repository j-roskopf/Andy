package app.andy.desktop.service

import app.andy.model.ExtraType
import app.andy.model.IntentDraft
import app.andy.model.IntentMode
import app.andy.service.CommandResult
import app.andy.service.IntentService

class DesktopIntentService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : IntentService {
    override fun buildCommand(draft: IntentDraft): List<String> {
        val verb = when (draft.mode) {
            IntentMode.Activity, IntentMode.DeepLink -> "start"
            IntentMode.Service -> "startservice"
            IntentMode.Broadcast -> "broadcast"
        }
        return buildList {
            add("am")
            add(verb)
            if (draft.action.isNotBlank()) addAll(listOf("-a", draft.action))
            if (draft.component.isNotBlank()) addAll(listOf("-n", draft.component))
            draft.categories.filter { it.isNotBlank() }.forEach { addAll(listOf("-c", it)) }
            draft.flags.filter { it.isNotBlank() }.forEach { addAll(listOf("-f", it)) }
            draft.extras.forEach { extra ->
                val flag = when (extra.type) {
                    ExtraType.StringValue -> "--es"
                    ExtraType.BooleanValue -> "--ez"
                    ExtraType.IntValue -> "--ei"
                    ExtraType.LongValue -> "--el"
                    ExtraType.FloatValue -> "--ef"
                }
                addAll(listOf(flag, extra.key, extra.value))
            }
            if (draft.dataUri.isNotBlank()) addAll(listOf("-d", draft.dataUri))
        }
    }

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell") + buildCommand(draft))
    }
}
