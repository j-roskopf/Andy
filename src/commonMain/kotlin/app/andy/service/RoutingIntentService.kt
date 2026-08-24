package app.andy.service

import app.andy.model.IntentDraft
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Routes [IntentService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget].
 */
class RoutingIntentService(
    android: IntentService,
    private val ios: IntentService,
) : IntentService {
    private val androidRef = MutableStateFlow(android)

    fun replaceAndroid(next: IntentService) {
        androidRef.value = next
    }

    private fun android(): IntentService = androidRef.value

    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android()

    override fun buildCommand(draft: IntentDraft): List<String> = android().buildCommand(draft)

    /** Prefer the iOS command shape when [serial] is an iOS target. */
    fun buildCommand(serial: String?, draft: IntentDraft): List<String> =
        if (serial != null && IosTargetRegistry.isIosTarget(serial)) {
            ios.buildCommand(draft)
        } else {
            android().buildCommand(draft)
        }

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult =
        of(serial).send(serial, draft)
}
