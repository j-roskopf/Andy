package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.PendingConfirmation
import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ActionsConfig
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.pickDirectory
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal fun actionIconMarker(icon: String): String = when (icon.trim().lowercase()) {
    "run" -> "|>"
    "test" -> "|="
    "debug" -> "|!"
    "build" -> "|#"
    "server" -> "|~"
    "deploy" -> "|^"
    else -> "|*"
}


private data class EditingProject(val project: ActionProject?)
private data class EditingAction(val projectId: String, val action: ProjectAction?)

@Composable
internal fun ActionsScreen(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
) {
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var catalogPaneWidth by remember { mutableStateOf(560f) }

    val runningIds = remember(running) { running.map { it.runId } }
    LaunchedEffect(runningIds) {
        if (activeRunId == null || running.none { it.runId == activeRunId }) {
            onActiveRunIdChange(running.lastOrNull()?.runId)
        }
    }

    fun upsertProject(project: ActionProject) {
        val exists = config.projects.any { it.id == project.id }
        onConfigChange(config.copy(projects = if (exists) config.projects.map { if (it.id == project.id) project.copy(actions = it.actions) else it } else config.projects + project))
    }

    fun upsertAction(projectId: String, previousProjectId: String?, action: ProjectAction) {
        onConfigChange(
            config.copy(
                projects = config.projects.map { project ->
                    when {
                        project.id == projectId -> project.copy(actions = project.actions.filterNot { it.id == action.id } + action)
                        previousProjectId != null && project.id == previousProjectId -> project.copy(actions = project.actions.filterNot { it.id == action.id })
                        else -> project
                    }
                },
            ),
        )
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(catalogPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar("Actions", "${config.projects.size} projects / ${config.projects.sumOf { it.actions.size }} actions", onPrimary = { editingProject = EditingProject(null) }, primaryLabel = "new project")
            if (config.projects.isEmpty()) {
                EmptyState("no action projects yet")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(config.projects, key = { it.id }) { project ->
                        PanelCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(project.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(onClick = { editingProject = EditingProject(project) }) { Text("edit") }
                                OutlinedButton(onClick = { editingAction = EditingAction(project.id, null) }) { Text("+ action") }
                                OutlinedButton(onClick = {
                                    pendingConfirmation = PendingConfirmation("Delete project?", "${project.name} and ${project.actions.size} actions") {
                                        onConfigChange(config.copy(projects = config.projects.filterNot { it.id == project.id }))
                                    }
                                }) { Text("del") }
                            }
                            if (project.actions.isEmpty()) {
                                Text("no actions", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                            } else {
                                project.actions.forEach { action ->
                                    val liveRun = running.firstOrNull { it.actionId == action.id && it.status == ActionRunStatus.Running }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            Modifier.heightIn(min = 32.dp)
                                                .background(AndyColors.Neutral900.copy(alpha = 0.72f))
                                                .border(1.dp, Color.White.copy(alpha = 0.05f))
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(actionIconMarker(action.icon), color = Rust, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                                            Text(action.name, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { editingAction = EditingAction(project.id, action) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("edit", fontSize = 11.sp) }
                                            OutlinedButton(onClick = {
                                                pendingConfirmation = PendingConfirmation("Delete action?", action.name) {
                                                    onConfigChange(config.copy(projects = config.projects.map { if (it.id == project.id) it.copy(actions = it.actions.filterNot { row -> row.id == action.id }) else it }))
                                                }
                                            }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("del", fontSize = 11.sp) }
                                            Button(
                                                onClick = {
                                                    if (liveRun != null) {
                                                        services.actionRuns.stop(liveRun.runId)
                                                        onActiveRunIdChange(liveRun.runId)
                                                    } else {
                                                        onActiveRunIdChange(services.actionRuns.run(project, action))
                                                    }
                                                },
                                                colors = if (liveRun != null) ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100) else primaryButtonColors(),
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                            ) { Text(if (liveRun != null) "stop" else "run", fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        PaneDivider(onDrag = { dragX -> catalogPaneWidth = (catalogPaneWidth + dragX).coerceIn(420f, 900f) })
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val activeRun = running.firstOrNull { it.runId == activeRunId }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running.isEmpty()) {
                    Text("no running actions", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                } else {
                    running.forEach { run ->
                        FilterPill("${actionIconMarker(run.icon)} ${run.actionName}", run.runId == activeRunId, actionStatusColor(run.status)) { onActiveRunIdChange(run.runId) }
                    }
                }
            }
            PanelCard(Modifier.fillMaxSize()) {
                if (activeRun == null) {
                    EmptyState("run an action to see output")
                } else {
                    val lines by services.actionRuns.output(activeRun.runId).collectAsState()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(activeRun.actionName, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("${activeRun.cwd}  $ ${activeRun.command}", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusTag(activeRun.status.name, actionStatusColor(activeRun.status))
                        if (activeRun.status == ActionRunStatus.Running) {
                            Button(onClick = { services.actionRuns.stop(activeRun.runId) }, colors = ButtonDefaults.buttonColors(containerColor = Rust)) { Text("Stop") }
                        }
                        OutlinedButton(onClick = {
                            services.actionRuns.clear(activeRun.runId)
                            onActiveRunIdChange(running.firstOrNull { it.runId != activeRun.runId }?.runId)
                        }) { Text("Clear") }
                    }
                    ActionConsole(lines, Modifier.fillMaxSize())
                }
            }
        }
    }

    editingProject?.let { editing ->
        ProjectDialog(editing.project, config.projects, onDismiss = { editingProject = null }) {
            editingProject = null
            upsertProject(it)
        }
    }
    editingAction?.let { editing ->
        ActionDialog(config.projects, editing.projectId, editing.action, onDismiss = { editingAction = null }) { targetProjectId, action ->
            editingAction = null
            upsertAction(targetProjectId, editing.projectId, action)
        }
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
            pendingConfirmation = null
            confirmation.onConfirm()
        })
    }
}

@Composable
private fun actionStatusColor(status: ActionRunStatus): Color = when (status) {
    ActionRunStatus.Running -> Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
}

@Composable
private fun ActionConsole(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stickToBottom by remember { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) true else (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 1
        }
    }
    LaunchedEffect(lines.size, stickToBottom) {
        if (stickToBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) stickToBottom = false
                if (atBottom) stickToBottom = true
            }
    }
    Box(modifier.background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))) {
        LazyColumn(Modifier.fillMaxSize().padding(10.dp).padding(end = 8.dp), state = listState) {
            itemsIndexed(lines) { _, line ->
                Text(line, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        DraggableScrollbar(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            visibleItems = listState.layoutInfo.visibleItemsInfo.size,
            totalItems = listState.layoutInfo.totalItemsCount,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            onDragToIndex = { index ->
                stickToBottom = index >= (listState.layoutInfo.totalItemsCount - listState.layoutInfo.visibleItemsInfo.size - 1)
                scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
            },
        )
    }
}

@Composable
private fun ProjectDialog(project: ActionProject?, existingProjects: List<ActionProject>, onDismiss: () -> Unit, onSave: (ActionProject) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var contextDir by remember(project?.id) { mutableStateOf(project?.contextDir.orEmpty()) }
    var envText by remember(project?.id) { mutableStateOf(project?.env?.toEnvText().orEmpty()) }
    val nextId = remember(existingProjects.size, name) { nextActionId("proj", name, existingProjects.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (project == null) "New project" else "Edit project", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Context directory", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(contextDir, { contextDir = it }, readOnly = true, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont), colors = fieldColors())
                        Button(onClick = { scope.launch { pickDirectory(contextDir.ifBlank { null })?.let { contextDir = it } } }, colors = primaryButtonColors()) { Text("browse") }
                    }
                }
                LabeledField("Env (KEY=VALUE)", envText, { envText = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 120.dp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(ActionProject(project?.id ?: nextId, name.trim(), contextDir.trim(), parseEnvLines(envText), project?.actions.orEmpty())) },
                enabled = name.isNotBlank() && contextDir.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionDialog(projects: List<ActionProject>, initialProjectId: String, action: ProjectAction?, onDismiss: () -> Unit, onSave: (String, ProjectAction) -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedProjectId by remember(action?.id, initialProjectId) { mutableStateOf(initialProjectId) }
    var name by remember(action?.id) { mutableStateOf(action?.name.orEmpty()) }
    var icon by remember(action?.id) { mutableStateOf(action?.icon ?: "run") }
    var command by remember(action?.id) { mutableStateOf(action?.command.orEmpty()) }
    var cwd by remember(action?.id) { mutableStateOf(action?.cwd.orEmpty()) }
    var envText by remember(action?.id) { mutableStateOf(action?.env?.toEnvText().orEmpty()) }
    val actionIds = projects.flatMap { it.actions }.map { it.id }.toSet()
    val nextId = remember(actionIds, name) { nextActionId("act", name, actionIds) }
    val iconOptions = listOf("run", "test", "debug", "build", "server", "deploy")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (action == null) "New action" else "Edit action", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Project", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { project -> FilterPill(project.name, project.id == selectedProjectId, Rust) { selectedProjectId = project.id } }
                }
                LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
                Text("Icon", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    iconOptions.forEach { option -> FilterPill("${actionIconMarker(option)} $option", icon == option, Rust) { icon = option } }
                }
                LabeledField("Command", command, { command = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 130.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cwd override", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            cwd,
                            { cwd = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(54.dp),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                            colors = fieldColors(),
                            placeholder = { Text("blank uses project context dir", color = TextSecondary, fontFamily = MonoFont) },
                        )
                        Button(
                            onClick = {
                                val initial = cwd.ifBlank { projects.firstOrNull { it.id == selectedProjectId }?.contextDir.orEmpty() }
                                scope.launch { pickDirectory(initial.ifBlank { null })?.let { cwd = it } }
                            },
                            colors = primaryButtonColors(),
                        ) { Text("browse") }
                    }
                }
                LabeledField("Env (KEY=VALUE)", envText, { envText = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 110.dp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedProjectId, ProjectAction(action?.id ?: nextId, name.trim(), icon, command.trim(), cwd.trim().takeIf { it.isNotBlank() }, parseEnvLines(envText))) },
                enabled = projects.any { it.id == selectedProjectId } && name.isNotBlank() && command.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Map<String, String>.toEnvText(): String = entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun parseEnvLines(value: String): Map<String, String> = value.lines()
    .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) null else trimmed.take(index).trim() to trimmed.drop(index + 1).trim()
    }
    .toMap()

private fun nextActionId(prefix: String, label: String, existing: Set<String>): String {
    val base = label.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifBlank { prefix }
    var id = "$prefix-$base"
    var index = 2
    while (id in existing) {
        id = "$prefix-$base-$index"
        index++
    }
    return id
}

