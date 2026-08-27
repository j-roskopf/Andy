package app.andy.service

import app.andy.model.CrashRecord

/**
 * Routes [CrashInspectorService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget]. iOS reads `.ips` reports from the host
 * `~/Library/Logs/DiagnosticReports` instead of `dumpsys dropbox` (§Phase 3.2).
 */
class RoutingCrashInspectorService(
    private val android: CrashInspectorService,
    private val ios: CrashInspectorService,
) : CrashInspectorService {
    private fun of(serial: String) = if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override suspend fun listCrashes(serial: String): List<CrashRecord> = of(serial).listCrashes(serial)
    override suspend fun loadCrash(serial: String, id: String): String = of(serial).loadCrash(serial, id)
    override suspend fun exportCrash(serial: String, id: String, localPath: String): CommandResult =
        of(serial).exportCrash(serial, id, localPath)
}
