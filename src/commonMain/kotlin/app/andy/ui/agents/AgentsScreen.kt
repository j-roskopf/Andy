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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentTask
import app.andy.model.modelConfigurationLabel
import app.andy.service.AndyServices
import app.andy.currentTimeMillis
import app.andy.ui.components.Button
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.TextField
import app.andy.ui.components.WorkspaceEmptyCanvas
import app.andy.ui.components.WorkspaceItemRow
import app.andy.ui.components.WorkspaceRailHeader
import app.andy.ui.components.WorkspaceSplit
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Cyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun AgentCommandCenter(
    services: AndyServices,
    active: Boolean,
    requestedTaskId: String?,
    onRequestedTaskConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tasks by services.agentRuns.tasks.collectAsState()
    val statuses by services.agentRuns.cliStatuses.collectAsState()
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var activeOnly by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    val transcriptScrollMemory = remember { TranscriptScrollMemory() }
    // Keep the open chat mounted while Agents is retained-but-inactive. Forcing composing
    // tore down AgentTerminalSurface, released the tmux viewer, and caused a multi-flash
    // reattach when returning. RetainedDestination already suppresses SwingPanels.
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
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = currentTimeMillis() } }

    fun requestDelete(task: AgentTask) {
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
    val selected = tasks.firstOrNull { it.id == selectedTaskId && it.projectId == null && it.archived == showArchived }
        ?: inbox.firstOrNull()
    val activeTasks = inbox.filter { it.isActive }
    DisposableEffect(active, selected?.id, composing) {
        val taskId = selected?.id?.takeIf { active && !composing }
        if (taskId != null) {
            services.agentRuns.setChatViewing(taskId, viewing = true)
        }
        onDispose {
            if (taskId != null) services.agentRuns.setChatViewing(taskId, viewing = false)
        }
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
                    Button(onClick = { composing = true }, colors = primaryButtonColors()) {
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
                FilterPill("All", !activeOnly && !showArchived, Cyan) { activeOnly = false; showArchived = false }
                FilterPill("Live", activeOnly, Green) { activeOnly = true; showArchived = false }
                FilterPill("Archived", showArchived, TextSecondary) { showArchived = true; activeOnly = false }
            }
            if (inbox.isEmpty()) {
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
                    items(inbox, key = { it.id }) { task ->
                        AgentInboxRow(
                            task = task,
                            selected = !composing && task.id == selected?.id,
                            nowMillis = nowMillis,
                            onClick = {
                                selectedTaskId = task.id
                                composing = false
                                services.agentRuns.setChatViewing(task.id, viewing = true)
                            },
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
                    )
                }
                if (!composing) {
                    if (selected == null) {
                        WorkspaceEmptyCanvas("Select a task or start a new one")
                    } else {
                        AgentTaskDetail(
                            services,
                            selected,
                            onDelete = ::requestDelete,
                            transcriptScrollMemory = transcriptScrollMemory,
                            modifier = Modifier.fillMaxSize(),
                        )
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
) {
    AgentCommandCenter(services, active, requestedTaskId, onRequestedTaskConsumed)
}

@Composable
private fun AgentInboxRow(
    task: AgentTask,
    selected: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit,
    archiveLabel: String = "Archive",
    onDelete: (AgentTask) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val elapsedEnd = rememberElapsedEndMillis(
        taskId = task.id,
        finishedAtMillis = task.finishedAtMillis,
        task = task,
    )
    val meta = buildList {
        add(task.agent.label)
        add(task.modelConfigurationLabel())
        formatElapsed(task.startedAtMillis, elapsedEnd, nowMillis)?.let { add(it) }
        formatCost(task.totalCostUsd)?.let { add(it) }
    }.joinToString(" · ")
    Box {
        WorkspaceItemRow(
            title = task.title,
            selected = selected,
            subtitle = meta,
            onClick = onClick,
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when {
                        isSessionWorking(task) -> ProjectActivityIndicator(8.dp)
                        task.unread -> UnreadDot()
                        task.status != null -> StatusDot(task.status!!)
                    }
                }
            },
            trailing = {
                Text(
                    agentStatusLabel(task),
                    color = agentStatusColor(task.status).copy(alpha = 0.85f),
                    fontFamily = MonoFont,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            DropdownMenuItem(
                text = { Text(archiveLabel, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onArchive()
                },
                enabled = archiveLabel == "Unarchive" || !task.isActive,
            )
            DropdownMenuItem(
                text = { Text("Delete", color = app.andy.ui.theme.Red, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onDelete(task)
                },
            )
        }
    }
}
