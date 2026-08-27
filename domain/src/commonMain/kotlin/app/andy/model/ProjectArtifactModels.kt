package app.andy.model

import kotlinx.serialization.Serializable

/** Media vs Documents sub-tab classification (by file type). */
enum class ProjectCatalogTab {
    Media,
    Documents,
}

/** How an entry entered the hybrid catalog. */
enum class ProjectCatalogSourceKind {
    ChatAttachment,
    EvidenceFile,
    WorkflowArtifact,
    Recording,
    DirectUpload,
    PinnedCopy,
}

/**
 * One row in the project Artifacts + Media catalog (or the Agents Unscoped inbox when
 * [projectId] is null).
 */
@Serializable
data class ProjectCatalogEntry(
    val id: String,
    val projectId: String?,
    val tab: ProjectCatalogTab,
    val title: String,
    val createdAtMillis: Long,
    val sourceKind: ProjectCatalogSourceKind,
    /** Absolute path used for preview / reveal when the file still exists. */
    val absolutePath: String? = null,
    val pinned: Boolean = false,
    val taskId: String? = null,
    val recordingId: String? = null,
    val bundleId: String? = null,
    /** Relative path under the durable artifacts dir when pinned or uploaded. */
    val durableRelativePath: String? = null,
    val mimeHint: String? = null,
)

@Serializable
data class CatalogPinRecord(
    val sourceId: String,
    val durableRelativePath: String,
    val pinnedAtMillis: Long,
    val title: String,
    val tab: ProjectCatalogTab,
    val originalSourceKind: ProjectCatalogSourceKind,
    val taskId: String? = null,
    val recordingId: String? = null,
    val bundleId: String? = null,
)

@Serializable
data class CatalogUploadRecord(
    val id: String,
    val projectId: String?,
    val durableRelativePath: String,
    val title: String,
    val tab: ProjectCatalogTab,
    val createdAtMillis: Long,
)

/**
 * Persisted overrides for one catalog root (`~/.andy/projects/<id>/` or unscoped).
 * Live sources are merged on read; this file only stores unlinks, pins, uploads, and assignments.
 */
@Serializable
data class ProjectCatalogStore(
    val unlinkedIds: Set<String> = emptySet(),
    val pins: List<CatalogPinRecord> = emptyList(),
    val uploads: List<CatalogUploadRecord> = emptyList(),
    /** sourceId → projectId for items assigned out of Unscoped (or reassigned). */
    val assignments: Map<String, String> = emptyMap(),
)
