package app.andy.desktop.service

import app.andy.domain.ProjectCatalogSourceHit
import app.andy.domain.chatAttachmentCatalogId
import app.andy.domain.evidenceCatalogId
import app.andy.domain.isCatalogExcludedWorkflowArtifact
import app.andy.domain.mergeProjectCatalogForProject
import app.andy.domain.mergeProjectCatalogForUnscoped
import app.andy.domain.projectCatalogTabForFileName
import app.andy.domain.recordingCatalogId
import app.andy.domain.uploadCatalogId
import app.andy.domain.withAssignment
import app.andy.domain.withPin
import app.andy.domain.withUnlinked
import app.andy.domain.withUpload
import app.andy.domain.withoutPin
import app.andy.domain.withoutUpload
import app.andy.domain.workflowCatalogId
import app.andy.desktop.service.agents.AgentWorkflowArtifacts
import app.andy.desktop.service.agents.defaultAndyAgentArtifactsDir
import app.andy.model.CatalogPinRecord
import app.andy.model.CatalogUploadRecord
import app.andy.model.ProjectCatalogEntry
import app.andy.model.ProjectCatalogSourceKind
import app.andy.model.ProjectCatalogStore
import app.andy.service.AgentRunService
import app.andy.service.BugService
import app.andy.service.CommandResult
import app.andy.service.ProjectArtifactCatalogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.util.UUID

/**
 * Hybrid Artifacts + Media catalog: derives live rows from agent chats, evidence, workflow
 * dirs, and recordings; persists unlinks/pins/uploads under ~/.andy/projects and unscoped.
 */
class DesktopProjectArtifactCatalogService(
    private val scope: CoroutineScope,
    private val agentRuns: AgentRunService,
    private val bugs: BugService,
    private val andyHome: File = File(System.getProperty("user.home"), ".andy"),
    private val agentsDir: File = defaultAndyAgentArtifactsDir(),
) : ProjectArtifactCatalogService {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val projectsRoot = File(andyHome, "projects")
    private val unscopedRoot = File(andyHome, "catalog/unscoped")

    private val _entries = MutableStateFlow<List<ProjectCatalogEntry>>(emptyList())
    override val entries: StateFlow<List<ProjectCatalogEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            agentRuns.tasks.collectLatest { refresh() }
        }
    }

    override fun entriesFor(projectId: String?): List<ProjectCatalogEntry> =
        _entries.value.filter { it.projectId == projectId }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val hits = collectHits()
        val unscopedStore = readStore(unscopedRoot)
        val projectIds = (
            hits.mapNotNull { it.projectId } +
                projectsRoot.listFiles()?.map { it.name }.orEmpty() +
                unscopedStore.assignments.values
            ).toSet()

        val merged = buildList {
            addAll(
                mergeProjectCatalogForUnscoped(
                    hits = hits,
                    unscopedStore = unscopedStore,
                    durableAbsolutePath = { rel -> File(artifactsDir(unscopedRoot), rel).takeIf { it.isFile }?.absolutePath },
                ),
            )
            for (projectId in projectIds) {
                val store = readStore(projectRoot(projectId))
                addAll(
                    mergeProjectCatalogForProject(
                        hits = hits,
                        projectId = projectId,
                        projectStore = store,
                        unscopedStore = unscopedStore,
                        projectDurablePath = { rel ->
                            File(artifactsDir(projectRoot(projectId)), rel).takeIf { it.isFile }?.absolutePath
                        },
                        unscopedDurablePath = { rel ->
                            File(artifactsDir(unscopedRoot), rel).takeIf { it.isFile }?.absolutePath
                        },
                    ),
                )
            }
        }.distinctBy { it.id }.sortedByDescending { it.createdAtMillis }
        _entries.value = merged
    }

    override suspend fun upload(projectId: String, paths: List<String>): CommandResult = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext CommandResult.failure("No files selected")
        val root = projectRoot(projectId)
        val artifacts = artifactsDir(root).also { it.mkdirs() }
        var store = readStore(root)
        val now = System.currentTimeMillis()
        for (path in paths) {
            val source = File(path)
            if (!source.isFile) continue
            val uploadId = UUID.randomUUID().toString()
            val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val relative = "$uploadId-$safeName"
            val dest = File(artifacts, relative)
            source.copyTo(dest, overwrite = true)
            store = store.withUpload(
                CatalogUploadRecord(
                    id = uploadId,
                    projectId = projectId,
                    durableRelativePath = relative,
                    title = source.name,
                    tab = projectCatalogTabForFileName(source.name),
                    createdAtMillis = now,
                ),
            )
        }
        writeStore(root, store)
        refresh()
        CommandResult.success("Uploaded")
    }

    override suspend fun pin(entryId: String): CommandResult = withContext(Dispatchers.IO) {
        val entry = _entries.value.firstOrNull { it.id == entryId }
            ?: return@withContext CommandResult.failure("Entry not found")
        if (entry.pinned) return@withContext CommandResult.success("Already pinned")
        val source = entry.absolutePath?.let(::File)?.takeIf { it.isFile }
            ?: return@withContext CommandResult.failure("Source file missing")
        val root = catalogRootFor(entry.projectId)
        val artifacts = artifactsDir(root).also { it.mkdirs() }
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val relative = "pin-${entryId.hashCode().toUInt().toString(16)}-$safeName"
        source.copyTo(File(artifacts, relative), overwrite = true)
        var store = readStore(root)
        store = store.withPin(
            CatalogPinRecord(
                sourceId = entryId,
                durableRelativePath = relative,
                pinnedAtMillis = System.currentTimeMillis(),
                title = entry.title,
                tab = entry.tab,
                originalSourceKind = entry.sourceKind,
                taskId = entry.taskId,
                recordingId = entry.recordingId,
                bundleId = entry.bundleId,
            ),
        )
        writeStore(root, store)
        refresh()
        CommandResult.success("Pinned")
    }

    override suspend fun unpin(entryId: String): CommandResult = withContext(Dispatchers.IO) {
        val entry = _entries.value.firstOrNull { it.id == entryId }
            ?: return@withContext CommandResult.failure("Entry not found")
        if (entry.sourceKind == ProjectCatalogSourceKind.DirectUpload) {
            return@withContext CommandResult.failure("Uploads stay durable; remove instead")
        }
        val root = catalogRootFor(entry.projectId)
        var store = readStore(root)
        val pin = store.pins.firstOrNull { it.sourceId == entryId }
        if (pin != null) {
            File(artifactsDir(root), pin.durableRelativePath).delete()
            store = store.withoutPin(entryId)
            writeStore(root, store)
        }
        // Also clear unscoped pin if assigned
        if (entry.projectId != null) {
            var unscoped = readStore(unscopedRoot)
            val uPin = unscoped.pins.firstOrNull { it.sourceId == entryId }
            if (uPin != null) {
                File(artifactsDir(unscopedRoot), uPin.durableRelativePath).delete()
                unscoped = unscoped.withoutPin(entryId)
                writeStore(unscopedRoot, unscoped)
            }
        }
        refresh()
        CommandResult.success("Unpinned")
    }

    override suspend fun remove(entryId: String): CommandResult = withContext(Dispatchers.IO) {
        val entry = _entries.value.firstOrNull { it.id == entryId }
            ?: return@withContext CommandResult.failure("Entry not found")
        when (entry.sourceKind) {
            ProjectCatalogSourceKind.DirectUpload -> {
                val uploadId = entryId.removePrefix("upload:")
                val root = catalogRootFor(entry.projectId)
                var store = readStore(root)
                val upload = store.uploads.firstOrNull { it.id == uploadId || uploadCatalogId(it.id) == entryId }
                if (upload != null) {
                    File(artifactsDir(root), upload.durableRelativePath).delete()
                    store = store.withoutUpload(upload.id)
                    writeStore(root, store)
                }
                // Assigned upload living under unscoped root
                var unscoped = readStore(unscopedRoot)
                val uUpload = unscoped.uploads.firstOrNull { uploadCatalogId(it.id) == entryId }
                if (uUpload != null) {
                    File(artifactsDir(unscopedRoot), uUpload.durableRelativePath).delete()
                    unscoped = unscoped.withoutUpload(uUpload.id)
                    writeStore(unscopedRoot, unscoped)
                }
            }
            ProjectCatalogSourceKind.PinnedCopy -> {
                unpin(entryId)
                // After unpin, if live source still exists user may want unlink too — treat remove as unlink+delete durable
                val root = catalogRootFor(entry.projectId)
                writeStore(root, readStore(root).withUnlinked(entryId))
                writeStore(unscopedRoot, readStore(unscopedRoot).withUnlinked(entryId))
            }
            else -> {
                // Indexed: unlink only
                val root = catalogRootFor(entry.projectId)
                writeStore(root, readStore(root).withUnlinked(entryId))
                if (entry.projectId == null) {
                    writeStore(unscopedRoot, readStore(unscopedRoot).withUnlinked(entryId))
                }
            }
        }
        refresh()
        CommandResult.success("Removed")
    }

    override suspend fun assignToProject(entryId: String, projectId: String): CommandResult = withContext(Dispatchers.IO) {
        val entry = _entries.value.firstOrNull { it.id == entryId }
            ?: return@withContext CommandResult.failure("Entry not found")
        val recordingId = entry.recordingId
        if (recordingId != null) {
            val result = bugs.assignBugProject(recordingId, projectId)
            if (!result.isSuccess) return@withContext result
        }
        var unscoped = readStore(unscopedRoot)
        unscoped = unscoped.withAssignment(entryId, projectId)
        writeStore(unscopedRoot, unscoped)
        refresh()
        CommandResult.success("Assigned to project")
    }

    override suspend fun reveal(entryId: String): CommandResult = withContext(Dispatchers.IO) {
        val path = absolutePath(entryId) ?: return@withContext CommandResult.failure("File not found")
        val file = File(path)
        runCatching {
            val desktop = Desktop.getDesktop()
            if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(file)
            } else {
                desktop.open(file.parentFile ?: file)
            }
            CommandResult.success(path)
        }.getOrElse { CommandResult.failure(it.message ?: "Reveal failed") }
    }

    override suspend fun absolutePath(entryId: String): String? = withContext(Dispatchers.IO) {
        _entries.value.firstOrNull { it.id == entryId }?.absolutePath
    }

    override suspend fun readTextPreview(entryId: String, maxChars: Int): String? = withContext(Dispatchers.IO) {
        val path = absolutePath(entryId) ?: return@withContext null
        val file = File(path)
        if (!file.isFile) return@withContext null
        val text = file.readText()
        if (text.length <= maxChars) text else text.take(maxChars) + "\n…"
    }

    private suspend fun collectHits(): List<ProjectCatalogSourceHit> {
        val tasks = agentRuns.tasks.value.filterNot { it.temporary }
        val hits = mutableListOf<ProjectCatalogSourceHit>()
        for (task in tasks) {
            for (imagePath in task.imagePaths) {
                val file = File(imagePath)
                if (!file.isFile) continue
                hits += ProjectCatalogSourceHit(
                    id = chatAttachmentCatalogId(task.id, file.absolutePath),
                    projectId = task.projectId,
                    title = file.name,
                    createdAtMillis = file.lastModified().takeIf { it > 0 } ?: task.createdAtMillis,
                    sourceKind = ProjectCatalogSourceKind.ChatAttachment,
                    absolutePath = file.absolutePath,
                    taskId = task.id,
                )
            }
            val evidenceRoot = File(File(agentsDir, task.id), "evidence")
            if (evidenceRoot.isDirectory) {
                evidenceRoot.walkTopDown().filter { it.isFile && it.name != "manifest.json" }.forEach { file ->
                    val bundleId = file.relativeTo(evidenceRoot).path.substringBefore('/').ifBlank { "bundle" }
                    hits += ProjectCatalogSourceHit(
                        id = evidenceCatalogId(task.id, bundleId, file.absolutePath),
                        projectId = task.projectId,
                        title = file.name,
                        createdAtMillis = file.lastModified(),
                        sourceKind = ProjectCatalogSourceKind.EvidenceFile,
                        absolutePath = file.absolutePath,
                        taskId = task.id,
                        bundleId = bundleId,
                    )
                }
            }
            val workflowDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
            if (workflowDir.isDirectory) {
                workflowDir.listFiles()
                    ?.filter { it.isFile && !isCatalogExcludedWorkflowArtifact(it.name) }
                    ?.forEach { file ->
                    hits += ProjectCatalogSourceHit(
                        id = workflowCatalogId(task.id, file.name),
                        projectId = task.projectId,
                        title = file.name,
                        createdAtMillis = file.lastModified(),
                        sourceKind = ProjectCatalogSourceKind.WorkflowArtifact,
                        absolutePath = file.absolutePath,
                        taskId = task.id,
                    )
                }
            }
        }
        val reports = runCatching { bugs.listBugs() + bugs.listRecordings() }.getOrDefault(emptyList())
        for (report in reports) {
            val reportProject = report.projectIdentity?.projectId
            val reportDir = bugs.bugDirectoryPath(report.id)?.let(::File) ?: continue
            for (artifact in report.artifacts) {
                val file = File(reportDir, artifact.relativePath)
                if (!file.isFile) continue
                // Prefer visual / document-ish artifacts; skip empty metadata noise optionally — include all files
                hits += ProjectCatalogSourceHit(
                    id = recordingCatalogId(report.id, artifact.name),
                    projectId = reportProject,
                    title = artifact.name,
                    createdAtMillis = report.capturedAtMillis,
                    sourceKind = ProjectCatalogSourceKind.Recording,
                    absolutePath = file.absolutePath,
                    recordingId = report.id,
                )
            }
            // Screenshot sidecars under events/screenshots
            val shots = File(reportDir, "events/screenshots")
            if (shots.isDirectory) {
                shots.listFiles()?.filter { it.isFile }?.forEach { file ->
                    hits += ProjectCatalogSourceHit(
                        id = recordingCatalogId(report.id, "screenshot-${file.name}"),
                        projectId = reportProject,
                        title = file.name,
                        createdAtMillis = file.lastModified(),
                        sourceKind = ProjectCatalogSourceKind.Recording,
                        absolutePath = file.absolutePath,
                        recordingId = report.id,
                    )
                }
            }
        }
        return hits
    }

    private fun projectRoot(projectId: String): File = File(projectsRoot, projectId)
    private fun catalogRootFor(projectId: String?): File =
        if (projectId == null) unscopedRoot else projectRoot(projectId)
    private fun artifactsDir(root: File): File = File(root, "artifacts")
    private fun storeFile(root: File): File = File(root, "catalog.json")

    private fun readStore(root: File): ProjectCatalogStore {
        val file = storeFile(root)
        if (!file.isFile) return ProjectCatalogStore()
        return runCatching { json.decodeFromString<ProjectCatalogStore>(file.readText()) }
            .getOrDefault(ProjectCatalogStore())
    }

    private fun writeStore(root: File, store: ProjectCatalogStore) {
        root.mkdirs()
        storeFile(root).writeText(json.encodeToString(store))
    }
}
