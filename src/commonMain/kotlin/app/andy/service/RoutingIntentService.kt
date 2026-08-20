package app.andy.service

import app.andy.model.IntentDraft

/**
 * Routes [IntentService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget].
 */
class RoutingIntentService(
    private val android: IntentService,
    private val ios: IntentService,
) : IntentService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override fun buildCommand(draft: IntentDraft): List<String> = android.buildCommand(draft)

    /** Prefer the iOS command shape when [serial] is an iOS target. */
    fun buildCommand(serial: String?, draft: IntentDraft): List<String> =
        if (serial != null && IosTargetRegistry.isIosTarget(serial)) {
            ios.buildCommand(draft)
        } else {
            android.buildCommand(draft)
        }

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult =
        of(serial).send(serial, draft)
}
