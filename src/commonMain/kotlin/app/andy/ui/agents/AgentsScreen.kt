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
import app.andy.currentTimeMillis
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
private fun AgentCommandCenter(
    services: AndyServices,
    active: Boolean,
    requestedTaskId: String?,
    onRequestedTaskConsumed: () -> Unit,
    compactToolCalls: Boolean,
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
    LaunchedEffect(active) { if (!active) { composing = true; pendingConfirmation = null } }
    LaunchedEffect(requestedTaskId, tasks) {
        requestedTaskId?.let { id -> tasks.firstOrNull { it.id == id && it.projectId == null }?.let { task -> selectedTaskId = task.id; composing = false; services.agentRuns.markRead(task.id) }; onRequestedTaskConsumed() }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); nowMillis = currentTimeMillis() } }

    val inbox = remember(tasks, query, activeOnly, showArchived) {
        tasks.filter { it.projectId == null }
            .filter { task -> task.archived == showArchived }
            .filter { task -> !activeOnly || task.isActive }
            .filter { task -> query.isBlank() || task.title.contains(query, true) || task.prompt.contains(query, true) || task.agent.label.contains(query, true) }
            .sortedWith(compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis })
    }
    val selected = tasks.firstOrNull { it.id == selectedTaskId && it.projectId == null && it.archived == showArchived }
        ?: inbox.firstOrNull()
    val activeTasks = inbox.filter { it.isActive }
    // Open chats stay read — including when a live run finishes while you're watching.
    // Gate on [active] so a retained Agents pane off-destination cannot clear unread.
    LaunchedEffect(active, selected?.id, selected?.status, composing) {
        if (!active) return@LaunchedEffect
        val task = selected ?: return@LaunchedEffect
        if (composing) return@LaunchedEffect
        if (task.unread && !task.isActive) {
            services.agentRuns.markRead(task.id)
        }
    }

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
                    FilterPill("All", !activeOnly && !showArchived, Cyan) { activeOnly = false; showArchived = false }
                    FilterPill("Live", activeOnly, Green) { activeOnly = true; showArchived = false }
                    FilterPill("Archived", showArchived, TextSecondary) { showArchived = true; activeOnly = false }
                }
                if (statuses.isNotEmpty()) {
                    AgentReadinessStrip(
                        readyCount = statuses.count { it.ready },
                        totalCount = statuses.size,
                    )
                }
                if (inbox.isEmpty()) EmptyState(if (query.isBlank()) if (showArchived) "No archived tasks" else "No tasks yet" else "No matching tasks") else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(inbox, key = { it.id }) { task ->
                        val sessionStatus by services.agentRuns.sessionStatus(task.id).collectAsState()
                        AgentTaskCard(
                            task,
                            null,
                            !composing && task.id == selected?.id,
                            nowMillis,
                            sessionStatus = sessionStatus,
                            onClick = { selectedTaskId = task.id; composing = false; if (task.unread) services.agentRuns.markRead(task.id) },
                            onMarkUnread = { services.agentRuns.markUnread(task.id) },
                            onArchive = if (showArchived) {
                                { services.agentRuns.unarchive(task.id) }
                            } else {
                                { services.agentRuns.archive(task.id); if (selectedTaskId == task.id) selectedTaskId = null }
                            },
                            archiveLabel = if (showArchived) "Unarchive" else "Archive",
                        )
                    }
                }
            }
            PaneDivider(onDrag = {})
            PanelCard(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // Keep the composer mounted so draft text/images survive opening a transcript.
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
                            EmptyState("Select a task or start a new one")
                        } else {
                            AgentTaskDetail(
                                services,
                                selected,
                                nowMillis,
                                onDelete = { task ->
                                    pendingConfirmation = PendingConfirmation("Delete task?", task.title) {
                                        scope.launch {
                                            transcriptScrollMemory.remove(task.id)
                                            services.agentRuns.delete(task.id, task.worktreePath != null)
                                            selectedTaskId = null
                                        }
                                    }
                                },
                                compactToolCalls = compactToolCalls,
                                transcriptScrollMemory = transcriptScrollMemory,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
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
    requestedTaskId: String? = null,
    onRequestedTaskConsumed: () -> Unit = {},
    compactToolCalls: Boolean = true,
) {
    AgentCommandCenter(services, active, requestedTaskId, onRequestedTaskConsumed, compactToolCalls)
}

@Composable
private fun AgentTaskCard(
    task: AgentTask,
    projectName: String?,
    selected: Boolean,
    nowMillis: Long,
    sessionStatus: app.andy.model.AgentSessionStatus? = null,
    onClick: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit,
    archiveLabel: String = "Archive",
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
                    if (sessionStatus == app.andy.model.AgentSessionStatus.Blocked) {
                        SessionStatusDot(sessionStatus)
                    }
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
                if (sessionStatus != null && task.isActive) {
                    StatusTag(agentSessionStatusLabel(sessionStatus), agentSessionStatusColor(sessionStatus))
                } else {
                    StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
                }
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
                val elapsedEnd = rememberElapsedEndMillis(
                    taskId = task.id,
                    finishedAtMillis = task.finishedAtMillis,
                    isActive = task.isActive,
                    sessionStatus = sessionStatus,
                )
                formatElapsed(task.startedAtMillis, elapsedEnd, nowMillis)?.let {
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
            DropdownMenuItem(
                text = { Text(archiveLabel, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp) },
                onClick = {
                    menuExpanded = false
                    onArchive()
                },
                enabled = archiveLabel == "Unarchive" || !task.isActive,
            )
        }
    }
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
