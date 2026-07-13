package app.andy.ui.agents

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.ActionProject
import app.andy.model.AgentTask
import app.andy.model.modelConfigurationLabel
import app.andy.service.AndyServices
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.EmptyState
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.StatusTag
import app.andy.ui.components.Toolbar
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AgentsScreen(
    services: AndyServices,
    active: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val tasks by services.agentRuns.tasks.collectAsState()
    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    var projects by remember { mutableStateOf<List<ActionProject>>(emptyList()) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var showComposer by remember { mutableStateOf(true) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var listPaneWidth by remember { mutableStateOf(420f) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(active) {
        if (!active) {
            showComposer = true
            pendingConfirmation = null
        }
    }

    LaunchedEffect(Unit) {
        projects = services.actionConfig.load().projects
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Chats started from a project stay with that project instead of appearing
    // in the standalone Agent inbox as well.
    val agentTasks = remember(tasks) { tasks.filter { it.projectId == null } }
    val ordered = remember(agentTasks) {
        agentTasks.sortedWith(
            compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis },
        )
    }
    val selectedTask = ordered.firstOrNull { it.id == selectedTaskId } ?: ordered.firstOrNull()
    val activeTasks = remember(ordered) { ordered.filter { it.isActive } }
    val completedTasks = remember(ordered) { ordered.filterNot { it.isActive } }
    val runningCount = activeTasks.size
    val availableAgents = cliStatuses.filter { it.ready }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(listPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar(
                "Agent sessions",
                "${agentTasks.size} sessions / $runningCount active / ${availableAgents.size} ready",
                onPrimary = { showComposer = true },
                primaryLabel = "new task",
            )
            if (availableAgents.isEmpty() && cliStatuses.isNotEmpty()) {
                Text(
                    "no agent CLIs found — install claude, codex, cursor-agent, or agy",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
            if (ordered.isEmpty()) {
                EmptyState("no agent tasks yet — dispatch one to claude, codex, cursor, or antigravity")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    if (activeTasks.isNotEmpty()) {
                        item(key = "active-header") { AgentSessionLabel("In progress", activeTasks.size) }
                    }
                    items(activeTasks, key = { it.id }) { task ->
                        AgentTaskCard(
                            task = task,
                            projectName = task.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name },
                            selected = !showComposer && task.id == selectedTask?.id,
                            nowMillis = nowMillis,
                            onClick = {
                                selectedTaskId = task.id
                                showComposer = false
                                if (task.unread) services.agentRuns.markRead(task.id)
                            },
                            onMarkUnread = { services.agentRuns.markUnread(task.id) },
                        )
                    }
                    if (completedTasks.isNotEmpty()) {
                        item(key = "recent-header") { AgentSessionLabel("Recent", completedTasks.size) }
                    }
                    items(completedTasks, key = { it.id }) { task ->
                        AgentTaskCard(
                            task = task,
                            projectName = task.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name },
                            selected = !showComposer && task.id == selectedTask?.id,
                            nowMillis = nowMillis,
                            onClick = {
                                selectedTaskId = task.id
                                showComposer = false
                                if (task.unread) services.agentRuns.markRead(task.id)
                            },
                            onMarkUnread = { services.agentRuns.markUnread(task.id) },
                        )
                    }
                }
            }
        }
        PaneDivider(onDrag = { dragX -> listPaneWidth = (listPaneWidth + dragX).coerceIn(320f, 720f) })
        PanelCard(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (!showComposer) {
                    if (selectedTask == null) {
                        EmptyState("select a task to see its transcript")
                    } else {
                        AgentTaskDetail(
                            services = services,
                            task = selectedTask,
                            nowMillis = nowMillis,
                            onDelete = { task ->
                                val removesWorktree = task.worktreePath != null
                                pendingConfirmation = PendingConfirmation(
                                    "Delete task?",
                                    buildString {
                                        append(task.title)
                                        if (task.isActive) append(" — it is still running and will be stopped")
                                        if (removesWorktree) append(" — its worktree and branch will be removed (uncommitted work is lost)")
                                    },
                                ) {
                                    scope.launch {
                                        services.agentRuns.delete(task.id, removeWorktree = removesWorktree)
                                        if (selectedTaskId == task.id) selectedTaskId = null
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Keep the new-task draft (prompt + selections) alive while browsing other chats.
                RetainedDestination(active = showComposer) {
                    AgentTaskComposerPane(
                        services = services,
                        cliStatuses = cliStatuses,
                        projectContext = null,
                        onCancel = { showComposer = false },
                        onSubmit = { draft ->
                            scope.launch {
                                val task = services.agentRuns.createAndStart(draft)
                                selectedTaskId = task.id
                                showComposer = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
private fun AgentSessionLabel(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextSecondary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text("$count", color = TextSecondary.copy(alpha = 0.72f), fontFamily = MonoFont, fontSize = 10.sp)
    }
}

@Composable
private fun AgentTaskCard(
    task: AgentTask,
    projectName: String?,
    selected: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val surfaceColor by animateColorAsState(
        targetValue = if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f),
        animationSpec = tween(180),
        label = "agent-session-selection",
    )
    Box {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    surfaceColor,
                    RoundedCornerShape(AndyRadius.R3),
                )
                .border(
                    1.dp,
                    if (selected) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border,
                    RoundedCornerShape(AndyRadius.R3),
                )
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
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (task.unread) UnreadDot()
                    AgentBadge(task.agent)
                }
                Text(
                    task.title,
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    task.modelConfigurationLabel(),
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    projectName ?: task.originDir ?: "no project context",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (task.useWorktree) {
                    Text("worktree", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
                formatElapsed(task.startedAtMillis, task.finishedAtMillis, nowMillis)?.let {
                    Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
                formatCost(task.totalCostUsd)?.let {
                    Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
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
