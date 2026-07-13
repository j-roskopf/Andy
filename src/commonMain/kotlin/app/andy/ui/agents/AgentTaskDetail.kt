package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.andy.rememberCopyText
import app.andy.domain.buildSplitDiffPairs
import app.andy.domain.SplitDiffPair
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentNativeSlashCommand
import app.andy.model.AgentNativeSlashCommands
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import app.andy.model.modelConfigurationLabel
import app.andy.model.parseAgentGoalCommand
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class DiffViewMode { Unified, Split }

@Composable
internal fun AgentTaskDetail(
    services: AndyServices,
    task: AgentTask,
    nowMillis: Long,
    onDelete: (AgentTask) -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    val events by services.agentRuns.events(task.id).collectAsState()
    val skillDirectory = task.worktreePath ?: task.cwd
    val availableSkills by remember(task.agent, skillDirectory) {
        services.agentRuns.skills(task.agent, skillDirectory)
    }.collectAsState()
    var followUp by remember(task.id) { mutableStateOf("") }
    var skillMenuDismissed by remember(task.id) { mutableStateOf(false) }
    var diffSummary by remember(task.id) { mutableStateOf<String?>(null) }
    var changeSummary by remember(task.id) { mutableStateOf<AgentChangeSummary?>(null) }
    var showAllChangedFiles by remember(task.id) { mutableStateOf(false) }
    var expandedDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var loadedFileDiffs by remember(task.id) { mutableStateOf<Map<String, AgentFileDiff>>(emptyMap()) }
    var loadingDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var diffViewMode by remember(task.id) { mutableStateOf(DiffViewMode.Unified) }
    var copiedHint by remember(task.id) { mutableStateOf(false) }
    var followUpImagePaths by remember(task.id) { mutableStateOf<List<String>>(emptyList()) }
    var followUpImageDragActive by remember(task.id) { mutableStateOf(false) }
    var goalEditorOpen by remember(task.id) { mutableStateOf(false) }
    var goalEditorText by remember(task.id) { mutableStateOf(task.goal.orEmpty()) }

    val supportsResume = task.vendorSessionId != null && task.agent != AgentKind.Antigravity
    val canSendFollowUp = followUp.isNotBlank() || followUpImagePaths.isNotEmpty()
    val slashCommand = findActiveSlashCommand(followUp)
    val matchingCommands = slashCommand?.let { command ->
        AgentNativeSlashCommands.forAgent(task.agent).filter { nativeCommand ->
            nativeCommand.name.contains(command.query, ignoreCase = true) ||
                nativeCommand.description.contains(command.query, ignoreCase = true)
        }
    }.orEmpty()
    val matchingSkills = slashCommand?.let { command ->
        availableSkills.filter { skill ->
            skill.name.contains(command.query, ignoreCase = true) ||
                skill.description.contains(command.query, ignoreCase = true)
        }.take(8)
    }.orEmpty()
    val selectedSkills = remember(followUp, availableSkills) {
        availableSkills.filter { skill -> followUp.referencesSkill(skill) }
    }

    fun selectSkill(skill: AgentSkill) {
        val command = findActiveSlashCommand(followUp) ?: return
        followUp = followUp.replaceRange(command.start, command.end, "/${skill.name} ")
        skillMenuDismissed = true
    }

    fun selectCommand(command: AgentNativeSlashCommand) {
        val slash = findActiveSlashCommand(followUp) ?: return
        followUp = followUp.replaceRange(slash.start, slash.end, "/${command.name} ")
        skillMenuDismissed = true
    }

    fun submitFollowUp() {
        if (!supportsResume || !canSendFollowUp) return
        fun sendOrQueue(message: String, skills: List<AgentSkill>) {
            if (task.isActive) {
                services.agentRuns.queueFollowUp(task.id, message, followUpImagePaths, skills)
            } else {
                services.agentRuns.resume(task.id, message, followUpImagePaths, skills)
            }
        }
        val goalCommand = if (AgentNativeSlashCommands.supportsGoal(task.agent)) followUp.parseAgentGoalCommand() else null
        if (goalCommand != null) {
            services.agentRuns.updateGoal(task.id, goalCommand.goal)
            val remainder = goalCommand.remainingPrompt
            if (remainder.isBlank()) {
                followUp = ""
                followUpImagePaths = emptyList()
                return
            }
            sendOrQueue(remainder, selectedSkills.filter { remainder.referencesSkill(it) })
        } else {
            sendOrQueue(followUp.trim(), selectedSkills)
        }
        followUp = ""
        followUpImagePaths = emptyList()
    }

    LaunchedEffect(task.id, task.status) {
        if (task.worktreePath != null) {
            diffSummary = services.agentRuns.worktreeDiffSummary(task.id)
        }
        changeSummary = if (task.isActive) null else task.completedChanges?.summary ?: services.agentRuns.changeSummary(task.id)
        expandedDiffPath = null
        loadedFileDiffs = task.completedChanges?.diffs.orEmpty()
        loadingDiffPath = null
    }
    LaunchedEffect(task.goal) {
        goalEditorText = task.goal.orEmpty()
        if (task.goal == null) goalEditorOpen = false
    }

    fun toggleFileDiff(path: String) {
        if (expandedDiffPath == path) {
            expandedDiffPath = null
            return
        }
        expandedDiffPath = path
        if (path in loadedFileDiffs) return
        loadingDiffPath = path
        scope.launch {
            val diff = services.agentRuns.fileDiff(task.id, path)
            if (diff != null) loadedFileDiffs = loadedFileDiffs + (path to diff)
            if (loadingDiffPath == path) loadingDiffPath = null
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        task.errorMessage?.let { error ->
            Text(error, color = app.andy.ui.theme.Red, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (task.status == AgentTaskStatus.Unknown) {
            Text(
                "interrupted by an app restart — continue interactively to pick the session back up",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }

        AgentTranscript(
            events,
            isActive = task.isActive,
            agentLabel = task.agent.label,
            headerContent = if (showHeader) {
                {
                    AgentTaskHeader(
                        task = task,
                        nowMillis = nowMillis,
                        onStop = { services.agentRuns.stop(task.id) },
                        onRetry = { scope.launch { services.agentRuns.retry(task.id) } },
                        onDelete = { onDelete(task) },
                        onCopyPrompt = { copyText(task.prompt) },
                    )
                }
            } else {
                null
            },
            originalPrompt = task.prompt,
            completedContent = {
                changeSummary?.takeIf { it.files.isNotEmpty() }?.let { summary ->
                    AgentChangeSummaryCard(
                        summary = summary,
                        showAllFiles = showAllChangedFiles,
                        onShowAllFilesChange = { showAllChangedFiles = it },
                        expandedPath = expandedDiffPath,
                        loadingPath = loadingDiffPath,
                        diffs = loadedFileDiffs,
                        viewMode = diffViewMode,
                        onViewModeChange = { diffViewMode = it },
                        onToggleFile = { path -> toggleFileDiff(path) },
                        embedded = true,
                    )
                }
            },
            onSkillOpen = { skill -> scope.launch { services.agentRuns.openSkill(skill.path) } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        if (task.queuedFollowUps.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4))
                    .border(1.dp, Cyan.copy(alpha = 0.6f), RoundedCornerShape(AndyRadius.R4))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("MESSAGE QUEUE · ${task.queuedFollowUps.size}", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                task.queuedFollowUps.forEachIndexed { index, queuedFollowUp ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", color = Cyan, fontFamily = MonoFont, fontSize = 11.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                queuedFollowUp.text.ifBlank { "images attached" },
                                color = TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (queuedFollowUp.skills.isNotEmpty()) {
                                Text(
                                    queuedFollowUp.skills.joinToString("  ") { "/${it.name}" },
                                    color = Cyan,
                                    fontFamily = MonoFont,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { services.agentRuns.removeQueuedFollowUp(task.id, index) },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                        ) { Text("remove", fontSize = 10.sp) }
                    }
                }
            }
        }

        if (task.status == AgentTaskStatus.Completed && task.planMode) {
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4))
                    .border(1.dp, Cyan.copy(alpha = 0.6f), RoundedCornerShape(AndyRadius.R4))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("PLAN COMPLETE", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                if (task.completedPlanText.isNullOrBlank()) {
                    Text(
                        "implementation unavailable — this older plan has no recoverable final response",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                } else {
                    Text(
                        "Start a fresh writable run with this plan and the original request.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Button(
                        onClick = { scope.launch { services.agentRuns.startImplementation(task.id) } },
                        colors = primaryButtonColors(),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) { Text("implement plan", fontSize = 11.sp) }
                }
            }
        }

        if (!task.isActive || supportsResume) {
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (supportsResume) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            task.goal?.let { goal ->
                                OutlinedButton(
                                    onClick = { goalEditorOpen = !goalEditorOpen },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text("GOAL MODE ACTIVE", color = Green, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                        Text(goal, color = TextPrimary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (goalEditorOpen) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3))
                                        .border(1.dp, Green.copy(alpha = 0.4f), RoundedCornerShape(AndyRadius.R3))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("persistent task goal", color = Green, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    TextField(
                                        goalEditorText,
                                        { goalEditorText = it },
                                        singleLine = false,
                                        minLines = 2,
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp),
                                        colors = fieldColors(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = {
                                                services.agentRuns.updateGoal(task.id, goalEditorText)
                                                goalEditorOpen = false
                                            },
                                            enabled = goalEditorText.isNotBlank(),
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 1.dp),
                                        ) { Text("save goal", fontSize = 10.sp) }
                                        OutlinedButton(
                                            onClick = { services.agentRuns.updateGoal(task.id, null) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 1.dp),
                                        ) { Text("clear goal", fontSize = 10.sp) }
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth()) {
                            TextField(
                                followUp,
                                {
                                    followUp = it
                                    skillMenuDismissed = false
                                },
                                singleLine = false,
                                minLines = 2,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 54.dp, max = 160.dp)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        if (event.key == Key.Tab && (matchingCommands.isNotEmpty() || matchingSkills.isNotEmpty())) {
                                            matchingCommands.firstOrNull()?.let(::selectCommand) ?: selectSkill(matchingSkills.first())
                                            return@onPreviewKeyEvent true
                                        }
                                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                                        if (event.isShiftPressed) return@onPreviewKeyEvent false
                                        if (canSendFollowUp) submitFollowUp()
                                        true
                                    }
                                    .onImageFilesDropped(
                                        onFiles = { dropped -> followUpImagePaths = (followUpImagePaths + dropped).distinct() },
                                        onDragActiveChange = { active -> followUpImageDragActive = active },
                                    )
                                    .border(
                                        if (followUpImageDragActive) 2.dp else 1.dp,
                                        if (followUpImageDragActive) Cyan else Border,
                                        RoundedCornerShape(AndyRadius.R3),
                                    ),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp),
                                colors = fieldColors(),
                                placeholder = {
                                    Text(
                                        if (followUpImageDragActive) {
                                            "release to attach image"
                                        } else if (task.isActive) {
                                            "next message — type / for ${task.agent.label} commands or skills, enter to queue"
                                        } else {
                                            "follow-up prompt — type / for ${task.agent.label} commands or skills, enter to send"
                                        },
                                        color = if (followUpImageDragActive) Cyan else TextSecondary,
                                        fontFamily = MonoFont,
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                            DropdownMenu(
                                expanded = slashCommand != null && !skillMenuDismissed,
                                onDismissRequest = { skillMenuDismissed = true },
                                modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                                properties = PopupProperties(focusable = false),
                            ) {
                                Text(
                                    if (matchingCommands.isEmpty() && matchingSkills.isEmpty()) {
                                        "no ${task.agent.label} commands or skills matching /${slashCommand?.query.orEmpty()}"
                                    } else {
                                        "${task.agent.label} commands and skills matching /${slashCommand?.query.orEmpty()}"
                                    },
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                                matchingCommands.forEach { command ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("/${command.name}", color = Green, fontFamily = MonoFont, fontSize = 12.sp)
                                                Text(command.description, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        },
                                        onClick = { selectCommand(command) },
                                    )
                                }
                                matchingSkills.forEach { skill ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("/${skill.name}", color = Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                                                skill.description.takeIf { it.isNotBlank() }?.let { description ->
                                                    Text(description, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        },
                                        onClick = { selectSkill(skill) },
                                    )
                                }
                            }
                        }
                        if (selectedSkills.isNotEmpty()) {
                            Text("selected skills", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                selectedSkills.forEach { skill ->
                                    FilterPill("/${skill.name} ×", true, Cyan) {
                                        followUp = followUp.removeSelectedSkill(skill)
                                    }
                                }
                            }
                        }
                        }
                        Button(
                            onClick = { submitFollowUp() },
                            enabled = canSendFollowUp,
                            colors = primaryButtonColors(),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text(if (task.isActive) "queue" else "send", fontSize = 11.sp) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = {
                            services.agentRuns.interactiveResumeCommand(task.id)?.let {
                                copyText(it)
                                copiedHint = true
                            }
                            scope.launch { services.agentRuns.openInTerminal(task.id) }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) { Text(if (copiedHint) "opened" else "terminal", fontSize = 11.sp) }
                }
                if (followUpImagePaths.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        followUpImagePaths.forEach { path ->
                            OutlinedButton(
                                onClick = { followUpImagePaths = followUpImagePaths.filterNot { it == path } },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                            ) { Text("${path.substringAfterLast('/').substringAfterLast('\\')} ×", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }

        if (task.worktreePath != null) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("worktree ${task.branchName.orEmpty()}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedButton(
                        onClick = { copyText(task.worktreePath.orEmpty()) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("copy path", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {
                            val branch = task.branchName ?: return@OutlinedButton
                            val originDir = task.originDir ?: return@OutlinedButton
                            copyText("git -C '$originDir' merge '$branch'")
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("copy merge cmd", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = { scope.launch { diffSummary = services.agentRuns.worktreeDiffSummary(task.id) } },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("refresh diff", fontSize = 10.sp) }
                }
                Text(
                    diffSummary ?: "loading diff…",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun AgentTaskHeader(
    task: AgentTask,
    nowMillis: Long,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopyPrompt: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentBadge(task.agent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${task.agent.label}  ${task.modelConfigurationLabel()}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
            if (task.isActive) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("stop", fontSize = 11.sp) }
            }
            if (task.status == AgentTaskStatus.Failed) {
                Button(
                    onClick = onRetry,
                    colors = primaryButtonColors(),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("retry", fontSize = 11.sp) }
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("delete", fontSize = 11.sp) }
        }

        Text(
            task.cwd ?: "no project context",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        task.goal?.let { goal ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Green.copy(alpha = 0.12f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Green.copy(alpha = 0.38f), RoundedCornerShape(AndyRadius.R3))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("GOAL MODE ACTIVE", color = Green, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(goal, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        AgentContextWindowIndicator(task)

        HorizontalDivider(color = Border)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCopyPrompt,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("copy prompt", fontSize = 10.sp) }
            Spacer(Modifier.weight(1f))
            formatElapsed(task.startedAtMillis, task.finishedAtMillis, nowMillis)?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
            formatCost(task.totalCostUsd, task.costIsEstimated)?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
        }
    }
}

/** Compact context-window status for the chat header; hidden until a provider reports it. */
@Composable
private fun AgentContextWindowIndicator(task: AgentTask) {
    val liveContext = task.contextTokens
    val turnInput = task.inputTokens
    val used = liveContext ?: turnInput
    val capacity = task.contextWindowTokens
    val fraction = used?.let { tokens ->
        capacity?.takeIf { it > 0 }?.let { (tokens.toFloat() / it).coerceIn(0f, 1f) }
    }
    val color = when {
        used == null -> TextSecondary
        fraction == null -> TextSecondary
        fraction >= 0.9f -> Red
        fraction >= 0.75f -> app.andy.ui.theme.Yellow
        else -> Cyan
    }
    val label = agentContextWindowLabel(task)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = color,
            fontFamily = MonoFont,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 1,
        )
        fraction?.let { progress ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(Border, RoundedCornerShape(AndyRadius.Pill)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(color, RoundedCornerShape(AndyRadius.Pill)),
                )
            }
        }
    }
}

internal fun agentContextWindowLabel(task: AgentTask): String {
    val liveContext = task.contextTokens
    val turnInput = task.inputTokens
    val used = liveContext ?: turnInput
    val capacity = task.contextWindowTokens
    val fraction = used?.let { tokens ->
        capacity?.takeIf { it > 0 }?.let { (tokens.toFloat() / it).coerceIn(0f, 1f) }
    }
    return buildString {
        when {
            used == null -> append("context · awaiting provider count")
            capacity == null || capacity <= 0 -> {
                append("context ")
                append(formatCompactTokenCount(used))
                append(" input · limit not reported")
            }
            else -> {
                append("context ")
                append(formatCompactTokenCount(used))
                append(" / ")
                append(formatCompactTokenCount(capacity))
                append(" · ")
                append((fraction!! * 100).toInt())
                append('%')
            }
        }
    }
}

private fun formatCompactTokenCount(value: Long): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}.${(value % 1_000_000) / 100_000}M"
    value >= 1_000 -> "${value / 1_000}.${(value % 1_000) / 100}k"
    else -> value.toString()
}

private data class SlashCommand(val start: Int, val end: Int, val query: String)

/** Finds a slash token only while the cursor is effectively at the end of the prompt. */
private fun findActiveSlashCommand(text: String): SlashCommand? {
    val match = Regex("(?:^|\\s)/([A-Za-z0-9:_-]*)$").find(text) ?: return null
    val tokenStart = match.range.first + if (match.value.startsWith('/') ) 0 else 1
    return SlashCommand(start = tokenStart, end = text.length, query = match.groupValues[1])
}

private fun String.referencesSkill(skill: AgentSkill): Boolean =
    Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)").containsMatchIn(this)

private fun String.removeSelectedSkill(skill: AgentSkill): String =
    replace(Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

@Composable
private fun AgentChangeSummaryCard(
    summary: AgentChangeSummary,
    showAllFiles: Boolean,
    onShowAllFilesChange: (Boolean) -> Unit,
    expandedPath: String?,
    loadingPath: String?,
    diffs: Map<String, AgentFileDiff>,
    viewMode: DiffViewMode,
    onViewModeChange: (DiffViewMode) -> Unit,
    onToggleFile: (String) -> Unit,
    embedded: Boolean = false,
) {
    val displayedFiles = if (showAllFiles) summary.files else summary.files.take(3)
    val remaining = summary.files.size - displayedFiles.size
    Column(
        Modifier.fillMaxWidth()
            .background(if (embedded) AndyColors.Neutral900.copy(alpha = 0.74f) else AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, if (embedded) Green.copy(alpha = 0.22f) else Border, RoundedCornerShape(AndyRadius.R3))
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("▣", color = Cyan, fontFamily = MonoFont, fontSize = 15.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    "Edited ${summary.files.size} ${if (summary.files.size == 1) "file" else "files"}",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+${summary.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                    Text("-${summary.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
        }
        displayedFiles.forEach { file ->
            // This interactive row is nested in the transcript's SelectionContainer.
            // Opt out so the file link keeps its hand cursor rather than a text cursor.
            DisableSelection {
                ChangedFileRow(
                    file = file,
                    expanded = expandedPath == file.path,
                    loading = loadingPath == file.path,
                    diff = diffs[file.path],
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    onToggle = { onToggleFile(file.path) },
                )
            }
        }
        if (remaining > 0 || showAllFiles) {
            OutlinedButton(
                onClick = { onShowAllFilesChange(!showAllFiles) },
                modifier = Modifier.padding(horizontal = 8.dp).height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
            ) {
                Text(if (showAllFiles) "show fewer files" else "show $remaining more files", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ChangedFileRow(
    file: AgentFileChange,
    expanded: Boolean,
    loading: Boolean,
    diff: AgentFileDiff?,
    viewMode: DiffViewMode,
    onViewModeChange: (DiffViewMode) -> Unit,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (expanded) "v" else ">",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.width(10.dp),
            )
            Text(
                file.path,
                color = Cyan,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand),
            )
            Text("+${file.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
            Text("-${file.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            when {
                loading && diff == null -> Text("loading diff…", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                diff == null -> Text("diff unavailable", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                else -> AgentFileDiffViewer(
                    diff = diff,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    onCollapse = onToggle,
                )
            }
        }
    }
}

@Composable
private fun AgentFileDiffViewer(
    diff: AgentFileDiff,
    viewMode: DiffViewMode,
    onViewModeChange: (DiffViewMode) -> Unit,
    onCollapse: () -> Unit,
) {
    var expandedContextBlocks by remember(diff.path) { mutableStateOf(setOf<Int>()) }
    val unifiedRows = remember(diff.lines, expandedContextBlocks) {
        buildDiffDisplayRows(diff.lines, expandedContextBlocks)
    }
    val splitRows = remember(diff.lines, expandedContextBlocks) {
        buildSplitDiffDisplayRows(buildSplitDiffPairs(diff.lines), expandedContextBlocks)
    }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Column(
        Modifier.fillMaxWidth()
            .background(AndyColors.Neutral900.copy(alpha = 0.85f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R2)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(AndyColors.Neutral850)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                diff.path,
                color = TextPrimary,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilterPill("unified", viewMode == DiffViewMode.Unified, Cyan) {
                onViewModeChange(DiffViewMode.Unified)
            }
            FilterPill("split", viewMode == DiffViewMode.Split, Cyan) {
                onViewModeChange(DiffViewMode.Split)
            }
            Text("+${diff.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
            Text("-${diff.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
            Text(
                "v",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onCollapse).padding(horizontal = 4.dp),
            )
        }
        when {
            diff.isBinary -> {
                Text(
                    "binary file changed",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            diff.lines.isEmpty() -> {
                Text(
                    "no line changes",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            else -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(verticalScroll),
                ) {
                    when (viewMode) {
                        DiffViewMode.Unified -> {
                            Column(Modifier.horizontalScroll(horizontalScroll).padding(bottom = 6.dp)) {
                                unifiedRows.forEach { row ->
                                    when (row) {
                                        is DiffDisplayRow.Collapsed -> CollapsedContextBar(
                                            count = row.lines.size,
                                            onToggle = {
                                                expandedContextBlocks = toggleContextBlock(expandedContextBlocks, row.id)
                                            },
                                        )
                                        is DiffDisplayRow.Line -> DiffCodeLine(row.line)
                                    }
                                }
                            }
                        }
                        DiffViewMode.Split -> {
                            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                splitRows.forEach { row ->
                                    when (row) {
                                        is SplitDisplayRow.Collapsed -> CollapsedContextBar(
                                            count = row.pairs.size,
                                            onToggle = {
                                                expandedContextBlocks = toggleContextBlock(expandedContextBlocks, row.id)
                                            },
                                        )
                                        is SplitDisplayRow.Pair -> SplitDiffCodeRow(row.pair)
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
private fun CollapsedContextBar(count: Int, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(AndyColors.Neutral850.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("^", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        Text(
            "$count unmodified ${if (count == 1) "line" else "lines"}",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        Text("v", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
    }
}

@Composable
private fun DiffCodeLine(line: DiffLine) {
    val background = when (line.kind) {
        DiffLineKind.Addition -> Green.copy(alpha = 0.14f)
        DiffLineKind.Deletion -> Red.copy(alpha = 0.16f)
        DiffLineKind.Context -> Color.Transparent
    }
    val gutter = when (line.kind) {
        DiffLineKind.Addition -> Green
        DiffLineKind.Deletion -> Red
        DiffLineKind.Context -> Color.Transparent
    }
    val textColor = when (line.kind) {
        DiffLineKind.Addition -> AndyColors.GreenSoft
        DiffLineKind.Deletion -> Red.copy(alpha = 0.92f)
        DiffLineKind.Context -> TextSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(gutter),
        )
        Text(
            line.oldLineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp).padding(start = 6.dp),
        )
        Text(
            line.newLineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp),
        )
        Text(
            when (line.kind) {
                DiffLineKind.Addition -> "+"
                DiffLineKind.Deletion -> "-"
                DiffLineKind.Context -> " "
            },
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun SplitDiffCodeRow(pair: SplitDiffPair) {
    Row(Modifier.fillMaxWidth()) {
        SplitDiffPane(
            line = pair.old,
            side = DiffSplitSide.Old,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(Border),
        )
        SplitDiffPane(
            line = pair.new,
            side = DiffSplitSide.New,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class DiffSplitSide { Old, New }

@Composable
private fun SplitDiffPane(
    line: DiffLine?,
    side: DiffSplitSide,
    modifier: Modifier = Modifier,
) {
    val kind = line?.kind
    val background = when {
        kind == DiffLineKind.Deletion -> Red.copy(alpha = 0.16f)
        kind == DiffLineKind.Addition -> Green.copy(alpha = 0.14f)
        line == null && side == DiffSplitSide.Old -> Green.copy(alpha = 0.06f)
        line == null && side == DiffSplitSide.New -> Red.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val gutter = when (kind) {
        DiffLineKind.Deletion -> Red
        DiffLineKind.Addition -> Green
        else -> Color.Transparent
    }
    val textColor = when (kind) {
        DiffLineKind.Deletion -> Red.copy(alpha = 0.92f)
        DiffLineKind.Addition -> AndyColors.GreenSoft
        DiffLineKind.Context -> TextSecondary
        null -> TextSecondary.copy(alpha = 0.35f)
    }
    val lineNumber = when (side) {
        DiffSplitSide.Old -> line?.oldLineNumber
        DiffSplitSide.New -> line?.newLineNumber
    }
    val marker = when (kind) {
        DiffLineKind.Deletion -> "-"
        DiffLineKind.Addition -> "+"
        DiffLineKind.Context -> " "
        null -> " "
    }
    Row(
        modifier
            .background(background)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(gutter),
        )
        Text(
            lineNumber?.toString().orEmpty(),
            color = TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            modifier = Modifier.width(36.dp).padding(start = 6.dp),
        )
        Text(
            marker,
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line?.text?.ifEmpty { " " } ?: " ",
            color = textColor,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private sealed class DiffDisplayRow {
    data class Line(val line: DiffLine) : DiffDisplayRow()
    data class Collapsed(val id: Int, val lines: List<DiffLine>) : DiffDisplayRow()
}

private sealed class SplitDisplayRow {
    data class Pair(val pair: SplitDiffPair) : SplitDisplayRow()
    data class Collapsed(val id: Int, val pairs: List<SplitDiffPair>) : SplitDisplayRow()
}

private fun toggleContextBlock(expanded: Set<Int>, id: Int): Set<Int> =
    if (id in expanded) expanded - id else expanded + id

private fun buildDiffDisplayRows(
    lines: List<DiffLine>,
    expandedContextBlocks: Set<Int>,
): List<DiffDisplayRow> {
    if (lines.isEmpty()) return emptyList()
    val rows = mutableListOf<DiffDisplayRow>()
    var index = 0
    var blockId = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.kind != DiffLineKind.Context) {
            rows += DiffDisplayRow.Line(line)
            index += 1
            continue
        }
        val start = index
        while (index < lines.size && lines[index].kind == DiffLineKind.Context) index += 1
        val block = lines.subList(start, index).toList()
        val id = blockId++
        if (id in expandedContextBlocks) {
            block.forEach { rows += DiffDisplayRow.Line(it) }
        } else {
            rows += DiffDisplayRow.Collapsed(id, block)
        }
    }
    return rows
}

private fun buildSplitDiffDisplayRows(
    pairs: List<SplitDiffPair>,
    expandedContextBlocks: Set<Int>,
): List<SplitDisplayRow> {
    if (pairs.isEmpty()) return emptyList()
    val rows = mutableListOf<SplitDisplayRow>()
    var index = 0
    var blockId = 0
    while (index < pairs.size) {
        if (!pairs[index].isContext) {
            rows += SplitDisplayRow.Pair(pairs[index])
            index += 1
            continue
        }
        val start = index
        while (index < pairs.size && pairs[index].isContext) index += 1
        val block = pairs.subList(start, index).toList()
        val id = blockId++
        if (id in expandedContextBlocks) {
            block.forEach { rows += SplitDisplayRow.Pair(it) }
        } else {
            rows += SplitDisplayRow.Collapsed(id, block)
        }
    }
    return rows
}
