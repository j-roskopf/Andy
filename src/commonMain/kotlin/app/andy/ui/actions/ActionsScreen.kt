package app.andy.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.drawscope.Stroke
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.project_new_chat
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PendingConfirmation
import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ActionsConfig
import app.andy.model.AgentTask
import app.andy.model.ProjectAction
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectWorkflowState
import app.andy.model.RunningAction
import app.andy.pickDirectory
import app.andy.service.AndyServices
import app.andy.currentTimeMillis
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.agents.AgentTaskComposerPane
import app.andy.ui.agents.AgentTaskDetail
import app.andy.ui.agents.ProjectActivityIndicator
import app.andy.ui.agents.UnreadDot
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

private enum class ProjectCanvas(val label: String) { Chat("chat"), Tasks("tasks"), Runbook("runbook"), Scratchpad("scratchpad") }
private enum class TerminalPlacement { Right, Bottom }

private const val RecentSessionsPerProject = 5

@Composable
private fun ProjectCockpit(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    terminalRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    initialWorkflowTaskId: String?,
    initialCanvasLabel: String?,
    requestedAgentTaskId: String?,
    requestedProjectId: String?,
    onRequestedAgentTaskConsumed: () -> Unit,
    compactToolCalls: Boolean,
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
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var specEditorOpen by remember { mutableStateOf(false) }
    var editingSpec by remember { mutableStateOf<ProjectTask?>(null) }
    var buildEditor by remember { mutableStateOf<BuildEditorSeed?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    var expandedActionId by remember { mutableStateOf<String?>(null) }
    var expandedProjectSessionsId by remember { mutableStateOf<String?>(null) }
    var terminalPlacement by remember { mutableStateOf<TerminalPlacement?>(null) }
    var lastTerminalPlacement by remember { mutableStateOf(TerminalPlacement.Right) }
    var terminalTabIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var handledTerminalRunId by remember { mutableStateOf<String?>(null) }
    val project = config.projects.firstOrNull { it.id == selectedProjectId }
    val loadedProjectWorkflow = project?.let { workflowProjects[it.id] }
    val projectWorkflow = loadedProjectWorkflow

    fun selectTerminalTab(runId: String) {
        if (runId !in terminalTabIds) terminalTabIds = terminalTabIds + runId
        onActiveRunIdChange(runId)
    }

    fun closeTerminalTab(runId: String) {
        val remaining = terminalTabIds.filter { it != runId }
        terminalTabIds = remaining
        if (activeRunId == runId) onActiveRunIdChange(remaining.lastOrNull())
        if (remaining.isEmpty()) terminalPlacement = null
    }

    LaunchedEffect(config.projects) {
        if (selectedProjectId !in config.projects.map { it.id }) selectedProjectId = config.projects.firstOrNull()?.id
    }
    LaunchedEffect(requestedAgentTaskId, requestedProjectId, agentTasks) {
        val taskId = requestedAgentTaskId ?: return@LaunchedEffect
        val task = agentTasks.firstOrNull { it.id == taskId && it.projectId == requestedProjectId }
        if (task != null) {
            selectedProjectId = task.projectId
            selectedTaskId = task.id
            canvas = ProjectCanvas.Chat
            services.agentRuns.markRead(task.id)
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
    LaunchedEffect(terminalRunId, running) {
        val runId = terminalRunId ?: return@LaunchedEffect
        if (runId == handledTerminalRunId) return@LaunchedEffect
        val terminalRun = running.firstOrNull { it.runId == runId } ?: return@LaunchedEffect
        selectedProjectId = terminalRun.projectId
        selectedTaskId = null
        selectTerminalTab(runId)
        terminalPlacement = lastTerminalPlacement
        handledTerminalRunId = runId
    }
    LaunchedEffect(running) {
        terminalTabIds = terminalTabIds.filter { tabId -> running.any { it.runId == tabId } }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = currentTimeMillis() } }

    val projects = remember(config.projects, query) {
        config.projects.filter { project ->
            query.isBlank() || project.name.contains(query, true) || project.contextDir.contains(query, true)
        }
    }
    val unreadProjectIds = remember(agentTasks) {
        agentTasks.mapNotNullTo(mutableSetOf()) { task ->
            task.projectId?.takeIf { task.unread }
        }
    }
    val projectTasks = project?.let { item ->
        agentTasks
            .filter { it.projectId == item.id }
            .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
    }.orEmpty()
    LaunchedEffect(loadedProjectWorkflow?.tasks, selectedWorkflowTaskId) {
        if (selectedWorkflowTaskId != null && loadedProjectWorkflow != null && loadedProjectWorkflow.tasks.none { it.id == selectedWorkflowTaskId }) {
            selectedWorkflowTaskId = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railWidth = 264.dp
        val minimumChatWidth = 520.dp
        val baseWorkspaceWidth = railWidth + minimumChatWidth + 12.dp
        val activeTerminalRunId = activeRunId?.takeIf { it in terminalTabIds }
        val terminalTabs = terminalTabIds.mapNotNull { tabId -> running.firstOrNull { it.runId == tabId } }

        Column(
            Modifier
                .width(maxOf(maxWidth, baseWorkspaceWidth))
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.width(railWidth).fillMaxHeight().background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Toolbar("Projects", "${config.projects.size} current projects", onPrimary = { editingProject = EditingProject(null) }, primaryLabel = "New")
                    TextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Find a project", color = TextSecondary, fontFamily = MonoFont) })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                        items(projects, key = { it.id }) { item ->
                            val sessions = agentTasks.filter { it.projectId == item.id }
                                .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
                            ProjectSessionGroup(
                                project = item,
                                sessions = if (expandedProjectSessionsId == item.id) sessions else sessions.take(RecentSessionsPerProject),
                                selectedProject = item.id == selectedProjectId,
                                selectedSessionId = selectedTaskId,
                                hasUnread = item.id in unreadProjectIds,
                                showMore = sessions.size > RecentSessionsPerProject && expandedProjectSessionsId != item.id,
                                onOpenProject = {
                                    selectedProjectId = item.id
                                    selectedTaskId = sessions.firstOrNull()?.id
                                    selectedWorkflowTaskId = null
                                    canvas = ProjectCanvas.Chat
                                },
                                onOpenSession = { task ->
                                    selectedProjectId = item.id
                                    selectedTaskId = task.id
                                    canvas = ProjectCanvas.Chat
                                    if (task.unread) services.agentRuns.markRead(task.id)
                                },
                                onMarkSessionUnread = { task -> services.agentRuns.markUnread(task.id) },
                                onShowMore = { expandedProjectSessionsId = item.id },
                                onNewChat = {
                                    selectedProjectId = item.id
                                    selectedTaskId = null
                                    selectedWorkflowTaskId = null
                                    canvas = ProjectCanvas.Chat
                                },
                                onEditProject = { editingProject = EditingProject(item) },
                            )
                        }
                    }
                }
                project?.let { current ->
                    Column(Modifier.widthIn(min = minimumChatWidth).weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProjectChatToolbar(
                            project = current,
                            canvas = canvas,
                            terminalPlacement = terminalPlacement,
                            onCanvasChange = {
                                canvas = it
                                if (it != ProjectCanvas.Tasks) selectedWorkflowTaskId = null
                            },
                            onToggleRightTerminal = {
                                if (terminalPlacement == TerminalPlacement.Right) {
                                    terminalPlacement = null
                                    return@ProjectChatToolbar
                                }
                                lastTerminalPlacement = TerminalPlacement.Right
                                val runId = activeRunId?.takeIf { activeId ->
                                    running.any { it.runId == activeId && it.projectId == current.id }
                                }
                                    ?: services.actionRuns.openShell(current)
                                selectTerminalTab(runId)
                                terminalPlacement = TerminalPlacement.Right
                            },
                            onToggleBottomTerminal = {
                                if (terminalPlacement == TerminalPlacement.Bottom) {
                                    terminalPlacement = null
                                    return@ProjectChatToolbar
                                }
                                lastTerminalPlacement = TerminalPlacement.Bottom
                                val runId = activeRunId?.takeIf { activeId ->
                                    running.any { it.runId == activeId && it.projectId == current.id }
                                }
                                    ?: services.actionRuns.openShell(current)
                                selectTerminalTab(runId)
                                terminalPlacement = TerminalPlacement.Bottom
                            },
                        )
                        PanelCard(
                            Modifier.fillMaxSize().testTag(if (canvas == ProjectCanvas.Chat) "project-chat-pane" else "project-task-dock"),
                            accent = if (canvas == ProjectCanvas.Chat) Cyan else null,
                        ) {
                            when (canvas) {
                                ProjectCanvas.Chat -> {
                                    val selected = projectTasks.firstOrNull { it.id == selectedTaskId }
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
                                                nowMillis,
                                                onDelete = { task -> scope.launch { services.agentRuns.delete(task.id, task.ownsWorktree); selectedTaskId = null } },
                                                compactToolCalls = compactToolCalls,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                                ProjectCanvas.Tasks -> {
                                    val workflow = projectWorkflow ?: ProjectWorkflowState(current.id)
                                    Row(
                                        Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ProjectWorkflowList(
                                            workflow = workflow,
                                            selectedTaskId = selectedWorkflowTaskId,
                                            onSelectTask = { selectedWorkflowTaskId = it },
                                            onNewSpec = { editingSpec = null; specEditorOpen = true },
                                            onNewBuild = { buildEditor = BuildEditorSeed() },
                                            onProfiles = { profilesOpen = true },
                                            modifier = Modifier.width(360.dp).fillMaxHeight(),
                                        )
                                        PaneDivider(onDrag = {})
                                        ProjectWorkflowDetail(
                                            services = services,
                                            workflow = workflow,
                                            task = workflow.tasks.firstOrNull { it.id == selectedWorkflowTaskId },
                                            agentTasks = projectTasks,
                                            onNewBuildFromPlan = { buildEditor = BuildEditorSeed(plan = it) },
                                            onOpenRun = { runId -> selectedTaskId = runId; canvas = ProjectCanvas.Chat; services.agentRuns.markRead(runId) },
                                            onEdit = { task ->
                                                if (task.kind == ProjectTaskKind.Spec) { editingSpec = task; specEditorOpen = true }
                                                else buildEditor = BuildEditorSeed(buildTaskId = task.linkedBuildTaskId ?: task.id)
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
                                        selectTerminalTab(runId)
                                        terminalPlacement = lastTerminalPlacement
                                    },
                                )
                                ProjectCanvas.Scratchpad -> ProjectScratchpadEditor(
                                    services = services,
                                    projectId = current.id,
                                    persistedText = projectWorkflow?.scratchpad.orEmpty(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    if (terminalPlacement == TerminalPlacement.Right) {
                        ProjectTerminalDrawer(
                            services = services,
                            terminalTabs = terminalTabs,
                            activeRunId = activeTerminalRunId,
                            placement = TerminalPlacement.Right,
                            onSelectTab = ::selectTerminalTab,
                            onCloseTab = ::closeTerminalTab,
                            onClose = { terminalPlacement = null },
                            modifier = Modifier.width(460.dp).fillMaxHeight(),
                        )
                    }
                } ?: EmptyState("Create a project to start")
            }
            if (terminalPlacement == TerminalPlacement.Bottom) {
                ProjectTerminalDrawer(
                    services = services,
                    terminalTabs = terminalTabs,
                    activeRunId = activeTerminalRunId,
                    placement = TerminalPlacement.Bottom,
                    onSelectTab = ::selectTerminalTab,
                    onCloseTab = ::closeTerminalTab,
                    onClose = { terminalPlacement = null },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
            }
        }
    }
    editingProject?.let { edit ->
        ProjectDialog(
            project = edit.project,
            existingProjects = config.projects,
            onDismiss = { editingProject = null },
            onDelete = edit.project?.let { project ->
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
    if (specEditorOpen && project != null && projectWorkflow != null) {
        SpecTaskDialog(services, project, projectWorkflow, editingSpec, agentCliStatuses, onDismiss = { specEditorOpen = false }) { id ->
            specEditorOpen = false
            selectedWorkflowTaskId = id
        }
    }
    buildEditor?.let { seed ->
        if (project != null && projectWorkflow != null) BuildPairDialog(services, project, projectWorkflow, seed, agentCliStatuses, onDismiss = { buildEditor = null }) { id ->
            buildEditor = null
            selectedWorkflowTaskId = id
        }
    }
    if (profilesOpen && projectWorkflow != null) ProjectProfilesDialog(services, projectWorkflow, agentCliStatuses) { profilesOpen = false }
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
    running: List<RunningAction>,
    activeRunId: String?,
    terminalRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
    agentTasks: List<AgentTask>,
    showIntroduction: Boolean = false,
    onIntroductionComplete: () -> Unit = {},
    active: Boolean = true,
    initialWorkflowTaskId: String? = null,
    initialCanvasLabel: String? = null,
    requestedAgentTaskId: String? = null,
    requestedProjectId: String? = null,
    onRequestedAgentTaskConsumed: () -> Unit = {},
    compactToolCalls: Boolean = true,
) {
    if (showIntroduction) {
        ProjectsIntroduction(onComplete = onIntroductionComplete)
    } else {
        ProjectCockpit(
            services = services,
            config = config,
            running = running,
            activeRunId = activeRunId,
            terminalRunId = terminalRunId,
            onActiveRunIdChange = onActiveRunIdChange,
            onConfigChange = onConfigChange,
            agentTasks = agentTasks,
            initialWorkflowTaskId = initialWorkflowTaskId,
            initialCanvasLabel = initialCanvasLabel,
            requestedAgentTaskId = requestedAgentTaskId,
            requestedProjectId = requestedProjectId,
            onRequestedAgentTaskConsumed = onRequestedAgentTaskConsumed,
            compactToolCalls = compactToolCalls,
        )
    }
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
private fun ProjectSessionGroup(
    project: ActionProject,
    sessions: List<AgentTask>,
    selectedProject: Boolean,
    selectedSessionId: String?,
    hasUnread: Boolean,
    showMore: Boolean,
    onOpenProject: () -> Unit,
    onOpenSession: (AgentTask) -> Unit,
    onMarkSessionUnread: (AgentTask) -> Unit,
    onShowMore: () -> Unit,
    onNewChat: () -> Unit,
    onEditProject: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selectedProject) AndyColors.Neutral700 else Color.Transparent, RoundedCornerShape(AndyRadius.R3))
                .hoverable(interactionSource)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                project.name,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable(onClick = onOpenProject),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(
                visible = hovered,
                enter = fadeIn(tween(120)) + expandHorizontally(tween(160), expandFrom = Alignment.End),
                exit = fadeOut(tween(90)) + shrinkHorizontally(tween(120), shrinkTowards = Alignment.End),
            ) {
                Text(
                    "Edit",
                    color = Cyan,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onEditProject).padding(end = 2.dp),
                )
            }
            NewProjectChatButton(onClick = onNewChat, size = 15.dp)
            if (hasUnread) UnreadDot()
        }
        sessions.forEach { task ->
            ProjectSessionRow(
                task = task,
                selected = task.id == selectedSessionId,
                onOpen = { onOpenSession(task) },
                onMarkUnread = { onMarkSessionUnread(task) },
            )
        }
        if (showMore) {
            Text(
                "Show more",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp).clickable(onClick = onShowMore).padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProjectSessionRow(
    task: AgentTask,
    selected: Boolean,
    onOpen: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 14.dp)
                .background(if (selected) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
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
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier.size(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    task.isActive -> ProjectActivityIndicator(9.dp)
                    task.unread -> UnreadDot()
                }
            }
            Text(
                task.title,
                color = if (selected) TextPrimary else TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun ProjectChatToolbar(
    project: ActionProject,
    canvas: ProjectCanvas,
    terminalPlacement: TerminalPlacement?,
    onCanvasChange: (ProjectCanvas) -> Unit,
    onToggleRightTerminal: () -> Unit,
    onToggleBottomTerminal: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(project.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(project.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectCanvas.entries.filter { it != ProjectCanvas.Chat }.forEach { tab ->
                FilterPill(tab.label, canvas == tab, if (tab == ProjectCanvas.Runbook) Rust else Cyan) { onCanvasChange(tab) }
            }
        }
        TerminalDrawerToggle(
            placement = TerminalPlacement.Bottom,
            selected = terminalPlacement == TerminalPlacement.Bottom,
            onClick = onToggleBottomTerminal,
        )
        TerminalDrawerToggle(
            placement = TerminalPlacement.Right,
            selected = terminalPlacement == TerminalPlacement.Right,
            onClick = onToggleRightTerminal,
        )
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
private fun TerminalDrawerToggle(placement: TerminalPlacement, selected: Boolean, onClick: () -> Unit) {
    val label = if (placement == TerminalPlacement.Right) "Show terminal on right" else "Show terminal at bottom"
    Box(
        Modifier
            .size(28.dp)
            .background(if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, if (selected) AndyColors.OrangeBorder else Border, RoundedCornerShape(AndyRadius.R2))
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = Stroke(width = 1.4.dp.toPx())
            val color = if (selected) Rust else TextSecondary
            drawRect(color = color, style = stroke)
            if (placement == TerminalPlacement.Right) {
                drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width * 0.32f, 0f), end = androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height), strokeWidth = stroke.width)
            } else {
                drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.68f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.68f), strokeWidth = stroke.width)
            }
        }
    }
}

@Composable
private fun ProjectTerminalDrawer(
    services: AndyServices,
    terminalTabs: List<RunningAction>,
    activeRunId: String?,
    placement: TerminalPlacement,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(170, easing = FastOutSlowInEasing))
    }
    PanelCard(
        modifier.graphicsLayer {
            alpha = 0.72f + reveal.value * 0.28f
            if (placement == TerminalPlacement.Right) translationX = (1f - reveal.value) * 28f
            else translationY = (1f - reveal.value) * 28f
        },
        accent = Rust,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Terminal", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
                Text(if (activeRunId == null) "Run an action to start a shell" else "Interactive project shell", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        if (terminalTabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                terminalTabs.forEach { terminal ->
                    TerminalTabPill(
                        text = terminal.actionName,
                        selected = terminal.runId == activeRunId,
                        color = actionStatusColor(terminal.status),
                        icon = actionIconMarker(terminal.icon),
                        onClick = { onSelectTab(terminal.runId) },
                        onClose = { onCloseTab(terminal.runId) },
                    )
                }
            }
        }
        if (activeRunId == null) {
            EmptyState("Run an action to open its terminal")
        } else {
            // Right-docked SwingPanel can cover chrome menus; bottom placement cannot.
            val suppressForChromeMenus =
                LocalSuppressHeavyweightSurfaces.current && placement == TerminalPlacement.Right
            CompositionLocalProvider(LocalSuppressHeavyweightSurfaces provides suppressForChromeMenus) {
                ProjectTerminalSurface(services, activeRunId, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TerminalTabPill(
    text: String,
    selected: Boolean,
    color: Color,
    icon: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(AndyRadius.R2)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(28.dp)
            .hoverable(interaction)
            .background(if (selected) color.copy(alpha = 0.26f) else AndyColors.Neutral850, shape)
            .border(1.dp, if (selected) color.copy(alpha = 0.70f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(icon, color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300, fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 14.sp)
        Text(
            text.lowercase(),
            color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        if (hovered) {
            Text(
                "×",
                color = Red,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .semantics { contentDescription = "Close tab"; role = Role.Button }
                    .clickable(onClick = onClose)
                    .padding(start = 2.dp),
            )
        }
    }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Runbook", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Button(onClick = onNewAction) { Text("Add action") }
    }
    if (project.actions.isEmpty()) {
        EmptyState("Add the commands you use most")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(project.actions, key = { it.id }) { action ->
                val expanded = expandedActionId == action.id
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (expanded) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3))
                        .border(1.dp, if (expanded) AndyColors.OrangeBorder.copy(alpha = 0.58f) else Border, RoundedCornerShape(AndyRadius.R3))
                        .clickable { onExpandedActionChange(if (expanded) null else action.id) }
                        .animateContentSize(animationSpec = tween(220))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(actionIconMarker(action.icon), color = Rust, fontFamily = MonoFont)
                        Text(action.name, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { onEditAction(action) }) { Text("Edit") }
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
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
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
