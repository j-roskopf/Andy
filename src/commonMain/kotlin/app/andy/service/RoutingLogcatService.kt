package app.andy.service

/**
 * Routes [LogcatService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget]. iOS uses `simctl spawn log stream` (unified log) instead of
 * `adb logcat`.
 */
class RoutingLogcatService(
    private val android: LogcatService,
    private val ios: LogcatService,
) : LogcatService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override fun stream(serial: String, filter: LogcatFilter) = of(serial).stream(serial, filter)
    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int) =
        of(serial).snapshot(serial, filter, limit)
    override suspend fun clear(serial: String) = of(serial).clear(serial)
}
