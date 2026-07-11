package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import app.andy.ui.agents.agentStatusColor
import app.andy.ui.agents.agentStatusLabel
import app.andy.ui.agents.formatElapsed
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
import kotlinx.coroutines.delay
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
private data class EditingNote(val projectId: String, val note: ProjectNote?)

private enum class ProjectRightPane { Chat, ActionOutput }

@Composable
internal fun ActionsScreen(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    active: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val agentCliStatuses by services.agentRuns.cliStatuses.collectAsState()
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var editingNote by remember { mutableStateOf<EditingNote?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var catalogPaneWidth by remember { mutableStateOf(560f) }
    var showCompletedByProject by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedAgentTaskId by remember { mutableStateOf<String?>(null) }
    var rightPane by remember { mutableStateOf(ProjectRightPane.Chat) }
    var knownAgentTaskIds by remember { mutableStateOf<Set<String>?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(active) {
        if (!active) {
            editingProject = null
            editingAction = null
            editingNote = null
            pendingConfirmation = null
        }
    }

    val runningIds = remember(running) { running.map { it.runId } }
    LaunchedEffect(runningIds) {
        if (activeRunId == null || running.none { it.runId == activeRunId }) {
            onActiveRunIdChange(running.lastOrNull()?.runId)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(config.projects) {
        val ids = config.projects.mapTo(linkedSetOf()) { it.id }
        if (selectedProjectId != null && selectedProjectId !in ids) {
            selectedProjectId = null
            selectedAgentTaskId = null
        }
        if (selectedProjectId == null) {
            selectedProjectId = config.projects.firstOrNull()?.id
        }
    }
    LaunchedEffect(agentTasks) {
        val currentIds = agentTasks.mapTo(linkedSetOf()) { it.id }
        val knownIds = knownAgentTaskIds
        if (knownIds != null) {
            agentTasks.filter { it.id !in knownIds }
                .maxByOrNull { it.createdAtMillis }
                ?.let { newest ->
                    selectedProjectId = newest.projectId ?: selectedProjectId
                    selectedAgentTaskId = newest.id
                    rightPane = ProjectRightPane.Chat
                }
        }
        knownAgentTaskIds = currentIds
        if (selectedAgentTaskId != null && selectedAgentTaskId !in currentIds) selectedAgentTaskId = null
    }

    fun selectProject(projectId: String) {
        if (selectedProjectId != projectId) {
            selectedProjectId = projectId
            selectedAgentTaskId = null
        } else {
            selectedProjectId = projectId
        }
        rightPane = ProjectRightPane.Chat
    }

    fun upsertProject(project: ActionProject) {
        val exists = config.projects.any { it.id == project.id }
        onConfigChange(
            config.copy(
                projects = if (exists) {
                    config.projects.map {
                        if (it.id == project.id) project.copy(actions = it.actions, notes = it.notes) else it
                    }
                } else {
                    config.projects + project
                },
            ),
        )
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

    fun upsertNote(projectId: String, note: ProjectNote) {
        onConfigChange(
            config.copy(
                projects = config.projects.map { project ->
                    if (project.id == projectId) {
                        project.copy(notes = project.notes.filterNot { it.id == note.id } + note)
                    } else {
                        project
                    }
                },
            ),
        )
    }

    fun updateNote(projectId: String, note: ProjectNote) {
        onConfigChange(
            config.copy(
                projects = config.projects.map { project ->
                    if (project.id == projectId) {
                        project.copy(notes = project.notes.map { if (it.id == note.id) note else it })
                    } else {
                        project
                    }
                },
            ),
        )
    }

    val actionCount = config.projects.sumOf { it.actions.size }
    val noteCount = config.projects.sumOf { it.notes.size }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(catalogPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar(
                "Projects",
                "${config.projects.size} projects / $actionCount actions / $noteCount notes",
                onPrimary = { editingProject = EditingProject(null) },
                primaryLabel = "new project",
            )
            if (config.projects.isEmpty()) {
                EmptyState("no projects yet")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(config.projects, key = { it.id }) { project ->
                        val selected = project.id == selectedProjectId
                        val showCompleted = showCompletedByProject[project.id] == true
                        val visibleNotes = if (showCompleted) project.notes else project.notes.filterNot { it.completed }
                        val completedCount = project.notes.count { it.completed }
                        val projectChats = agentTasks.filter { it.projectId == project.id }
                            .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
                        PanelCard(
                            Modifier
                                .border(
                                    1.dp,
                                    if (selected) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Color.Transparent,
                                    RoundedCornerShape(AndyRadius.R3),
                                )
                                .clickable { selectProject(project.id) },
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(project.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(onClick = { editingProject = EditingProject(project) }) { Text("edit") }
                                OutlinedButton(onClick = { editingAction = EditingAction(project.id, null) }) { Text("+ action") }
                                OutlinedButton(onClick = { editingNote = EditingNote(project.id, null) }) { Text("+ note") }
                                OutlinedButton(onClick = {
                                    pendingConfirmation = PendingConfirmation(
                                        "Delete project?",
                                        "${project.name}, ${project.actions.size} actions, ${project.notes.size} notes",
                                    ) {
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
                                                    rightPane = ProjectRightPane.ActionOutput
                                                },
                                                colors = if (liveRun != null) ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100) else primaryButtonColors(),
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                            ) { Text(if (liveRun != null) "stop" else "run", fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Chats", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            if (projectChats.isEmpty()) {
                                Text("no chats yet — start one on the right", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                            } else {
                                projectChats.forEach { task ->
                                    ProjectChatRow(
                                        task = task,
                                        selected = task.id == selectedAgentTaskId && rightPane == ProjectRightPane.Chat,
                                        nowMillis = nowMillis,
                                        onOpen = {
                                            selectedProjectId = project.id
                                            selectedAgentTaskId = task.id
                                            rightPane = ProjectRightPane.Chat
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Notes", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                if (completedCount > 0) {
                                    FilterPill("show completed ($completedCount)", showCompleted, Cyan) {
                                        showCompletedByProject = showCompletedByProject + (project.id to !showCompleted)
                                    }
                                    if (showCompleted) {
                                        OutlinedButton(
                                            onClick = {
                                                pendingConfirmation = PendingConfirmation(
                                                    "Clear completed notes?",
                                                    "$completedCount completed note(s) in ${project.name}",
                                                ) {
                                                    onConfigChange(
                                                        config.copy(
                                                            projects = config.projects.map {
                                                                if (it.id == project.id) it.copy(notes = it.notes.filterNot { note -> note.completed }) else it
                                                            },
                                                        ),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        ) { Text("clear completed", fontSize = 11.sp) }
                                    }
                                }
                            }
                            if (visibleNotes.isEmpty()) {
                                Text(
                                    if (project.notes.isEmpty()) "no notes" else "no open notes",
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontSize = 12.sp,
                                )
                            } else {
                                visibleNotes.forEach { note ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(
                                            Modifier.weight(1f)
                                                .background(AndyColors.Neutral900.copy(alpha = 0.72f))
                                                .border(1.dp, Color.White.copy(alpha = 0.05f))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                note.title,
                                                color = if (note.completed) TextSecondary else TextPrimary,
                                                fontFamily = MonoFont,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (note.body.isNotBlank()) {
                                                Text(
                                                    note.body,
                                                    color = TextSecondary,
                                                    fontFamily = MonoFont,
                                                    fontSize = 11.sp,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { updateNote(project.id, note.copy(completed = !note.completed)) },
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            ) { Text(if (note.completed) "undo" else "done", fontSize = 11.sp) }
                                            OutlinedButton(
                                                onClick = { editingNote = EditingNote(project.id, note) },
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            ) { Text("edit", fontSize = 11.sp) }
                                            OutlinedButton(
                                                onClick = {
                                                    pendingConfirmation = PendingConfirmation("Delete note?", note.title) {
                                                        onConfigChange(
                                                            config.copy(
                                                                projects = config.projects.map {
                                                                    if (it.id == project.id) it.copy(notes = it.notes.filterNot { row -> row.id == note.id }) else it
                                                                },
                                                            ),
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            ) { Text("del", fontSize = 11.sp) }
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
            val selectedProject = config.projects.firstOrNull { it.id == selectedProjectId }
            val selectedAgentTask = agentTasks.firstOrNull { it.id == selectedAgentTaskId }
                ?.takeIf { selectedProject == null || it.projectId == selectedProject.id }
            val activeRun = running.firstOrNull { it.runId == activeRunId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("chat", rightPane == ProjectRightPane.Chat, Cyan) { rightPane = ProjectRightPane.Chat }
                FilterPill("action output", rightPane == ProjectRightPane.ActionOutput, Rust) { rightPane = ProjectRightPane.ActionOutput }
                Spacer(Modifier.weight(1f))
                if (rightPane == ProjectRightPane.Chat && selectedAgentTask != null) {
                    OutlinedButton(onClick = { selectedAgentTaskId = null }) { Text("new chat") }
                }
            }
            when (rightPane) {
                ProjectRightPane.Chat -> {
                    PanelCard(Modifier.fillMaxSize()) {
                        when {
                            selectedProject == null -> EmptyState("select a project to start a chat")
                            selectedAgentTask != null -> {
                                AgentTaskDetail(
                                    services = services,
                                    task = selectedAgentTask,
                                    nowMillis = nowMillis,
                                    onDelete = { task ->
                                        val removesWorktree = task.worktreePath != null
                                        pendingConfirmation = PendingConfirmation(
                                            "Delete chat?",
                                            buildString {
                                                append(task.title)
                                                if (task.isActive) append(" — it is still running and will be stopped")
                                                if (removesWorktree) append(" — its worktree and branch will be removed (uncommitted work is lost)")
                                            },
                                        ) {
                                            scope.launch {
                                                services.agentRuns.delete(task.id, removeWorktree = removesWorktree)
                                                selectedAgentTaskId = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            else -> {
                                AgentTaskComposerPane(
                                    services = services,
                                    cliStatuses = agentCliStatuses,
                                    projectContext = selectedProject,
                                    onSubmit = { draft ->
                                        scope.launch {
                                            val task = services.agentRuns.createAndStart(draft)
                                            selectedAgentTaskId = task.id
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                ProjectRightPane.ActionOutput -> {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running.isEmpty()) {
                            Text("run an action to see output here", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                        } else {
                            running.forEach { run ->
                                FilterPill(
                                    "${actionIconMarker(run.icon)} ${run.actionName}",
                                    run.runId == activeRunId,
                                    actionStatusColor(run.status),
                                ) { onActiveRunIdChange(run.runId) }
                            }
                        }
                    }
                    PanelCard(Modifier.fillMaxSize()) {
                        if (activeRun == null) {
                            EmptyState("run an action to see output here")
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
    editingNote?.let { editing ->
        NoteDialog(config.projects, editing.projectId, editing.note, onDismiss = { editingNote = null }) { note ->
            editingNote = null
            upsertNote(editing.projectId, note)
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
private fun ProjectChatRow(
    task: AgentTask,
    selected: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f))
            .border(1.dp, if (selected) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border)
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentBadge(task.agent)
        Column(Modifier.weight(1f)) {
            Text(task.title, color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            formatElapsed(task.startedAtMillis, task.finishedAtMillis, nowMillis)?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
        }
        StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
    }
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
