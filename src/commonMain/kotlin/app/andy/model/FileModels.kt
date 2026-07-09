package app.andy.model

data class DeviceFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val permissions: String?,
    val modified: String?,
)

data class HostFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val extension: String = "",
    val languageHint: String = "",
)

data class HostFileDocument(
    val path: String,
    val content: String,
    val modifiedMillis: Long,
    val sizeBytes: Long,
    val charset: String = "UTF-8",
    val languageHint: String = "",
)

sealed interface HostFileSaveResult {
    data class Saved(val modifiedMillis: Long) : HostFileSaveResult
    data class Conflict(val currentModifiedMillis: Long) : HostFileSaveResult
    data class Failed(val message: String) : HostFileSaveResult
}

enum class HostSearchMode { FileName, Content, Combined }

enum class HostSearchMatchKind { FileName, Content }

data class HostSearchResult(
    val path: String,
    val root: String,
    val kind: HostSearchMatchKind,
    val lineNumber: Int? = null,
    val column: Int? = null,
    val preview: String = "",
)

data class HostIndexStatus(
    val root: String,
    val indexedFiles: Int,
    val indexedBytes: Long,
    val indexing: Boolean,
    val message: String,
    val updatedAtMillis: Long,
)
