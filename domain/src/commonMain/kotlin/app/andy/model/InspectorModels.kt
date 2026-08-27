package app.andy.model

import kotlinx.serialization.Serializable

enum class PerformanceTab {
    Metrics,
    Tracing,
    Memory,
}

enum class FilesTab {
    Files,
    SharedPreferences,
    Database,
}

enum class LogcatTab {
    Stream,
    Crashes,
}

enum class CrashKind {
    JavaCrash,
    NativeCrash,
    Anr,
    SystemAppCrash,
    Watchdog,
}

data class CrashRecord(
    val id: String,
    val kind: CrashKind,
    val packageName: String?,
    val timestampMillis: Long,
    val summary: String,
)

/** Local capture managed like [TraceRecording] — captured, listed, revealed, deleted. */
@Serializable
data class HeapDumpInfo(
    val id: String,
    val packageName: String,
    val serial: String,
    val deviceLabel: String? = null,
    val capturedAtMillis: Long,
    val sizeBytes: Long,
    val localPath: String,
)

data class MeminfoBreakdown(
    val packageName: String,
    val javaHeapMb: Float? = null,
    val nativeHeapMb: Float? = null,
    val codeMb: Float? = null,
    val stackMb: Float? = null,
    val graphicsMb: Float? = null,
    val privateOtherMb: Float? = null,
    val systemMb: Float? = null,
    val totalPssMb: Float? = null,
)

data class BatteryStatsWakelock(val name: String, val packageName: String?, val heldMillis: Long, val count: Int)
data class BatteryStatsAlarm(val name: String, val packageName: String?, val count: Int)
data class BatteryStatsJob(val name: String, val packageName: String?, val durationMillis: Long, val count: Int)
data class BatteryStatsDrain(val packageName: String, val percent: Float)

data class BatteryStatsSummary(
    val wakelocks: List<BatteryStatsWakelock> = emptyList(),
    val alarms: List<BatteryStatsAlarm> = emptyList(),
    val jobs: List<BatteryStatsJob> = emptyList(),
    val drain: List<BatteryStatsDrain> = emptyList(),
    val raw: String = "",
)

enum class PrefType {
    String,
    Int,
    Long,
    Float,
    Boolean,
    StringSet,
}

data class PrefEntry(
    val key: String,
    val type: PrefType,
    val value: String,
)

data class AppDatabaseInfo(
    val name: String,
    val path: String,
    val hasWal: Boolean = false,
    val hasShm: Boolean = false,
)

data class DbColumnInfo(
    val name: String,
    val type: String,
    val primaryKey: Boolean,
)

data class DbTableInfo(
    val name: String,
    val columns: List<DbColumnInfo>,
    val hasRowId: Boolean,
)

data class DbQueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val rowsAffected: Int? = null,
    val message: String? = null,
)

@Serializable
data class SavedSqlQuery(
    val id: String,
    val name: String,
    val sql: String,
    val packageName: String,
    val updatedAtMillis: Long,
)
