package app.andy.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.domain.splitPriorityChats
import app.andy.domain.excludingTemporary
import app.andy.domain.onlyTemporary
import app.andy.domain.temporaryChatOrder
import app.andy.domain.temporaryChatNeedsDiscardConfirm
import app.andy.ui.components.Button
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.WorkspaceEmptyCanvas
import app.andy.ui.components.WorkspaceRailHeader
import app.andy.ui.components.WorkspaceSplit
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Cyan
import kotlinx.coroutines.launch

@Composable
private fun AgentCommandCenter(
    services: AndyServices,
    active: Boolean,
    requestedTaskId: String?,
    onRequestedTaskConsumed: () -> Unit,
    workspaceState: WorkspaceState,
    onViewedTaskChange: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tasks by services.agentRuns.tasks.collectAsState()
    val statuses by services.agentRuns.cliStatuses.collectAsState()
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var activeOnly by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showUnscopedArtifacts by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val transcriptScrollMemory = remember { TranscriptScrollMemory() }
    var projects by remember { mutableStateOf<List<app.andy.model.ActionProject>>(emptyList()) }
    LaunchedEffect(Unit) {
        projects = runCatching { services.actionConfig.load().projects }.getOrDefault(emptyList())
    }
    // Keep the open chat mounted while Agents is retained-but-inactive. Forcing composing
    // tore down AgentTerminalSurface, released the tmux viewer, and caused a multi-flash
    // reattach when returning.
    LaunchedEffect(active) { if (!active) pendingConfirmation = null }
    LaunchedEffect(requestedTaskId, tasks) {
        requestedTaskId?.let { id ->
            tasks.firstOrNull { it.id == id && it.projectId == null }?.let { task ->
                selectedTaskId = task.id
                composing = false
                services.agentRuns.setChatViewing(task.id, viewing = true)
            }
            onRequestedTaskConsumed()
        }
    }

    fun requestKeep(task: AgentTask) {
        scope.launch { services.agentRuns.keepTemporaryChat(task.id) }
    }

    fun requestDelete(task: AgentTask, force: Boolean = false) {
        if (force) {
            scope.launch {
                transcriptScrollMemory.remove(task.id)
                services.agentRuns.delete(task.id, task.ownsWorktree, force = true)
                if (selectedTaskId == task.id) selectedTaskId = null
            }
            return
        }
        // Discarding a temporary chat is unrecoverable by design — nothing was ever written —
        // so it says what is being lost and offers the way out instead.
        if (task.temporary) {
            if (!temporaryChatNeedsDiscardConfirm(task)) {
                requestDelete(task, force = true)
                return
            }
            val worktreeNote = if (task.ownsWorktree && task.branchName != null) {
                "\n\nIts worktree and branch \"${task.branchName}\" are deleted too."
            } else {
                ""
            }
            pendingConfirmation = PendingConfirmation(
                title = "Discard temporary chat?",
                message = "\"${task.title}\" was never saved, so this cannot be undone.$worktreeNote",
                confirmLabel = "Discard",
                neutralLabel = "Keep chat",
                onNeutral = {
                    pendingConfirmation = null
                    requestKeep(task)
                },
            ) { requestDelete(task, force = true) }
            return
        }
        pendingConfirmation = PendingConfirmation(
            title = "Delete chat?",
            message = "Permanently removes \"${task.title}\" and its saved transcript.",
            confirmLabel = "Delete",
        ) {
            scope.launch {
                when (val outcome = services.agentRuns.delete(task.id, task.ownsWorktree)) {
                    app.andy.model.WorktreeDeleteOutcome.Deleted -> {
                        transcriptScrollMemory.remove(task.id)
                        if (selectedTaskId == task.id) selectedTaskId = null
                    }
                    is app.andy.model.WorktreeDeleteOutcome.BlockedByChildren -> {
                        val childList = outcome.children.joinToString("\n") { child ->
                            "• ${child.title} (${child.branch})"
                        }
                        pendingConfirmation = PendingConfirmation(
                            title = "Delete worktree with children?",
                            message = "\"${task.title}\" still has nested worktrees:\n$childList\n\nDelete anyway? Child worktrees become roots and keep their branches.",
                            confirmLabel = "Delete anyway",
                        ) { requestDelete(task, force = true) }
                    }
                }
            }
        }
    }

    val inbox = remember(tasks, query, activeOnly, showArchived) {
        tasks.filter { it.projectId == null }
            .filter { task -> task.archived == showArchived }
            .filter { task -> !activeOnly || task.isActive }
            .filter { task ->
                query.isBlank() ||
                    task.title.contains(query, true) ||
                    task.prompt.contains(query, true) ||
                    task.agent.label.contains(query, true)
            }
            .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
    }
    // Temporary chats get their own pinned section: they are never history, but they still need
    // somewhere to click back to while they are alive.
    val temporaryInbox = remember(inbox) { inbox.onlyTemporary().temporaryChatOrder() }
    val persistentInbox = remember(inbox) { inbox.excludingTemporary() }
    val pinPriority = workspaceState.agentPinPriorityChats && !showArchived
    val groupedInbox = remember(persistentInbox, pinPriority) {
        if (pinPriority) splitPriorityChats(persistentInbox) else null
    }
    val selected = tasks.firstOrNull { it.id == selectedTaskId && it.projectId == null && it.archived == showArchived }
        ?: inbox.firstOrNull()
    val activeTasks = inbox.filter { it.isActive }
    fun openInboxTask(task: AgentTask) {
        selectedTaskId = task.id
        composing = false
        services.agentRuns.setChatViewing(task.id, viewing = true)
    }
    DisposableEffect(active, selected?.id, composing) {
        val taskId = selected?.id?.takeIf { active && !composing }
        if (taskId != null) {
            services.agentRuns.setChatViewing(taskId, viewing = true)
            onViewedTaskChange(taskId)
        }
        onDispose {
            if (taskId != null) {
                services.agentRuns.setChatViewing(taskId, viewing = false)
                onViewedTaskChange(null)
            }
        }
    }
    val viewingChatId = selected?.id?.takeIf { active && !composing }
    SideEffect {
        if (viewingChatId != null) onViewedTaskChange(viewingChatId)
    }

    WorkspaceSplit(
        sidebarWidth = 236.dp,
        sidebar = {
            WorkspaceRailHeader(
                title = "Agents",
                subtitle = when {
                    activeTasks.isNotEmpty() -> "${activeTasks.size} running"
                    statuses.isNotEmpty() -> "${statuses.count { it.ready }} of ${statuses.size} ready"
                    else -> null
                },
                actions = {
                    Button(onClick = { composing = true; showUnscopedArtifacts = false }, colors = primaryButtonColors()) {
                        Text("New", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
            TextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search", color = TextSecondary, fontFamily = MonoFont) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill("All", !activeOnly && !showArchived && !showUnscopedArtifacts, Cyan) {
                    activeOnly = false; showArchived = false; showUnscopedArtifacts = false
                }
                FilterPill("Live", activeOnly && !showUnscopedArtifacts, Green) {
                    activeOnly = true; showArchived = false; showUnscopedArtifacts = false
                }
                FilterPill("Archived", showArchived && !showUnscopedArtifacts, TextSecondary) {
                    showArchived = true; activeOnly = false; showUnscopedArtifacts = false
                }
                FilterPill("Unscoped artifacts", showUnscopedArtifacts, Cyan) {
                    showUnscopedArtifacts = true; activeOnly = false; showArchived = false
                }
            }
            if (showUnscopedArtifacts) {
                WorkspaceEmptyCanvas(
                    "Unscoped artifacts appear in the main pane",
                    Modifier.weight(1f),
                )
            } else if (inbox.isEmpty()) {
                WorkspaceEmptyCanvas(
                    if (query.isBlank()) {
                        if (showArchived) "No archived tasks" else "No tasks yet"
                    } else {
                        "No matching tasks"
                    },
                    Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val priority = groupedInbox?.priority.orEmpty()
                    val rest = groupedInbox?.rest ?: persistentInbox
                    if (temporaryInbox.isNotEmpty()) {
                        item(key = "temporary-header", contentType = "section-label") {
                            ChatInboxSectionLabel("Temporary")
                        }
                        items(temporaryInbox, key = { it.id }) { task ->
                            AgentInboxRow(
                                task = task,
                                selected = !composing && task.id == selected?.id,
                                onClick = { openInboxTask(task) },
                                onMarkUnread = { services.agentRuns.markUnread(task.id) },
                                // Archiving would imply a history a temporary chat does not have.
                                onArchive = null,
                                onDelete = ::requestDelete,
                                deleteLabel = "Discard",
                                onKeep = { requestKeep(task) },
                            )
                        }
                    }
                    if (priority.isNotEmpty()) {
                        item(key = "priority-header", contentType = "section-label") {
                            ChatInboxSectionLabel("Priority")
                        }
                        items(priority, key = { it.id }) { task ->
                            AgentInboxRow(
                                task = task,
                                selected = !composing && task.id == selected?.id,
                                onClick = { openInboxTask(task) },
                                onMarkUnread = { services.agentRuns.markUnread(task.id) },
                                onArchive = {
                                    services.agentRuns.archive(task.id)
                                    if (selectedTaskId == task.id) selectedTaskId = null
                                },
                                onDelete = ::requestDelete,
                            )
                        }
                    }
                    if (priority.isNotEmpty() && rest.isNotEmpty()) {
                        item(key = "recent-header", contentType = "section-label") {
                            ChatInboxSectionLabel("Recent")
                        }
                    }
                    items(rest, key = { it.id }) { task ->
                        AgentInboxRow(
                            task = task,
                            selected = !composing && task.id == selected?.id,
                            onClick = { openInboxTask(task) },
                            onMarkUnread = { services.agentRuns.markUnread(task.id) },
                            onArchive = if (showArchived) {
                                { services.agentRuns.unarchive(task.id) }
                            } else {
                                {
                                    services.agentRuns.archive(task.id)
                                    if (selectedTaskId == task.id) selectedTaskId = null
                                }
                            },
                            archiveLabel = if (showArchived) "Unarchive" else "Archive",
                            onDelete = ::requestDelete,
                        )
                    }
                }
            }
        },
        main = {
            Box(Modifier.fillMaxSize()) {
                if (showUnscopedArtifacts) {
                    app.andy.ui.artifacts.ProjectArtifactsScreen(
                        services = services,
                        projectId = null,
                        projects = projects,
                        onOpenChat = { taskId ->
                            showUnscopedArtifacts = false
                            selectedTaskId = taskId
                            composing = false
                            services.agentRuns.setChatViewing(taskId, viewing = true)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    RetainedDestination(active = composing) {
                        AgentTaskComposerPane(
                            services,
                            statuses,
                            null,
                            onCancel = { composing = false },
                            onSubmit = { draft ->
                                scope.launch {
                                    val task = services.agentRuns.createAndStart(draft)
                                    selectedTaskId = task.id
                                    composing = false
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            workspaceState = workspaceState,
                            dictationActive = active && composing,
                        )
                    }
                    if (selected == null) {
                        if (!composing) {
                            WorkspaceEmptyCanvas("Select a task or start a new one")
                        }
                    } else {
                        // Keep the open transcript mounted under New Chat so follow-up drafts survive.
                        RetainedDestination(active = !composing) {
                            AgentTaskDetail(
                                services,
                                selected,
                                onDelete = ::requestDelete,
                                transcriptScrollMemory = transcriptScrollMemory,
                                workspaceState = workspaceState,
                                modifier = Modifier.fillMaxSize(),
                                dictationActive = active && !composing,
                            )
                        }
                    }
                }
            }
        },
    )
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, { pendingConfirmation = null }) {
            pendingConfirmation = null
            confirmation.onConfirm()
        }
    }
}

@Composable
internal fun AgentsScreen(
    services: AndyServices,
    active: Boolean = true,
    requestedTaskId: String? = null,
    onRequestedTaskConsumed: () -> Unit = {},
    workspaceState: WorkspaceState = WorkspaceState(),
    onViewedTaskChange: (String?) -> Unit = {},
) {
    AgentCommandCenter(services, active, requestedTaskId, onRequestedTaskConsumed, workspaceState, onViewedTaskChange)
}

@Composable
private fun AgentInboxRow(
    task: AgentTask,
    selected: Boolean,
    onClick: () -> Unit,
    onMarkUnread: () -> Unit,
    /** Null for temporary chats, which have no history to archive. */
    onArchive: (() -> Unit)?,
    archiveLabel: String = "Archive",
    onDelete: (AgentTask) -> Unit,
    deleteLabel: String = "Delete",
    /** Promotes a temporary chat to a persisted one. Null for chats that are already permanent. */
    onKeep: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ChatSessionSidebarRow(
            task = task,
            selected = selected,
            onClick = onClick,
            trailing = when {
                task.status == AgentStatus.Blocked -> {
                    { StatusTag("blocked", Red) }
                }
                else -> null
            },
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
            if (onArchive != null) {
                DropdownMenuItem(
                    text = { Text(archiveLabel, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp) },
                    onClick = {
                        menuExpanded = false
                        onArchive()
                    },
                    enabled = archiveLabel == "Unarchive" || !task.isActive,
                )
            }
            if (onKeep != null) {
                DropdownMenuItem(
                    text = { Text("Keep chat", color = Green, fontFamily = MonoFont, fontSize = 12.sp) },
                    onClick = {
                        menuExpanded = false
                        onKeep()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(deleteLabel, color = app.andy.ui.theme.Red, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onDelete(task)
                },
            )
        }
    }
}
