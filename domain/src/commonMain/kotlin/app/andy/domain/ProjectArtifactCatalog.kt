package app.andy.domain

import app.andy.model.CatalogPinRecord
import app.andy.model.CatalogUploadRecord
import app.andy.model.ProjectCatalogEntry
import app.andy.model.ProjectCatalogSourceKind
import app.andy.model.ProjectCatalogStore
import app.andy.model.ProjectCatalogTab

private val MediaExtensions = setOf(
    "png", "jpg", "jpeg", "webp", "gif", "mp4", "mov", "webm", "m4v", "mkv",
)

/** File-type classification for Media | Documents tabs. */
fun projectCatalogTabForFileName(fileName: String): ProjectCatalogTab {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (ext in MediaExtensions) ProjectCatalogTab.Media else ProjectCatalogTab.Documents
}

fun isProjectCatalogMediaFileName(fileName: String): Boolean =
    projectCatalogTabForFileName(fileName) == ProjectCatalogTab.Media

/** Andy-internal workflow files that should not appear in the project catalog. */
private val ExcludedWorkflowArtifactNames = setOf(
    "status.json",
)

fun isCatalogExcludedWorkflowArtifact(fileName: String): Boolean =
    fileName in ExcludedWorkflowArtifactNames

/** A discovered live source before catalog.json overrides are applied. */
data class ProjectCatalogSourceHit(
    val id: String,
    val projectId: String?,
    val title: String,
    val createdAtMillis: Long,
    val sourceKind: ProjectCatalogSourceKind,
    val absolutePath: String,
    val taskId: String? = null,
    val recordingId: String? = null,
    val bundleId: String? = null,
)

fun chatAttachmentCatalogId(taskId: String, absolutePath: String): String =
    "chat:$taskId:image:${pathFingerprint(absolutePath)}"

fun evidenceCatalogId(taskId: String, bundleId: String, absolutePath: String): String =
    "evidence:$taskId:$bundleId:${pathFingerprint(absolutePath)}"

fun workflowCatalogId(taskId: String, fileName: String): String =
    "workflow:$taskId:$fileName"

fun recordingCatalogId(recordingId: String, artifactName: String): String =
    "recording:$recordingId:$artifactName"

fun uploadCatalogId(uploadId: String): String = "upload:$uploadId"

/**
 * Merges live hits with one catalog store for a single scope.
 *
 * @param projectFilter null = Unscoped inbox; otherwise that project id
 * @param store catalog.json for that scope (project or unscoped root)
 * @param assignmentLookup sourceId → projectId from the unscoped store (assignments always live there)
 */
fun mergeProjectCatalog(
    hits: List<ProjectCatalogSourceHit>,
    store: ProjectCatalogStore,
    projectFilter: String?,
    assignmentLookup: Map<String, String> = emptyMap(),
    durableAbsolutePath: (relativePath: String) -> String?,
): List<ProjectCatalogEntry> {
    val unlinked = store.unlinkedIds
    val pinBySource = store.pins.associateBy { it.sourceId }
    val uploads = store.uploads.filter { upload ->
        if (projectFilter == null) upload.projectId == null
        else upload.projectId == projectFilter
    }

    val fromHits = hits.mapNotNull { hit ->
        if (hit.id in unlinked) return@mapNotNull null
        val effectiveProject = assignmentLookup[hit.id] ?: hit.projectId
        if (!matchesFilter(effectiveProject, projectFilter)) return@mapNotNull null
        val pin = pinBySource[hit.id]
        if (pin != null) {
            entryFromPin(
                pin = pin,
                projectId = effectiveProject,
                fallbackPath = hit.absolutePath,
                durableAbsolutePath = durableAbsolutePath,
                taskId = pin.taskId ?: hit.taskId,
                recordingId = pin.recordingId ?: hit.recordingId,
                bundleId = pin.bundleId ?: hit.bundleId,
            )
        } else {
            ProjectCatalogEntry(
                id = hit.id,
                projectId = effectiveProject,
                tab = projectCatalogTabForFileName(hit.title),
                title = hit.title,
                createdAtMillis = hit.createdAtMillis,
                sourceKind = hit.sourceKind,
                absolutePath = hit.absolutePath,
                pinned = false,
                taskId = hit.taskId,
                recordingId = hit.recordingId,
                bundleId = hit.bundleId,
                mimeHint = fileExtension(hit.title),
            )
        }
    }

    val fromUploads = uploads.mapNotNull { upload ->
        val id = uploadCatalogId(upload.id)
        if (id in unlinked || upload.id in unlinked) return@mapNotNull null
        val path = durableAbsolutePath(upload.durableRelativePath) ?: return@mapNotNull null
        ProjectCatalogEntry(
            id = id,
            projectId = upload.projectId,
            tab = upload.tab,
            title = upload.title,
            createdAtMillis = upload.createdAtMillis,
            sourceKind = ProjectCatalogSourceKind.DirectUpload,
            absolutePath = path,
            pinned = true,
            durableRelativePath = upload.durableRelativePath,
            mimeHint = fileExtension(upload.title),
        )
    }

    val hitIds = hits.map { it.id }.toSet()
    val orphanPins = store.pins.mapNotNull { pin ->
        if (pin.sourceId in hitIds || pin.sourceId in unlinked) return@mapNotNull null
        val effectiveProject = assignmentLookup[pin.sourceId] ?: projectFilter.takeIf { it != null }
        // Unscoped orphan pins: only when still unassigned
        if (projectFilter == null) {
            if (assignmentLookup[pin.sourceId] != null) return@mapNotNull null
            entryFromPin(pin, null, null, durableAbsolutePath, pin.taskId, pin.recordingId, pin.bundleId)
        } else {
            val resolved = assignmentLookup[pin.sourceId] ?: projectFilter
            if (resolved != projectFilter) return@mapNotNull null
            entryFromPin(pin, resolved, null, durableAbsolutePath, pin.taskId, pin.recordingId, pin.bundleId)
        }
    }

    return (fromHits + fromUploads + orphanPins)
        .distinctBy { it.id }
        .sortedByDescending { it.createdAtMillis }
}

/**
 * Builds the project view by merging all hits with the project store, then adding
 * unscoped hits that were assigned to this project (using unscoped pins/uploads as needed).
 */
fun mergeProjectCatalogForProject(
    hits: List<ProjectCatalogSourceHit>,
    projectId: String,
    projectStore: ProjectCatalogStore,
    unscopedStore: ProjectCatalogStore,
    projectDurablePath: (relativePath: String) -> String?,
    unscopedDurablePath: (relativePath: String) -> String?,
): List<ProjectCatalogEntry> {
    val assignments = unscopedStore.assignments
    val primary = mergeProjectCatalog(
        hits = hits,
        store = projectStore,
        projectFilter = projectId,
        assignmentLookup = assignments,
        durableAbsolutePath = projectDurablePath,
    )
    // Hits that live as unscoped sources but were assigned here need unscoped pin bytes.
    val assignedHits = hits.filter { (assignments[it.id] ?: it.projectId) == projectId && it.projectId != projectId }
    val fromAssigned = mergeProjectCatalog(
        hits = assignedHits,
        store = unscopedStore.copy(
            // Don't re-include unscoped-only uploads here
            uploads = emptyList(),
        ),
        projectFilter = projectId,
        assignmentLookup = assignments,
        durableAbsolutePath = { rel -> unscopedDurablePath(rel) ?: projectDurablePath(rel) },
    )
    val assignedUploads = mergeProjectCatalog(
        hits = emptyList(),
        store = unscopedStore.copy(pins = emptyList(), unlinkedIds = unscopedStore.unlinkedIds),
        projectFilter = projectId,
        assignmentLookup = assignments,
        durableAbsolutePath = unscopedDurablePath,
    )
    return (primary + fromAssigned + assignedUploads)
        .distinctBy { it.id }
        .sortedByDescending { it.createdAtMillis }
}

fun mergeProjectCatalogForUnscoped(
    hits: List<ProjectCatalogSourceHit>,
    unscopedStore: ProjectCatalogStore,
    durableAbsolutePath: (relativePath: String) -> String?,
): List<ProjectCatalogEntry> = mergeProjectCatalog(
    hits = hits,
    store = unscopedStore,
    projectFilter = null,
    assignmentLookup = unscopedStore.assignments,
    durableAbsolutePath = durableAbsolutePath,
)

fun ProjectCatalogStore.withUnlinked(id: String): ProjectCatalogStore =
    copy(unlinkedIds = unlinkedIds + id)

fun ProjectCatalogStore.withoutUnlinked(id: String): ProjectCatalogStore =
    copy(unlinkedIds = unlinkedIds - id)

fun ProjectCatalogStore.withPin(pin: CatalogPinRecord): ProjectCatalogStore =
    copy(pins = pins.filterNot { it.sourceId == pin.sourceId } + pin)

fun ProjectCatalogStore.withoutPin(sourceId: String): ProjectCatalogStore =
    copy(pins = pins.filterNot { it.sourceId == sourceId })

fun ProjectCatalogStore.withUpload(upload: CatalogUploadRecord): ProjectCatalogStore =
    copy(uploads = uploads.filterNot { it.id == upload.id } + upload)

fun ProjectCatalogStore.withoutUpload(uploadId: String): ProjectCatalogStore =
    copy(uploads = uploads.filterNot { it.id == uploadId })

fun ProjectCatalogStore.withAssignment(sourceId: String, projectId: String): ProjectCatalogStore =
    copy(assignments = assignments + (sourceId to projectId))

fun ProjectCatalogStore.withoutAssignment(sourceId: String): ProjectCatalogStore =
    copy(assignments = assignments - sourceId)

private fun matchesFilter(effectiveProject: String?, projectFilter: String?): Boolean =
    if (projectFilter == null) effectiveProject == null else effectiveProject == projectFilter

private fun entryFromPin(
    pin: CatalogPinRecord,
    projectId: String?,
    fallbackPath: String?,
    durableAbsolutePath: (relativePath: String) -> String?,
    taskId: String?,
    recordingId: String?,
    bundleId: String?,
): ProjectCatalogEntry? {
    val path = durableAbsolutePath(pin.durableRelativePath) ?: fallbackPath ?: return null
    return ProjectCatalogEntry(
        id = pin.sourceId,
        projectId = projectId,
        tab = pin.tab,
        title = pin.title,
        createdAtMillis = pin.pinnedAtMillis,
        sourceKind = ProjectCatalogSourceKind.PinnedCopy,
        absolutePath = path,
        pinned = true,
        taskId = taskId,
        recordingId = recordingId,
        bundleId = bundleId,
        durableRelativePath = pin.durableRelativePath,
        mimeHint = fileExtension(pin.title),
    )
}

private fun pathFingerprint(path: String): String {
    var hash = 0L
    for (ch in path) {
        hash = hash * 31L + ch.code
    }
    return (hash.toULong()).toString(16)
}

private fun fileExtension(name: String): String? =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotBlank() }
