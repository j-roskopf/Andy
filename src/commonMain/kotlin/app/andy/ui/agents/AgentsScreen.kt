package app.andy.ui.agents

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import app.andy.model.AgentKind
import app.andy.model.modelConfigurationLabel
import app.andy.service.AndyServices
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.shell.RetainedDestination
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun AgentCommandCenter(services: AndyServices, active: Boolean) {
    val scope = rememberCoroutineScope()
    val tasks by services.agentRuns.tasks.collectAsState()
    val statuses by services.agentRuns.cliStatuses.collectAsState()
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var activeOnly by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { if (!active) { composing = true; pendingConfirmation = null } }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = System.currentTimeMillis() } }

    val inbox = remember(tasks, query, activeOnly) {
        tasks.filter { it.projectId == null }
            .filter { task -> !activeOnly || task.isActive }
            .filter { task -> query.isBlank() || task.title.contains(query, true) || task.prompt.contains(query, true) || task.agent.label.contains(query, true) }
            .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
    }
    val selected = tasks.firstOrNull { it.id == selectedTaskId && it.projectId == null } ?: inbox.firstOrNull()
    val activeTasks = inbox.filter { it.isActive }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Agent command center", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
                Text("Start focused work, watch it progress, and pick the next move without leaving the thread.", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            }
            Button(onClick = { composing = true }, colors = primaryButtonColors()) { Text("New task") }
        }
        if (activeTasks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(AndyColors.GreenSubtle, RoundedCornerShape(AndyRadius.R4)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(34.dp).background(Green, RoundedCornerShape(AndyRadius.R2)))
                Column(Modifier.weight(1f)) { Text("${activeTasks.size} agent${if (activeTasks.size == 1) "" else "s"} moving now", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold); Text(activeTasks.joinToString("  ·  ") { it.title }, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedButton(onClick = { selectedTaskId = activeTasks.first().id; composing = false }) { Text("Open live work") }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Inbox", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                TextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search tasks", color = TextSecondary, fontFamily = MonoFont) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill("All", !activeOnly, Cyan) { activeOnly = false }
                    FilterPill("Live", activeOnly, Green) { activeOnly = true }
                    Spacer(Modifier.weight(1f))
                    Text("${statuses.count { it.ready }}/${statuses.size}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
                if (inbox.isEmpty()) EmptyState(if (query.isBlank()) "No tasks yet" else "No matching tasks") else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(inbox, key = { it.id }) { task -> AgentTaskCard(task, null, !composing && task.id == selected?.id, nowMillis, onClick = { selectedTaskId = task.id; composing = false; if (task.unread) services.agentRuns.markRead(task.id) }, onMarkUnread = { services.agentRuns.markUnread(task.id) }) }
                }
            }
            PaneDivider(onDrag = {})
            PanelCard(Modifier.fillMaxSize()) {
                if (composing) {
                    AgentTaskComposerPane(services, statuses, null, onCancel = { composing = false }, onSubmit = { draft -> scope.launch { selectedTaskId = services.agentRuns.createAndStart(draft).id; composing = false } }, modifier = Modifier.fillMaxSize())
                } else if (selected == null) {
                    EmptyState("Select a task or start a new one")
                } else {
                    AgentTaskDetail(services, selected, nowMillis, onDelete = { task -> pendingConfirmation = PendingConfirmation("Delete task?", task.title) { scope.launch { services.agentRuns.delete(task.id, task.worktreePath != null); selectedTaskId = null } } }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
    pendingConfirmation?.let { confirmation -> ConfirmationDialog(confirmation, { pendingConfirmation = null }) { pendingConfirmation = null; confirmation.onConfirm() } }
}

@Composable
internal fun AgentsScreen(
    services: AndyServices,
    active: Boolean = true,
) {
    AgentCommandCenter(services, active)
    return

    val scope = rememberCoroutineScope()
    val tasks by services.agentRuns.tasks.collectAsState()
    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    var projects by remember { mutableStateOf<List<ActionProject>>(emptyList()) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var showComposer by remember { mutableStateOf(true) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var listPaneWidth by remember { mutableStateOf(420f) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var query by remember { mutableStateOf("") }
    var sessionFilter by remember { mutableStateOf(AgentSessionFilter.All) }
    var agentFilter by remember { mutableStateOf<AgentKind?>(null) }

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
    val visibleTasks = remember(agentTasks, query, sessionFilter, agentFilter) {
        agentTasks.filter { task ->
            val matchesQuery = query.isBlank() || listOfNotNull(
                task.title,
                task.prompt,
                task.modelConfigurationLabel(),
                task.originDir,
            ).any { value -> value.contains(query, ignoreCase = true) }
            val matchesStatus = when (sessionFilter) {
                AgentSessionFilter.All -> true
                AgentSessionFilter.Active -> task.isActive
                AgentSessionFilter.Finished -> !task.isActive
            }
            matchesQuery && matchesStatus && (agentFilter == null || task.agent == agentFilter)
        }.sortedWith(
            compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis },
        )
    }
    val selectedTask = agentTasks.firstOrNull { it.id == selectedTaskId } ?: visibleTasks.firstOrNull()
    val activeTasks = remember(visibleTasks) { visibleTasks.filter { it.isActive } }
    val completedTasks = remember(visibleTasks) { visibleTasks.filterNot { it.isActive } }
    val runningCount = agentTasks.count { it.isActive }
    val availableAgents = cliStatuses.filter { it.ready }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(listPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar(
                "Agents",
                "${agentTasks.size} sessions · $runningCount active · ${availableAgents.size} ready",
                onPrimary = { showComposer = true },
                primaryLabel = "New task",
            )
            if (cliStatuses.isNotEmpty()) {
                AgentReadinessStrip(availableAgents.size, cliStatuses.size)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search sessions", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentSessionFilter.entries.forEach { filter ->
                    FilterPill(filter.label, sessionFilter == filter, when (filter) {
                        AgentSessionFilter.Active -> Green
                        AgentSessionFilter.Finished -> TextSecondary
                        AgentSessionFilter.All -> AndyColors.OrangeBorder
                    }) { sessionFilter = filter }
                }
                AgentKind.entries.forEach { agent ->
                    FilterPill(agent.label, agentFilter == agent, agentColor(agent)) {
                        agentFilter = if (agentFilter == agent) null else agent
                    }
                }
            }
            if (availableAgents.isEmpty() && cliStatuses.isNotEmpty()) {
                Text(
                    "no agent CLIs found — install claude, codex, cursor-agent, or agy",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
            if (visibleTasks.isEmpty()) {
                EmptyState(
                    if (agentTasks.isEmpty()) "no agent tasks yet — dispatch one to claude, codex, cursor, or antigravity"
                    else "no sessions match these filters",
                )
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
                .animateContentSize(animationSpec = tween(200))
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
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(3.dp)
                .height(38.dp)
                .background(
                    if (selected) AndyColors.OrangeHover else agentStatusColor(task.status).copy(alpha = 0.72f),
                    RoundedCornerShape(AndyRadius.R2),
                ),
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
        }
    }
}

private enum class AgentSessionFilter(val label: String) {
    All("All"),
    Active("Active"),
    Finished("Finished"),
}

@Composable
private fun AgentReadinessStrip(readyCount: Int, totalCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PanelSoft.copy(alpha = 0.56f), RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .background(if (readyCount > 0) Green else TextSecondary, RoundedCornerShape(AndyRadius.R2)),
        )
        Text(
            if (readyCount == totalCount) "All configured agents are ready" else "$readyCount of $totalCount configured agents are ready",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
    }
}
