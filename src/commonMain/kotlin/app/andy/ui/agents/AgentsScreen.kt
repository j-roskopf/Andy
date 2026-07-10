package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
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
    var showComposer by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var listPaneWidth by remember { mutableStateOf(420f) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(active) {
        if (!active) {
            showComposer = false
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

    val ordered = remember(tasks) {
        tasks.sortedWith(
            compareByDescending<AgentTask> { it.isActive }.thenByDescending { it.createdAtMillis },
        )
    }
    val selectedTask = ordered.firstOrNull { it.id == selectedTaskId } ?: ordered.firstOrNull()
    val runningCount = tasks.count { it.isActive }
    val availableAgents = cliStatuses.filter { it.available }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(listPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar(
                "Agents",
                "${tasks.size} tasks / $runningCount active / ${availableAgents.size} CLIs found",
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
                    items(ordered, key = { it.id }) { task ->
                        AgentTaskCard(
                            task = task,
                            projectName = task.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name },
                            selected = task.id == selectedTask?.id,
                            nowMillis = nowMillis,
                            onClick = { selectedTaskId = task.id },
                        )
                    }
                }
            }
        }
        PaneDivider(onDrag = { dragX -> listPaneWidth = (listPaneWidth + dragX).coerceIn(320f, 720f) })
        PanelCard(Modifier.fillMaxSize()) {
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
    }

    if (showComposer) {
            AgentTaskComposer(
                services = services,
                cliStatuses = cliStatuses,
            onDismiss = { showComposer = false },
            onSubmit = { draft ->
                showComposer = false
                scope.launch {
                    val task = services.agentRuns.createAndStart(draft)
                    selectedTaskId = task.id
                }
            },
        )
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
            pendingConfirmation = null
            confirmation.onConfirm()
        })
    }
}

@Composable
private fun AgentTaskCard(
    task: AgentTask,
    projectName: String?,
    selected: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral900.copy(alpha = 0.72f),
                RoundedCornerShape(AndyRadius.R3),
            )
            .border(
                1.dp,
                if (selected) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border,
                RoundedCornerShape(AndyRadius.R3),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentBadge(task.agent)
            Text(
                task.title,
                color = TextPrimary,
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
}
