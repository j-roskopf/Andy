package app.andy.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ActionsConfig
import app.andy.model.AgentTask
import app.andy.model.ProjectAction
import app.andy.model.ProjectNote
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
import app.andy.ui.agents.AgentBadge
import app.andy.ui.agents.AgentTaskComposerPane
import app.andy.ui.agents.AgentTaskDetail
import app.andy.ui.agents.UnreadDot
import app.andy.ui.agents.agentStatusColor
import app.andy.ui.agents.agentStatusLabel
import app.andy.ui.agents.formatElapsed
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun actionIconMarker(icon: String): String = when (icon.trim().lowercase()) {
    "run" -> "|>"
    "terminal" -> "|$"
    "test" -> "|="
    "debug" -> "|!"
    "build" -> "|#"
    "server" -> "|~"
    "deploy" -> "|^"
    else -> "|*"
}


private data class EditingProject(val project: ActionProject?)
private data class EditingAction(val projectId: String, val action: ProjectAction?)
private data class EditingNote(val projectId: String, val note: ProjectNote?)

private enum class ProjectRightPane { Chat, ActionOutput }
private enum class ProjectCanvas { Actions, Notes, Sessions }

@Composable
private fun ProjectCockpit(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
) {
    val scope = rememberCoroutineScope()
    val agentCliStatuses by services.agentRuns.cliStatuses.collectAsState()
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var canvas by remember { mutableStateOf(ProjectCanvas.Actions) }
    var workPane by remember { mutableStateOf(ProjectRightPane.Chat) }
    var query by remember { mutableStateOf("") }
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var editingNote by remember { mutableStateOf<EditingNote?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var completedNotesExpanded by remember { mutableStateOf(false) }
    var dockWidth by remember { mutableStateOf(620f) }
    var expandedActionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config.projects) {
        if (selectedProjectId !in config.projects.map { it.id }) selectedProjectId = config.projects.firstOrNull()?.id
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = System.currentTimeMillis() } }

    val projects = remember(config.projects, query) {
        config.projects.filter { project ->
            query.isBlank() || project.name.contains(query, true) || project.contextDir.contains(query, true)
        }
    }
    val project = config.projects.firstOrNull { it.id == selectedProjectId }
    val projectTasks = project?.let { item -> agentTasks.filter { it.projectId == item.id } }.orEmpty()
    fun updateProject(updated: ActionProject) = onConfigChange(config.copy(projects = config.projects.map { if (it.id == updated.id) updated else it }))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minimumCanvasWidth = 560.dp
        val maximumDockWidth = (maxWidth - 250.dp - minimumCanvasWidth - 14.dp - 36.dp).coerceAtLeast(500.dp)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(250.dp).fillMaxHeight().background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Toolbar("Workspaces", "${config.projects.size} project spaces", onPrimary = { editingProject = EditingProject(null) }, primaryLabel = "New")
            TextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Find a workspace", color = TextSecondary, fontFamily = MonoFont) })
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(projects, key = { it.id }) { item ->
                    val selected = item.id == selectedProjectId
                    Row(
                        Modifier.fillMaxWidth().background(if (selected) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R3))
                            .clickable { selectedProjectId = item.id; selectedTaskId = null; canvas = ProjectCanvas.Actions }.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(30.dp).background(if (selected) Rust else Border, RoundedCornerShape(AndyRadius.R2)))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.actions.size} runs · ${item.notes.count { !it.completed }} notes", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        project?.let { current ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
                        Text(current.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { editingProject = EditingProject(current) }) { Text("Edit workspace") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectCanvas.entries.forEach { tab -> FilterPill(tab.name.lowercase(), canvas == tab, if (tab == ProjectCanvas.Actions) Rust else Cyan) { canvas = tab } }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { workPane = ProjectRightPane.Chat; selectedTaskId = null }) { Text("New chat") }
                }
                PanelCard(Modifier.fillMaxSize()) {
                    when (canvas) {
                        ProjectCanvas.Actions -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Runbook", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Button(onClick = { editingAction = EditingAction(current.id, null) }) { Text("Add action") }
                            }
                            if (current.actions.isEmpty()) EmptyState("Add the commands you use most") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                items(current.actions, key = { it.id }) { action ->
                                    val expanded = expandedActionId == action.id
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .background(if (expanded) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3))
                                            .border(1.dp, if (expanded) AndyColors.OrangeBorder.copy(alpha = 0.58f) else Border, RoundedCornerShape(AndyRadius.R3))
                                            .clickable { expandedActionId = if (expanded) null else action.id }
                                            .animateContentSize(animationSpec = tween(220))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(actionIconMarker(action.icon), color = Rust, fontFamily = MonoFont)
                                            Text(action.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            OutlinedButton(onClick = { editingAction = EditingAction(current.id, action) }) { Text("Edit") }
                                            Button(onClick = { onActiveRunIdChange(services.actionRuns.run(current, action)); workPane = ProjectRightPane.ActionOutput }) { Text("Run") }
                                        }
                                        AnimatedVisibility(
                                            visible = expanded,
                                            enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                                            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(action.command, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ProjectCanvas.Notes -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Working notes", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); Button(onClick = { editingNote = EditingNote(current.id, null) }) { Text("New note") } }
                            val openNotes = current.notes.filterNot { it.completed }
                            val completedNotes = current.notes.filter { it.completed }
                            if (current.notes.isEmpty()) EmptyState("Capture a decision or a loose end") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                if (openNotes.isEmpty()) item(key = "no-open-notes") { EmptyState("Nothing open right now") }
                                items(openNotes, key = { "open-${it.id}" }) { note ->
                                    CockpitNoteRow(note, onToggle = { updateProject(current.copy(notes = current.notes.map { if (it.id == note.id) it.copy(completed = !it.completed) else it })) }, onEdit = { editingNote = EditingNote(current.id, note) })
                                }
                                if (completedNotes.isNotEmpty()) {
                                    item(key = "completed-toggle") {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3))
                                                .clickable { completedNotesExpanded = !completedNotesExpanded }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Box(Modifier.width(3.dp).height(28.dp).background(Rust.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R2)))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text("Finished work", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
                                                Text("${completedNotes.size} note${if (completedNotes.size == 1) "" else "s"} kept out of the active runbook", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                                            }
                                            Text(if (completedNotesExpanded) "Hide" else "Review", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                        }
                                    }
                                    item(key = "completed-content") {
                                        AnimatedVisibility(
                                            visible = completedNotesExpanded,
                                            enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                                            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                completedNotes.forEach { note ->
                                                    CockpitNoteRow(note, onToggle = { updateProject(current.copy(notes = current.notes.map { if (it.id == note.id) it.copy(completed = !it.completed) else it })) }, onEdit = { editingNote = EditingNote(current.id, note) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ProjectCanvas.Sessions -> {
                            Text("Agent sessions", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            if (projectTasks.isEmpty()) EmptyState("Start a chat from the work dock") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) { items(projectTasks, key = { it.id }) { task -> ProjectChatRow(task, task.id == selectedTaskId, nowMillis, onOpen = { selectedTaskId = task.id; workPane = ProjectRightPane.Chat; if (task.unread) services.agentRuns.markRead(task.id) }, onMarkUnread = { services.agentRuns.markUnread(task.id) }) } }
                        }
                    }
                }
            }
            PaneDivider(onDrag = { dragX -> dockWidth = (dockWidth - dragX).coerceIn(500f, maximumDockWidth.value) })
            Column(Modifier.width(dockWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterPill("Chat", workPane == ProjectRightPane.Chat, Cyan) { workPane = ProjectRightPane.Chat }; FilterPill("Terminal", workPane == ProjectRightPane.ActionOutput, Rust) { workPane = ProjectRightPane.ActionOutput } }
                PanelCard(Modifier.fillMaxSize()) {
                    if (workPane == ProjectRightPane.Chat) {
                        val selected = projectTasks.firstOrNull { it.id == selectedTaskId }
                        if (selected == null) AgentTaskComposerPane(services, agentCliStatuses, current, onSubmit = { draft -> scope.launch { selectedTaskId = services.agentRuns.createAndStart(draft).id } }, modifier = Modifier.fillMaxSize())
                        else AgentTaskDetail(services, selected, nowMillis, onDelete = { task -> scope.launch { services.agentRuns.delete(task.id, task.worktreePath != null); selectedTaskId = null } }, modifier = Modifier.fillMaxSize())
                    } else {
                        val activeRun = running.firstOrNull { it.runId == activeRunId }
                        if (activeRun == null) EmptyState("Run an action to open its output") else ProjectTerminalSurface(services, activeRun.runId, Modifier.fillMaxSize())
                    }
                }
            }
        } ?: EmptyState("Create a workspace to start")
    }
    }
    editingProject?.let { edit -> ProjectDialog(edit.project, config.projects, { editingProject = null }) { updated -> editingProject = null; onConfigChange(config.copy(projects = if (edit.project == null) config.projects + updated else config.projects.map { if (it.id == updated.id) updated else it })) } }
    editingAction?.let { edit -> ActionDialog(config.projects, edit.projectId, edit.action, { editingAction = null }) { projectId, action -> editingAction = null; onConfigChange(config.copy(projects = config.projects.map { project -> if (project.id == projectId) project.copy(actions = project.actions.filterNot { it.id == action.id } + action) else project })) } }
    editingNote?.let { edit -> NoteDialog(config.projects, edit.projectId, edit.note, { editingNote = null }) { note -> editingNote = null; onConfigChange(config.copy(projects = config.projects.map { project -> if (project.id == edit.projectId) project.copy(notes = project.notes.filterNot { it.id == note.id } + note) else project })) } }
}

@Composable
private fun CockpitNoteRow(
    note: ProjectNote,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(note.title, color = if (note.completed) TextSecondary else TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
            if (note.body.isNotBlank()) Text(note.body, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onToggle) { Text(if (note.completed) "Reopen" else "Done") }
        OutlinedButton(onClick = onEdit) { Text("Edit") }
    }
}

@Composable
internal fun ActionsScreen(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    terminalRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    active: Boolean = true,
) {
    ProjectCockpit(
        services = services,
        config = config,
        running = running,
        activeRunId = activeRunId,
        onActiveRunIdChange = onActiveRunIdChange,
        onConfigChange = onConfigChange,
        agentTasks = agentTasks,
    )
}

private fun actionStatusColor(status: ActionRunStatus): Color = when (status) {
    ActionRunStatus.Running -> Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
}

@Composable
private fun ProjectSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (expanded) "v" else ">",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.width(10.dp),
            )
            Text(
                title,
                color = TextSecondary,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
            Text(
                "($count)",
                color = TextSecondary.copy(alpha = 0.72f),
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        trailing()
    }
}

@Composable
private fun ProjectChatRow(
    task: AgentTask,
    selected: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f))
                .border(1.dp, if (selected) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border)
                .pointerInput(task.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.buttons.isSecondaryPressed) {
                                menuExpanded = true
                                change.consume()
                            }
                        }
                    }
                }
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (task.unread) UnreadDot()
                AgentBadge(task.agent)
            }
            Column(Modifier.weight(1f)) {
                Text(task.title, color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                formatElapsed(task.startedAtMillis, task.finishedAtMillis, nowMillis)?.let {
                    Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
            StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = PanelSoft,
        ) {
            DropdownMenuItem(
                text = { Text("Mark as unread", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onMarkUnread()
                },
                enabled = !task.unread,
            )
        }
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
                onClick = {
                    onSave(
                        ActionProject(
                            id = project?.id ?: nextId,
                            name = name.trim(),
                            contextDir = contextDir.trim(),
                            env = parseEnvLines(envText),
                            actions = project?.actions.orEmpty(),
                            notes = project?.notes.orEmpty(),
                        ),
                    )
                },
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

@Composable
private fun NoteDialog(
    projects: List<ActionProject>,
    projectId: String,
    note: ProjectNote?,
    onDismiss: () -> Unit,
    onSave: (ProjectNote) -> Unit,
) {
    var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var body by remember(note?.id) { mutableStateOf(note?.body.orEmpty()) }
    val noteIds = projects.flatMap { it.notes }.map { it.id }.toSet()
    val nextId = remember(noteIds, title) { nextActionId("note", title, noteIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (note == null) "New note" else "Edit note", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth())
                LabeledField("Body", body, { body = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 160.dp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ProjectNote(
                            id = note?.id ?: nextId,
                            title = title.trim(),
                            body = body.trim(),
                            completed = note?.completed == true,
                        ),
                    )
                },
                enabled = projects.any { it.id == projectId } && title.isNotBlank(),
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
