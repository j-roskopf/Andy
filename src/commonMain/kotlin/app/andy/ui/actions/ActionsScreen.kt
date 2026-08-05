package app.andy.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.project_new_chat
import app.andy.ui.components.TabBar
import app.andy.ui.components.AndyAlertDialog
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PendingConfirmation
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentTask
import app.andy.model.ConfigSource
import app.andy.model.ProjectAction
import app.andy.model.ProjectNote
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectWorkflowState
import app.andy.model.WorkspaceState
import app.andy.pickDirectory
import app.andy.service.AndyServices
import app.andy.currentTimeMillis
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.WorkspaceCanvas
import app.andy.ui.components.WorkspaceEmptyCanvas
import app.andy.ui.components.WorkspaceRail
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.model.AgentStatus
import app.andy.ui.agents.AgentTaskComposerPane
import app.andy.ui.agents.AgentTaskDetail
import app.andy.ui.agents.ChatSessionSidebarRow
import app.andy.ui.agents.isSessionWorking
import app.andy.ui.agents.TranscriptScrollMemory
import app.andy.ui.agents.UnreadDot
import app.andy.ui.components.StatusTag
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

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
private data class ProjectChatLists(val active: List<AgentTask>, val archived: List<AgentTask>)

private val ProjectChatSort =
    compareByDescending<AgentTask> { it.createdAtMillis }

private enum class ProjectCanvas(val label: String) { Chat("chat"), Tasks("tasks"), Runbook("runbook"), Scratchpad("scratchpad") }

private const val RecentSessionsPerProject = 5

@Composable
private fun ProjectCockpit(
    services: AndyServices,
    config: ActionsConfig,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    preferredProjectId: String?,
    onPreferredProjectChange: (String) -> Unit,
    workspaceReady: Boolean,
    initialWorkflowTaskId: String?,
    initialCanvasLabel: String?,
    requestedAgentTaskId: String?,
    requestedProjectId: String?,
    onRequestedAgentTaskConsumed: () -> Unit,
    onNotifyTerminalRun: (String) -> Unit,
    active: Boolean,
    workspaceState: WorkspaceState,
) {
    val scope = rememberCoroutineScope()
    val agentCliStatuses by services.agentRuns.cliStatuses.collectAsState()
    val workflowProjects by services.projectWorkflows.projects.collectAsState()
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedWorkflowTaskId by remember { mutableStateOf<String?>(null) }
    var initialWorkflowSelectionApplied by remember { mutableStateOf(false) }
    var canvas by remember { mutableStateOf(ProjectCanvas.entries.firstOrNull { it.label == initialCanvasLabel } ?: ProjectCanvas.Chat) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var specEditorOpen by remember { mutableStateOf(false) }
    var editingSpec by remember { mutableStateOf<ProjectTask?>(null) }
    var buildEditor by remember { mutableStateOf<BuildEditorSeed?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    val transcriptScrollMemory = remember { TranscriptScrollMemory() }
    var expandedActionId by remember { mutableStateOf<String?>(null) }
    var expandedProjectSessionsId by remember { mutableStateOf<String?>(null) }
    var viewingArchivedForProjectId by remember { mutableStateOf<String?>(null) }
    var collapsedProjectIds by remember { mutableStateOf(setOf<String>()) }
    val project = config.projects.firstOrNull { it.id == selectedProjectId }
    val loadedProjectWorkflow = project?.let { workflowProjects[it.id] }
    val effectiveProjectWorkflow = project?.let { loadedProjectWorkflow ?: ProjectWorkflowState(it.id) }

    fun selectProject(projectId: String, rememberAsPreferred: Boolean = true) {
        selectedProjectId = projectId
        if (rememberAsPreferred) onPreferredProjectChange(projectId)
    }

    fun ensureWorkflowProjectLoaded() {
        project?.id?.let { projectId -> scope.launch { services.projectWorkflows.ensureProject(projectId) } }
    }

    fun requestDeleteChat(task: AgentTask) {
        pendingConfirmation = PendingConfirmation(
            title = "Delete chat?",
            message = "Permanently removes \"${task.title}\" and its saved transcript.",
            confirmLabel = "Delete",
        ) {
            scope.launch {
                transcriptScrollMemory.remove(task.id)
                services.agentRuns.delete(task.id, task.ownsWorktree)
                if (selectedTaskId == task.id) selectedTaskId = null
            }
        }
    }

    LaunchedEffect(workspaceReady, config.projects, preferredProjectId) {
        if (!workspaceReady) return@LaunchedEffect
        val projectIds = config.projects.map { it.id }
        if (selectedProjectId !in projectIds) {
            selectedProjectId = preferredProjectId?.takeIf { it in projectIds }
                ?: config.projects.firstOrNull()?.id
        }
    }
    LaunchedEffect(requestedAgentTaskId, requestedProjectId, agentTasks) {
        val taskId = requestedAgentTaskId ?: return@LaunchedEffect
        val task = agentTasks.firstOrNull { it.id == taskId && it.projectId == requestedProjectId }
        if (task != null) {
            task.projectId?.let { selectProject(it) }
            selectedTaskId = task.id
            if (task.archived) viewingArchivedForProjectId = task.projectId
            canvas = ProjectCanvas.Chat
            services.agentRuns.setChatViewing(task.id, viewing = true)
        }
        onRequestedAgentTaskConsumed()
    }
    LaunchedEffect(selectedProjectId) {
        val projectId = selectedProjectId
        if (projectId != null) {
            services.projectWorkflows.ensureProject(projectId)
            if (initialWorkflowSelectionApplied || initialWorkflowTaskId == null) {
                selectedWorkflowTaskId = null
            }
        }
    }
    LaunchedEffect(selectedProjectId, loadedProjectWorkflow?.tasks, initialWorkflowTaskId) {
        val initialTaskId = initialWorkflowTaskId ?: return@LaunchedEffect
        if (!initialWorkflowSelectionApplied && loadedProjectWorkflow?.tasks?.any { it.id == initialTaskId } == true) {
            selectedWorkflowTaskId = initialTaskId
            canvas = ProjectCanvas.Tasks
            initialWorkflowSelectionApplied = true
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = currentTimeMillis() } }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }

    val searchActive = query.isNotBlank()
    val unreadProjectIds = remember(agentTasks, config.projects) {
        val validProjectIds = config.projects.mapTo(mutableSetOf()) { it.id }
        agentTasks.mapNotNullTo(mutableSetOf()) { task ->
            task.projectId?.takeIf { task.unread && !task.archived && task.workflowTaskId == null && it in validProjectIds }
        }
    }
    val projectChatLists = remember(agentTasks) {
        agentTasks
            .asSequence()
            .filter { it.workflowTaskId == null && it.projectId != null }
            .groupBy { it.projectId!! }
            .mapValues { (_, tasks) ->
                ProjectChatLists(
                    active = tasks.filter { !it.archived }.sortedWith(ProjectChatSort),
                    archived = tasks.filter { it.archived }.sortedByDescending { it.createdAtMillis },
                )
            }
    }
    val sidebarEntries = remember(config.projects, query, projectChatLists) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            config.projects.map { ProjectSidebarEntry(it) }
        } else {
            config.projects.mapNotNull { project ->
                val chats = projectChatLists[project.id] ?: ProjectChatLists(emptyList(), emptyList())
                val matchingSessions = (chats.active + chats.archived)
                    .filter { projectSidebarTaskMatches(trimmed, it) }
                    .distinctBy { it.id }
                when {
                    !projectSidebarProjectMatches(trimmed, project) && matchingSessions.isEmpty() -> null
                    matchingSessions.isNotEmpty() -> ProjectSidebarEntry(project, matchingSessions)
                    else -> ProjectSidebarEntry(project)
                }
            }
        }
    }
    val projectTasks = project?.let { item ->
        agentTasks
            .filter { it.projectId == item.id && !it.archived }
            .sortedByDescending { it.createdAtMillis }
    }.orEmpty()
    val selectedProjectTask = project?.let { item ->
        agentTasks.firstOrNull { it.id == selectedTaskId && it.projectId == item.id }
    }
    // Open chats stay read — including while a live run is on screen.
    // Only while Projects is the active destination: RetainedDestination keeps this
    // screen composed off-page, and clearing unread there would hide the badge.
    DisposableEffect(active, selectedProjectTask?.id, canvas) {
        val taskId = selectedProjectTask?.id?.takeIf { active && canvas == ProjectCanvas.Chat }
        if (taskId != null) {
            services.agentRuns.setChatViewing(taskId, viewing = true)
        }
        onDispose {
            if (taskId != null) services.agentRuns.setChatViewing(taskId, viewing = false)
        }
    }
    LaunchedEffect(loadedProjectWorkflow?.tasks, selectedWorkflowTaskId) {
        if (selectedWorkflowTaskId != null && loadedProjectWorkflow != null && loadedProjectWorkflow.tasks.none { it.id == selectedWorkflowTaskId }) {
            selectedWorkflowTaskId = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railWidth = AndyLayout.ListWidth
        val chatMinWidth = 520.dp

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                WorkspaceRail(Modifier.width(railWidth).fillMaxHeight()) {
                    ProjectsSidebarHeader(
                        query = query,
                        onQueryChange = { query = it },
                        searchExpanded = searchExpanded,
                        onSearchExpandedChange = { expanded ->
                            searchExpanded = expanded
                            if (!expanded) query = ""
                        },
                        searchFocusRequester = searchFocusRequester,
                        onNew = { editingProject = EditingProject(null) },
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (sidebarEntries.isEmpty()) {
                            item {
                                EmptyState(
                                    if (query.isBlank()) "Create a project to start" else "No projects or chats match your search",
                                )
                            }
                        }
                        items(sidebarEntries, key = { entry -> entry.project.id }) { entry ->
                            val item = entry.project
                            val chatLists = projectChatLists[item.id] ?: ProjectChatLists(emptyList(), emptyList())
                            val sessions = chatLists.active
                            val archivedSessions = chatLists.archived
                            val viewingArchived = viewingArchivedForProjectId == item.id
                            val sessionsCollapsed = !searchActive && item.id in collapsedProjectIds
                            ProjectSessionGroup(
                                project = item,
                                nowMillis = nowMillis,
                                hasUnread = item.id in unreadProjectIds,
                                sessions = when {
                                    entry.searchSessions != null -> entry.searchSessions
                                    sessionsCollapsed -> emptyList()
                                    viewingArchived -> archivedSessions
                                    expandedProjectSessionsId == item.id -> sessions
                                    else -> sessions.take(RecentSessionsPerProject)
                                },
                                selectedSessionId = selectedTaskId,
                                sessionsCollapsed = sessionsCollapsed,
                                viewingArchived = viewingArchived && entry.searchSessions == null,
                                archivedCount = archivedSessions.size,
                                showMore = !searchActive && !sessionsCollapsed && !viewingArchived &&
                                    sessions.size > RecentSessionsPerProject &&
                                    expandedProjectSessionsId != item.id,
                                onToggleProject = {
                                    if (item.id == selectedProjectId) {
                                        collapsedProjectIds = if (sessionsCollapsed) {
                                            collapsedProjectIds - item.id
                                        } else {
                                            collapsedProjectIds + item.id
                                        }
                                    } else {
                                        collapsedProjectIds = collapsedProjectIds - item.id
                                        selectProject(item.id)
                                        selectedWorkflowTaskId = null
                                        canvas = ProjectCanvas.Chat
                                    }
                                },
                                onOpenSession = { task ->
                                    collapsedProjectIds = collapsedProjectIds - item.id
                                    selectProject(item.id)
                                    selectedTaskId = task.id
                                    canvas = ProjectCanvas.Chat
                                    services.agentRuns.setChatViewing(task.id, viewing = true)
                                },
                                onMarkSessionUnread = { task -> services.agentRuns.markUnread(task.id) },
                                onArchiveSession = { task ->
                                    services.agentRuns.archive(task.id)
                                    if (selectedTaskId == task.id) selectedTaskId = null
                                },
                                onUnarchiveSession = { task -> services.agentRuns.unarchive(task.id) },
                                onDeleteSession = ::requestDeleteChat,
                                onShowMore = { expandedProjectSessionsId = item.id },
                                onToggleArchived = {
                                    viewingArchivedForProjectId = if (viewingArchived) null else item.id
                                    expandedProjectSessionsId = null
                                },
                                onNewChat = {
                                    collapsedProjectIds = collapsedProjectIds - item.id
                                    viewingArchivedForProjectId = null
                                    selectProject(item.id)
                                    selectedTaskId = null
                                    selectedWorkflowTaskId = null
                                    canvas = ProjectCanvas.Chat
                                },
                                onEditProject = { editingProject = EditingProject(item) },
                            )
                        }
                    }
                    ProjectsSidebarFooter()
                }
                val current = project
                if (current == null) {
                    WorkspaceCanvas(Modifier.weight(1f).fillMaxHeight()) {
                        WorkspaceEmptyCanvas("Create a project to start")
                    }
                } else {
                    WorkspaceCanvas(Modifier.widthIn(min = chatMinWidth).weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProjectChatToolbar(
                            project = current,
                            canvas = canvas,
                            onCanvasChange = {
                                canvas = it
                                if (it != ProjectCanvas.Tasks) selectedWorkflowTaskId = null
                            },
                        )
                        Box(
                            Modifier.fillMaxSize().testTag(if (canvas == ProjectCanvas.Chat) "project-chat-pane" else "project-task-dock"),
                        ) {
                            when (canvas) {
                                ProjectCanvas.Chat -> {
                                    val selected = agentTasks.firstOrNull { it.id == selectedTaskId && it.projectId == current.id }
                                    Box(Modifier.fillMaxSize()) {
                                        // Keep the composer mounted so draft text/images survive opening a transcript.
                                        RetainedDestination(active = selected == null) {
                                            AgentTaskComposerPane(
                                                services,
                                                agentCliStatuses,
                                                current,
                                                onSubmit = { draft -> scope.launch { selectedTaskId = services.agentRuns.createAndStart(draft).id } },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        if (selected != null) {
                                            AgentTaskDetail(
                                                services,
                                                selected,
                                                onDelete = ::requestDeleteChat,
                                                transcriptScrollMemory = transcriptScrollMemory,
                                                workspaceState = workspaceState,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                                ProjectCanvas.Tasks -> {
                                    val workflow = effectiveProjectWorkflow ?: ProjectWorkflowState(current.id)
                                    Row(
                                        Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ProjectWorkflowList(
                                            workflow = workflow,
                                            selectedTaskId = selectedWorkflowTaskId,
                                            onSelectTask = { selectedWorkflowTaskId = it },
                                            onNewSpec = {
                                                ensureWorkflowProjectLoaded()
                                                editingSpec = null
                                                specEditorOpen = true
                                            },
                                            onNewBuild = {
                                                ensureWorkflowProjectLoaded()
                                                buildEditor = BuildEditorSeed()
                                            },
                                            onProfiles = {
                                                ensureWorkflowProjectLoaded()
                                                profilesOpen = true
                                            },
                                            modifier = Modifier.width(360.dp).fillMaxHeight(),
                                        )
                                        PaneDivider(onDrag = {})
                                        ProjectWorkflowDetail(
                                            services = services,
                                            workflow = workflow,
                                            task = workflow.tasks.firstOrNull { it.id == selectedWorkflowTaskId },
                                            agentTasks = projectTasks,
                                            onNewBuildFromPlan = { buildEditor = BuildEditorSeed(plan = it) },
                                            onOpenRun = { runId ->
                                                selectedTaskId = runId
                                                canvas = ProjectCanvas.Chat
                                                services.agentRuns.setChatViewing(runId, viewing = true)
                                            },
                                            onEdit = { task ->
                                                ensureWorkflowProjectLoaded()
                                                if (task.kind == ProjectTaskKind.Spec) {
                                                    editingSpec = task
                                                    specEditorOpen = true
                                                } else {
                                                    buildEditor = BuildEditorSeed(buildTaskId = task.linkedBuildTaskId ?: task.id)
                                                }
                                            },
                                            onDelete = { task ->
                                                val hasChildren = task.kind == ProjectTaskKind.Spec && workflow.tasks.any { it.linkedSpecTaskId == task.id }
                                                pendingConfirmation = PendingConfirmation(
                                                    title = "Delete ${task.kind.label}?",
                                                    message = if (hasChildren) "This Spec has Build/Review/Verification children. Delete the entire workflow?" else "Deletes this Build workflow and its linked Review/Verification records. Run history and worktrees remain unless removed separately.",
                                                    confirmLabel = "Delete",
                                                ) { scope.launch { services.projectWorkflows.deleteTask(task.id, cascade = hasChildren); selectedWorkflowTaskId = null } }
                                            },
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                        )
                                    }
                                }
                                ProjectCanvas.Runbook -> ProjectRunbook(
                                    project = current,
                                    expandedActionId = expandedActionId,
                                    onExpandedActionChange = { expandedActionId = it },
                                    onEditAction = { editingAction = EditingAction(current.id, it) },
                                    onNewAction = { editingAction = EditingAction(current.id, null) },
                                    onRunAction = { action ->
                                        val runId = services.actionRuns.run(current, action)
                                        onNotifyTerminalRun(runId)
                                    },
                                )
                                ProjectCanvas.Scratchpad -> ProjectScratchpadEditor(
                                    services = services,
                                    projectId = current.id,
                                    persistedText = effectiveProjectWorkflow?.scratchpad.orEmpty(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    editingProject?.let { edit ->
        ProjectDialog(
            project = edit.project,
            existingProjects = config.projects,
            onDismiss = { editingProject = null },
            onDelete = edit.project?.takeIf { it.source != ConfigSource.Repo }?.let { project ->
                {
                    editingProject = null
                    pendingConfirmation = PendingConfirmation(
                        title = "Delete workspace?",
                        message = "Removes \"${project.name}\", its workflows, scratchpad, actions, and agent sessions from Andy.",
                        confirmLabel = "Delete",
                    ) {
                        val sessions = agentTasks.filter { it.projectId == project.id }
                        scope.launch {
                            services.projectWorkflows.deleteProject(project.id)
                            sessions.forEach { task ->
                                runCatching {
                                    services.agentRuns.delete(task.id, task.ownsWorktree)
                                }
                            }
                            if (selectedProjectId == project.id) {
                                selectedProjectId = null
                                selectedTaskId = null
                            }
                            onConfigChange(config.copy(projects = config.projects.filterNot { it.id == project.id }))
                        }
                    }
                }
            },
        ) { updated ->
            editingProject = null
            onConfigChange(
                config.copy(
                    projects = if (edit.project == null) {
                        config.projects + updated
                    } else {
                        config.projects.map { if (it.id == updated.id) updated else it }
                    },
                ),
            )
        }
    }
    editingAction?.let { edit -> ActionDialog(config.projects, edit.projectId, edit.action, { editingAction = null }) { projectId, action -> editingAction = null; onConfigChange(config.copy(projects = config.projects.map { project -> if (project.id == projectId) project.copy(actions = project.actions.filterNot { it.id == action.id } + action) else project })) } }
    if (specEditorOpen && project != null && effectiveProjectWorkflow != null) {
        SpecTaskDialog(services, project, effectiveProjectWorkflow, editingSpec, agentCliStatuses, onDismiss = { specEditorOpen = false }) { id ->
            specEditorOpen = false
            selectedWorkflowTaskId = id
        }
    }
    buildEditor?.let { seed ->
        if (project != null && effectiveProjectWorkflow != null) {
            BuildPairDialog(services, project, effectiveProjectWorkflow, seed, agentCliStatuses, onDismiss = { buildEditor = null }) { id ->
                buildEditor = null
                selectedWorkflowTaskId = id
            }
        }
    }
    if (profilesOpen && effectiveProjectWorkflow != null) {
        ProjectProfilesDialog(services, effectiveProjectWorkflow, agentCliStatuses) { profilesOpen = false }
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { pendingConfirmation = null },
            onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            },
        )
    }
}

@Composable
internal fun ActionsScreen(
    services: AndyServices,
    config: ActionsConfig,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    showIntroduction: Boolean = false,
    onIntroductionComplete: () -> Unit = {},
    preferredProjectId: String? = null,
    onPreferredProjectChange: (String) -> Unit = {},
    workspaceReady: Boolean = true,
    active: Boolean = true,
    initialWorkflowTaskId: String? = null,
    initialCanvasLabel: String? = null,
    requestedAgentTaskId: String? = null,
    requestedProjectId: String? = null,
    onRequestedAgentTaskConsumed: () -> Unit = {},
    onNotifyTerminalRun: (String) -> Unit = {},
    workspaceState: WorkspaceState = WorkspaceState(),
) {
    if (showIntroduction) {
        ProjectsIntroduction(onComplete = onIntroductionComplete)
    } else {
        var pageTab by remember { mutableStateOf(ProjectsPageTab.Projects) }
        Column(Modifier.fillMaxSize()) {
            TabBar(
                tabs = ProjectsPageTab.entries,
                selected = pageTab,
                onSelect = { pageTab = it },
                label = { it.label },
            )
            when (pageTab) {
                ProjectsPageTab.Projects -> ProjectCockpit(
                    services = services,
                    config = config,
                    onConfigChange = onConfigChange,
                    agentTasks = agentTasks,
                    preferredProjectId = preferredProjectId,
                    onPreferredProjectChange = onPreferredProjectChange,
                    workspaceReady = workspaceReady,
                    initialWorkflowTaskId = initialWorkflowTaskId,
                    initialCanvasLabel = initialCanvasLabel,
                    requestedAgentTaskId = requestedAgentTaskId,
                    requestedProjectId = requestedProjectId,
                    onRequestedAgentTaskConsumed = onRequestedAgentTaskConsumed,
                    onNotifyTerminalRun = onNotifyTerminalRun,
                    active = active,
                    workspaceState = workspaceState,
                )
                ProjectsPageTab.Kanban -> KanbanBoardScreen(services = services)
            }
        }
    }
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
private fun ProjectsSidebarHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    onNew: () -> Unit,
) {
    val searchVisible = searchExpanded || query.isNotBlank()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Projects",
                color = TextSecondary.copy(alpha = 0.85f),
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchGlyphButton(
                    active = searchVisible,
                    onClick = {
                        if (searchVisible && query.isBlank()) {
                            onSearchExpandedChange(false)
                        } else {
                            onSearchExpandedChange(true)
                        }
                    },
                )
                PlusGlyphButton(onClick = onNew)
            }
        }
        AnimatedVisibility(
            visible = searchVisible,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            TextField(
                query,
                onQueryChange,
                Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
                singleLine = true,
                placeholder = {
                    Text(
                        "Search",
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 12.sp,
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                ),
                colors = fieldColors(),
            )
        }
    }
}

@Composable
private fun SearchGlyphButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tint = when {
        active -> TextPrimary.copy(alpha = 0.9f)
        hovered -> TextSecondary.copy(alpha = 0.9f)
        else -> TextSecondary.copy(alpha = 0.62f)
    }
    HeaderGlyphButton(onClick = onClick, interactionSource = interactionSource, contentDescription = "Search projects") {
        SearchGlyph(color = tint)
    }
}

@Composable
private fun PlusGlyphButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tint = when {
        hovered -> TextPrimary.copy(alpha = 0.9f)
        else -> TextSecondary.copy(alpha = 0.62f)
    }
    HeaderGlyphButton(onClick = onClick, interactionSource = interactionSource, contentDescription = "New project") {
        PlusGlyph(color = tint)
    }
}

@Composable
private fun HeaderGlyphButton(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SearchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round)
        val radius = size.minDimension * 0.28f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = radius, center = center, style = stroke)
        drawLine(
            color,
            Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            Offset(size.width * 0.82f, size.height * 0.82f),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val arm = size.minDimension * 0.24f
        drawLine(
            color,
            Offset(center.x - arm, center.y),
            Offset(center.x + arm, center.y),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(center.x, center.y - arm),
            Offset(center.x, center.y + arm),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ProjectFolderGlyph(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val fillColor = AndyColors.SurfaceHover.copy(alpha = 0.85f)
    val strokeColor = TextSecondary.copy(alpha = 0.78f)
    val stroke = remember(strokeColor) {
        Stroke(width = 1.15f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    }
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        if (!expanded) {
            val body = Path().apply {
                moveTo(w * 0.506f, h * 0.272f)
                lineTo(w * 0.558f, h * 0.281f)
                lineTo(w * 0.592f, h * 0.281f)
                lineTo(w * 0.812f, h * 0.281f)
                cubicTo(w * 0.891f, h * 0.281f, w * 0.906f, h * 0.323f, w * 0.906f, h * 0.375f)
                lineTo(w * 0.906f, h * 0.758f)
                cubicTo(w * 0.906f, h * 0.809f, w * 0.871f, h * 0.844f, w * 0.848f, h * 0.844f)
                lineTo(w * 0.165f, h * 0.844f)
                cubicTo(w * 0.129f, h * 0.844f, w * 0.094f, h * 0.809f, w * 0.094f, h * 0.758f)
                lineTo(w * 0.094f, h * 0.242f)
                cubicTo(w * 0.094f, h * 0.191f, w * 0.129f, h * 0.156f, w * 0.165f, h * 0.156f)
                lineTo(w * 0.383f, h * 0.156f)
                cubicTo(w * 0.411f, h * 0.156f, w * 0.438f, h * 0.166f, w * 0.457f, h * 0.191f)
                close()
            }
            drawPath(body, fillColor)
            drawPath(body, strokeColor, style = stroke)
        } else {
            val back = Path().apply {
                moveTo(w * 0.094f, h * 0.313f)
                lineTo(w * 0.165f, h * 0.188f)
                lineTo(w * 0.383f, h * 0.188f)
                cubicTo(w * 0.411f, h * 0.188f, w * 0.438f, h * 0.198f, w * 0.457f, h * 0.223f)
                lineTo(w * 0.506f, h * 0.304f)
                lineTo(w * 0.812f, h * 0.304f)
                cubicTo(w * 0.891f, h * 0.304f, w * 0.906f, h * 0.346f, w * 0.906f, h * 0.398f)
                lineTo(w * 0.906f, h * 0.844f)
                lineTo(w * 0.094f, h * 0.844f)
                close()
            }
            val flap = Path().apply {
                moveTo(w * 0.094f, h * 0.313f)
                lineTo(w * 0.906f, h * 0.313f)
                lineTo(w * 0.906f, h * 0.844f)
                lineTo(w * 0.094f, h * 0.844f)
                close()
            }
            drawPath(back, fillColor)
            drawPath(back, strokeColor, style = stroke)
            drawPath(flap, strokeColor, style = stroke)
            drawLine(
                strokeColor,
                Offset(w * 0.094f, h * 0.313f),
                Offset(w * 0.906f, h * 0.313f),
                strokeWidth = 1.15f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private data class ProjectSidebarEntry(
    val project: ActionProject,
    val searchSessions: List<AgentTask>? = null,
)

private fun projectSidebarProjectMatches(query: String, project: ActionProject): Boolean {
    if (project.name.contains(query, ignoreCase = true)) return true
    if (project.contextDir.contains(query, ignoreCase = true)) return true
    if (project.actions.any { action ->
            action.name.contains(query, ignoreCase = true) || action.command.contains(query, ignoreCase = true)
        }) {
        return true
    }
    if (project.notes.any { note ->
            note.title.contains(query, ignoreCase = true) || note.body.contains(query, ignoreCase = true)
        }) {
        return true
    }
    return false
}

private fun projectSidebarTaskMatches(query: String, task: AgentTask): Boolean {
    val fields = buildList {
        add(task.title)
        add(task.prompt)
        task.latestPrompt?.let(::add)
        task.goal?.let(::add)
        task.completedResultText?.let(::add)
        task.continuationPrompt?.let(::add)
        task.completedPlanText?.let(::add)
        task.branchName?.let(::add)
        task.errorMessage?.let(::add)
        task.model?.let(::add)
        addAll(task.queuedFollowUps.map { it.text })
        addAll(task.skills.map { it.name })
        addAll(task.skills.mapNotNull { it.description.takeIf(String::isNotBlank) })
    }
    return fields.any { it.contains(query, ignoreCase = true) }
}

private val ProjectSidebarViolet = Color(0xFF8B5CF6)

@Composable
private fun WorktreeBranchGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.72f, size.height * 0.18f)
            lineTo(size.width * 0.72f, size.height * 0.82f)
            moveTo(size.width * 0.72f, size.height * 0.42f)
            cubicTo(
                size.width * 0.72f, size.height * 0.22f,
                size.width * 0.28f, size.height * 0.22f,
                size.width * 0.28f, size.height * 0.42f,
            )
            cubicTo(
                size.width * 0.28f, size.height * 0.62f,
                size.width * 0.72f, size.height * 0.62f,
                size.width * 0.72f, size.height * 0.82f,
            )
        }
        drawPath(path, ProjectSidebarViolet, style = stroke)
        drawCircle(ProjectSidebarViolet, radius = 2.2f, center = Offset(size.width * 0.72f, size.height * 0.18f))
        drawCircle(ProjectSidebarViolet, radius = 2.2f, center = Offset(size.width * 0.28f, size.height * 0.42f))
        drawCircle(ProjectSidebarViolet, radius = 2.2f, center = Offset(size.width * 0.72f, size.height * 0.82f))
    }
}

@Composable
private fun ProjectsSidebarFooter() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Local only",
            color = TextSecondary.copy(alpha = 0.48f),
            fontFamily = DisplayFont,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ProjectSessionGroup(
    project: ActionProject,
    nowMillis: Long,
    hasUnread: Boolean,
    sessions: List<AgentTask>,
    selectedSessionId: String?,
    sessionsCollapsed: Boolean,
    viewingArchived: Boolean,
    archivedCount: Int,
    showMore: Boolean,
    onToggleProject: () -> Unit,
    onOpenSession: (AgentTask) -> Unit,
    onMarkSessionUnread: (AgentTask) -> Unit,
    onArchiveSession: (AgentTask) -> Unit,
    onUnarchiveSession: (AgentTask) -> Unit,
    onDeleteSession: (AgentTask) -> Unit,
    onShowMore: () -> Unit,
    onToggleArchived: () -> Unit,
    onNewChat: () -> Unit,
    onEditProject: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                .clickable(onClick = onToggleProject)
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectFolderGlyph(expanded = !sessionsCollapsed)
            Text(
                project.name,
                color = TextPrimary.copy(alpha = 0.92f),
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hovered) {
                    if (project.source != ConfigSource.Repo) {
                        Text(
                            "edit",
                            color = TextSecondary.copy(alpha = 0.72f),
                            fontFamily = DisplayFont,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable(onClick = onEditProject),
                        )
                    }
                    NewProjectChatButton(onClick = onNewChat, size = 13.dp)
                }
                if (hasUnread) UnreadDot()
            }
        }
        AnimatedVisibility(
            visible = !sessionsCollapsed,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            Column(
                Modifier.padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (viewingArchived) {
                    Text(
                        "ARCHIVED",
                        color = TextSecondary.copy(alpha = 0.55f),
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                }
                sessions.forEach { task ->
                    ProjectSessionRow(
                        task = task,
                        selected = task.id == selectedSessionId,
                        nowMillis = nowMillis,
                        onOpen = { onOpenSession(task) },
                        onMarkUnread = { onMarkSessionUnread(task) },
                        onArchive = {
                            if (viewingArchived) onUnarchiveSession(task) else onArchiveSession(task)
                        },
                        archiveLabel = if (viewingArchived) "Unarchive" else "Archive",
                        onDelete = { onDeleteSession(task) },
                    )
                }
                if (showMore) {
                    Text(
                        "Show more",
                        color = TextSecondary.copy(alpha = 0.55f),
                        fontFamily = DisplayFont,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onShowMore)
                            .padding(vertical = 4.dp),
                    )
                }
                if (archivedCount > 0 || viewingArchived) {
                    Text(
                        if (viewingArchived) "Back to chats" else "Archived ($archivedCount)",
                        color = TextSecondary.copy(alpha = 0.55f),
                        fontFamily = DisplayFont,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onToggleArchived)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectSessionRow(
    task: AgentTask,
    selected: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit,
    archiveLabel: String = "Archive",
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ChatSessionSidebarRow(
            task = task,
            selected = selected,
            nowMillis = nowMillis,
            onClick = onOpen,
            modifier = Modifier.pointerInput(task.id) {
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
            },
            trailing = when {
                task.status == AgentStatus.Blocked -> {
                    { StatusTag("blocked", Red) }
                }
                task.worktreePath != null || task.branchName != null -> {
                    { WorktreeBranchGlyph() }
                }
                else -> null
            },
        )
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
            DropdownMenuItem(
                text = { Text(archiveLabel, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onArchive()
                },
                enabled = archiveLabel == "Unarchive" || !task.isActive,
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Red, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ProjectChatToolbar(
    project: ActionProject,
    canvas: ProjectCanvas,
    onCanvasChange: (ProjectCanvas) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                project.name,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                project.contextDir,
                color = TextSecondary.copy(alpha = 0.78f),
                fontFamily = MonoFont,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectCanvas.entries.filter { it != ProjectCanvas.Chat }.forEach { tab ->
                FilterPill(tab.label, canvas == tab, if (tab == ProjectCanvas.Runbook) Rust else Cyan) { onCanvasChange(tab) }
            }
        }
    }
}

@Composable
private fun NewProjectChatButton(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 19.dp) {
    Image(
        painter = painterResource(Res.drawable.project_new_chat),
        contentDescription = "Start new chat",
        colorFilter = ColorFilter.tint(Cyan),
        modifier = Modifier
            .size(size)
            .semantics { role = Role.Button }
            .testTag("project-new-chat")
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ProjectRunbook(
    project: ActionProject,
    expandedActionId: String?,
    onExpandedActionChange: (String?) -> Unit,
    onEditAction: (ProjectAction) -> Unit,
    onNewAction: () -> Unit,
    onRunAction: (ProjectAction) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Runbook", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Button(onClick = onNewAction) { Text("Add action") }
        }
        if (project.actions.isEmpty() && project.notes.isEmpty()) {
            EmptyState("Add the commands you use most")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (project.actions.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(project.actions, key = { it.id }) { action ->
                        val expanded = expandedActionId == action.id
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (expanded) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.Control))
                                .border(1.dp, if (expanded) AndyColors.OrangeBorder.copy(alpha = 0.58f) else Border, RoundedCornerShape(AndyRadius.Control))
                                .clickable { onExpandedActionChange(if (expanded) null else action.id) }
                                .animateContentSize(animationSpec = tween(220))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(actionIconMarker(action.icon), color = Rust, fontFamily = MonoFont)
                                Text(action.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (action.source == ConfigSource.Repo) {
                                    RepoSourceBadge()
                                    OutlinedButton(onClick = {}, enabled = false) { Text("Edit") }
                                } else {
                                    OutlinedButton(onClick = { onEditAction(action) }) { Text("Edit") }
                                }
                                Button(onClick = { onRunAction(action) }) { Text("Run") }
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
                            ) {
                                Text(action.command, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (project.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notes", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    project.notes.forEach { note ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.Control))
                                .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = note.completed,
                                onCheckedChange = null,
                                enabled = note.source != ConfigSource.Repo,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(note.title, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    if (note.source == ConfigSource.Repo) {
                                        RepoSourceBadge()
                                    }
                                }
                                if (note.body.isNotBlank()) {
                                    Text(note.body, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ProjectDialog(
    project: ActionProject?,
    existingProjects: List<ActionProject>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (ActionProject) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var contextDir by remember(project?.id) { mutableStateOf(project?.contextDir.orEmpty()) }
    var envText by remember(project?.id) { mutableStateOf(project?.env?.toEnvText().orEmpty()) }
    val nextId = remember(existingProjects.size, name) { nextActionId("proj", name, existingProjects.map { it.id }.toSet()) }
    AndyAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (project == null) "New project" else "Edit project", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Context directory", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(contextDir, { contextDir = it }, readOnly = true, singleLine = true, modifier = Modifier.weight(1f).defaultMinSize(minHeight = AndyLayout.FieldHeight), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont), colors = fieldColors())
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
                enabled = project?.source != ConfigSource.Repo && name.isNotBlank() && contextDir.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null && project?.source != ConfigSource.Repo) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    ) { Text("Delete") }
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
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
    AndyAlertDialog(
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
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = AndyLayout.FieldHeight),
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

@Composable
private fun RepoSourceBadge() {
    Surface(
        color = AndyColors.Neutral800,
        shape = RoundedCornerShape(AndyRadius.Control),
        border = BorderStroke(1.dp, AndyColors.Neutral600),
    ) {
        Text(
            "from repo",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
