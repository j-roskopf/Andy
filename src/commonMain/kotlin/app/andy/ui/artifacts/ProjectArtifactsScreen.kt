package app.andy.ui.artifacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.loadImageBitmap
import app.andy.model.ActionProject
import app.andy.model.ProjectCatalogEntry
import app.andy.model.ProjectCatalogSourceKind
import app.andy.model.ProjectCatalogTab
import app.andy.pickFiles
import app.andy.service.AndyServices
import app.andy.ui.components.AndyAlertDialog
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.TabBar
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Artifacts + Media catalog: project-scoped ([projectId] set) or Agents Unscoped
 * ([projectId] null) with Media | Documents tabs.
 */
@Composable
fun ProjectArtifactsScreen(
    services: AndyServices,
    projectId: String?,
    projects: List<ActionProject> = emptyList(),
    initialSelectedId: String? = null,
    onOpenChat: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val allEntries by services.projectArtifacts.entries.collectAsState()
    val entries = remember(allEntries, projectId) {
        allEntries.filter { it.projectId == projectId }
    }
    var tab by remember { mutableStateOf(ProjectCatalogTab.Media) }
    var selectedId by remember { mutableStateOf(initialSelectedId) }
    var status by remember { mutableStateOf<String?>(null) }
    var assignDialog by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialSelectedId) {
        if (initialSelectedId != null) {
            selectedId = initialSelectedId
            val entry = entries.firstOrNull { it.id == initialSelectedId }
            if (entry != null) tab = entry.tab
        }
    }
    LaunchedEffect(Unit) { services.projectArtifacts.refresh() }

    val filtered = remember(entries, tab) { entries.filter { it.tab == tab } }
    val selected = filtered.firstOrNull { it.id == selectedId } ?: filtered.firstOrNull()
    LaunchedEffect(selected?.id, tab) {
        selectedId = selected?.id
        previewText = null
        val entry = selected ?: return@LaunchedEffect
        if (entry.tab == ProjectCatalogTab.Documents) {
            previewText = services.projectArtifacts.readTextPreview(entry.id)
        }
    }

    if (assignDialog && selected != null && projectId == null) {
        AssignProjectDialog(
            projects = projects,
            onDismiss = { assignDialog = false },
            onAssign = { targetId ->
                assignDialog = false
                scope.launch {
                    val result = services.projectArtifacts.assignToProject(selected.id, targetId)
                    status = if (result.isSuccess) "Assigned" else result.stderr.ifBlank { result.stdout }
                }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Artifacts + Media", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            status?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (projectId != null) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val paths = pickFiles(allowMultiple = true)
                        if (paths.isEmpty()) return@launch
                        val result = services.projectArtifacts.upload(projectId, paths)
                        status = if (result.isSuccess) "Uploaded ${paths.size}" else result.stderr.ifBlank { result.stdout }
                    }
                }) { Text("Upload") }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    services.projectArtifacts.refresh()
                    status = "Refreshed"
                }
            }) { Text("Refresh") }
        }
        TabBar(
            tabs = listOf("Media", "Documents"),
            selectedIndex = if (tab == ProjectCatalogTab.Media) 0 else 1,
            onSelect = { tab = if (it == 0) ProjectCatalogTab.Media else ProjectCatalogTab.Documents },
        )
        Row(Modifier.fillMaxSize().padding(AndySpace.Space3), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                if (filtered.isEmpty()) {
                    EmptyState(
                        if (projectId == null) "No unscoped artifacts"
                        else if (tab == ProjectCatalogTab.Media) "No media yet"
                        else "No documents yet",
                    )
                } else if (tab == ProjectCatalogTab.Media) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        contentPadding = PaddingValues(bottom = AndySpace.Space2),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            MediaGridCell(
                                entry = entry,
                                selected = entry.id == selected?.id,
                                onClick = { selectedId = entry.id },
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered, key = { it.id }) { entry ->
                            CatalogRow(
                                entry = entry,
                                selected = entry.id == selected?.id,
                                onClick = { selectedId = entry.id },
                            )
                        }
                    }
                }
            }
            PaneDivider(onDrag = {})
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                if (selected == null) {
                    EmptyState("Select an item")
                } else {
                    CatalogDetail(
                        entry = selected,
                        previewText = previewText,
                        showAssign = projectId == null && projects.isNotEmpty(),
                        onPin = {
                            scope.launch {
                                val result = services.projectArtifacts.pin(selected.id)
                                status = if (result.isSuccess) "Pinned" else result.stderr.ifBlank { result.stdout }
                            }
                        },
                        onUnpin = {
                            scope.launch {
                                val result = services.projectArtifacts.unpin(selected.id)
                                status = if (result.isSuccess) "Unpinned" else result.stderr.ifBlank { result.stdout }
                            }
                        },
                        onRemove = {
                            scope.launch {
                                val result = services.projectArtifacts.remove(selected.id)
                                status = if (result.isSuccess) "Removed" else result.stderr.ifBlank { result.stdout }
                                selectedId = null
                            }
                        },
                        onReveal = {
                            scope.launch {
                                val result = services.projectArtifacts.reveal(selected.id)
                                status = if (result.isSuccess) "Revealed" else result.stderr.ifBlank { result.stdout }
                            }
                        },
                        onAssign = { assignDialog = true },
                        onOpenChat = selected.taskId?.let { taskId ->
                            onOpenChat?.let { open -> { open(taskId) } }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridCell(
    entry: ProjectCatalogEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bitmap by rememberCatalogBitmap(entry.absolutePath)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                isLikelyVideo(entry) -> Text("VIDEO", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                else -> Text("—", color = TextSecondary, fontSize = 16.sp)
            }
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Cyan.copy(alpha = 0.18f)),
                )
            }
        }
        Text(
            entry.title,
            color = if (selected) Cyan else TextPrimary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CatalogRow(
    entry: ProjectCatalogEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            entry.title,
            color = if (selected) Cyan else TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${sourceLabel(entry.sourceKind)}${if (entry.pinned) " · pinned" else ""}",
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CatalogDetail(
    entry: ProjectCatalogEntry,
    previewText: String?,
    showAssign: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onRemove: () -> Unit,
    onReveal: () -> Unit,
    onAssign: () -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    val bitmap by rememberCatalogBitmap(entry.absolutePath.takeIf { entry.tab == ProjectCatalogTab.Media })
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
        Text(entry.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "${sourceLabel(entry.sourceKind)} · ${entry.id}",
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        entry.absolutePath?.let {
            Text(it, color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
            if (!entry.pinned && entry.sourceKind != ProjectCatalogSourceKind.DirectUpload) {
                Button(onClick = onPin) { Text("Pin") }
            } else if (entry.sourceKind == ProjectCatalogSourceKind.PinnedCopy) {
                OutlinedButton(onClick = onUnpin) { Text("Unpin") }
            }
            OutlinedButton(onClick = onReveal) { Text("Reveal") }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
            if (showAssign) OutlinedButton(onClick = onAssign) { Text("Assign…") }
            if (onOpenChat != null) OutlinedButton(onClick = onOpenChat) { Text("Open chat") }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                entry.tab == ProjectCatalogTab.Media && image != null -> Image(
                    bitmap = image,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                entry.tab == ProjectCatalogTab.Media && isLikelyVideo(entry) -> Text(
                    "Video file — use Reveal to open on disk.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                entry.tab == ProjectCatalogTab.Media -> Text(
                    "Preview unavailable",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                previewText != null -> {
                    SelectionContainer {
                        Text(
                            previewText,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        )
                    }
                }
                else -> Text("Loading preview…", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun rememberCatalogBitmap(path: String?) = produceState<ImageBitmap?>(initialValue = null, path) {
    value = if (path.isNullOrBlank()) {
        null
    } else {
        withContext(Dispatchers.Default) {
            runCatching { loadImageBitmap(path) }.getOrNull()
        }
    }
}

@Composable
private fun AssignProjectDialog(
    projects: List<ActionProject>,
    onDismiss: () -> Unit,
    onAssign: (String) -> Unit,
) {
    AndyAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space1)) {
                projects.forEach { project ->
                    Text(
                        project.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAssign(project.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun isLikelyVideo(entry: ProjectCatalogEntry): Boolean {
    val ext = entry.mimeHint ?: entry.title.substringAfterLast('.', "").lowercase()
    return ext in setOf("mp4", "mov", "webm", "m4v", "mkv")
}

private fun sourceLabel(kind: ProjectCatalogSourceKind): String = when (kind) {
    ProjectCatalogSourceKind.ChatAttachment -> "Chat"
    ProjectCatalogSourceKind.EvidenceFile -> "Evidence"
    ProjectCatalogSourceKind.WorkflowArtifact -> "Workflow"
    ProjectCatalogSourceKind.Recording -> "Recording"
    ProjectCatalogSourceKind.DirectUpload -> "Upload"
    ProjectCatalogSourceKind.PinnedCopy -> "Pinned"
}
