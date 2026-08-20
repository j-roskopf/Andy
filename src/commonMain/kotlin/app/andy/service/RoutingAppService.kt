package app.andy.service

/**
 * Routes [AppService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget].
 */
class RoutingAppService(
    private val android: AppService,
    private val ios: AppService,
) : AppService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override suspend fun listApps(serial: String) = of(serial).listApps(serial)
    override suspend fun focusedPackage(serial: String) = of(serial).focusedPackage(serial)
    override suspend fun getAppDetails(serial: String, packageName: String) =
        of(serial).getAppDetails(serial, packageName)
    override suspend fun launch(serial: String, packageName: String) =
        of(serial).launch(serial, packageName)
    override suspend fun launchActivity(serial: String, packageName: String, activityName: String) =
        of(serial).launchActivity(serial, packageName, activityName)
    override suspend fun stop(serial: String, packageName: String) =
        of(serial).stop(serial, packageName)
    override suspend fun clearData(serial: String, packageName: String) =
        of(serial).clearData(serial, packageName)
    override suspend fun resetPermissions(serial: String, packageName: String) =
        of(serial).resetPermissions(serial, packageName)
    override suspend fun uninstall(serial: String, packageName: String) =
        of(serial).uninstall(serial, packageName)
    override suspend fun install(serial: String, apkPath: String, replace: Boolean) =
        of(serial).install(serial, apkPath, replace)
    override suspend fun listPermissions(serial: String, packageName: String) =
        of(serial).listPermissions(serial, packageName)
    override suspend fun listActivities(serial: String, packageName: String) =
        of(serial).listActivities(serial, packageName)
    override suspend fun getIcon(serial: String, packageName: String) =
        of(serial).getIcon(serial, packageName)
}
