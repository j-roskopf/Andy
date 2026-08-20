package app.andy.service

import app.andy.model.PrefEntry

/**
 * Routes [SharedPrefsService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget]. On iOS this reads/writes `Library/Preferences/<bundleid>.plist`
 * ("User Defaults") through the same shape as Android shared_prefs.
 */
class RoutingSharedPrefsService(
    private val android: SharedPrefsService,
    private val ios: SharedPrefsService,
) : SharedPrefsService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override suspend fun listFiles(serial: String, packageName: String) =
        of(serial).listFiles(serial, packageName)
    override suspend fun read(serial: String, packageName: String, fileName: String) =
        of(serial).read(serial, packageName, fileName)
    override suspend fun upsert(serial: String, packageName: String, fileName: String, entry: PrefEntry) =
        of(serial).upsert(serial, packageName, fileName, entry)
    override suspend fun delete(serial: String, packageName: String, fileName: String, key: String) =
        of(serial).delete(serial, packageName, fileName, key)
}
