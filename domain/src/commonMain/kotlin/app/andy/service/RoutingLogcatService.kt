package app.andy.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Routes [LogcatService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget]. iOS uses `simctl spawn log stream` (unified log) instead of
 * `adb logcat`.
 */
class RoutingLogcatService(
    android: LogcatService,
    private val ios: LogcatService,
) : LogcatService {
    private val androidRef = MutableStateFlow(android)

    fun replaceAndroid(next: LogcatService) {
        androidRef.value = next
    }

    private fun android(): LogcatService = androidRef.value

    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android()

    override fun stream(serial: String, filter: LogcatFilter) = of(serial).stream(serial, filter)
    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int) =
        of(serial).snapshot(serial, filter, limit)
    override suspend fun clear(serial: String) = of(serial).clear(serial)
}
