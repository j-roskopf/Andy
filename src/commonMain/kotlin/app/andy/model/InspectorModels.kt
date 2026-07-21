package app.andy.model

import kotlinx.serialization.Serializable

enum class PerformanceTab {
    Metrics,
    Tracing,
}

enum class FilesTab {
    Files,
    SharedPreferences,
    Database,
}

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
