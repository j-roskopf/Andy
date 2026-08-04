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
import androidx.compose.foundation.layout.fillMaxSize
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
import app.andy.ui.components.AndyHorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import app.andy.currentTimeMillis
import app.andy.domain.buildSplitDiffPairs
import app.andy.domain.SplitDiffPair
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentChangeSummary
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.AgentEvent
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentNativeSlashCommand
import app.andy.model.AgentNativeSlashCommands
import app.andy.model.AgentSkill
import app.andy.model.WorkspaceState
import app.andy.model.AgentTask
import app.andy.model.AgentStatus
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import app.andy.model.modelConfigurationLabel
import app.andy.model.parseAgentGoalCommand
import app.andy.model.shouldShowConnectionStallBanner
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.ChatImageAttachButton
import app.andy.ui.components.ChatSendButton
import app.andy.ui.components.FilterPill
import app.andy.service.OpenInvestigationRequest
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.shell.LocalOpenInvestigation
import app.andy.ui.components.StatusTag
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.TextField
import app.andy.ui.components.attachChatImages
import app.andy.ui.components.onChatImagePaste
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DiffViewMode { Unified, Split }

@Composable
internal fun AgentTaskDetail(
    services: AndyServices,
    task: AgentTask,
    onDelete: (AgentTask) -> Unit,
    showHeader: Boolean = true,
    transcriptScrollMemory: TranscriptScrollMemory? = null,
    workspaceState: WorkspaceState = WorkspaceState(),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    val skillDirectory = task.worktreePath ?: task.cwd
    val availableSkills by remember(task.agent, skillDirectory) {
        services.agentRuns.skills(task.agent, skillDirectory)
    }.collectAsState()
    val knownSkillNames by remember(skillDirectory) {
        services.agentRuns.knownSkillNames(skillDirectory)
    }.collectAsState()
    var followUp by remember(task.id) { mutableStateOf("") }
    var skillMenuDismissed by remember(task.id) { mutableStateOf(false) }
    var diffSummary by remember(task.id) { mutableStateOf<String?>(null) }
    var changeSummary by remember(task.id) { mutableStateOf<AgentChangeSummary?>(null) }
    var changedFilesExpanded by remember(task.id) { mutableStateOf(false) }
    var showAllChangedFiles by remember(task.id) { mutableStateOf(false) }
    var expandedDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var loadedFileDiffs by remember(task.id) { mutableStateOf<Map<String, AgentFileDiff>>(emptyMap()) }
    var loadingDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var diffViewMode by remember(task.id) { mutableStateOf(DiffViewMode.Unified) }
    var copiedHint by remember(task.id) { mutableStateOf(false) }
    var followUpImagePaths by remember(task.id) { mutableStateOf<List<String>>(emptyList()) }
    var followUpImageDragActive by remember(task.id) { mutableStateOf(false) }
    var scrollToLatestRequest by remember(task.id) { mutableStateOf(0) }
    var goalEditorOpen by remember(task.id) { mutableStateOf(false) }
    var goalEditorText by remember(task.id) { mutableStateOf(task.goal.orEmpty()) }
    LaunchedEffect(task.id, task.isActive, task.status) {
        if (!task.isActive) {
            changeSummary = task.completedChanges?.summary ?: services.agentRuns.changeSummary(task.id)
            loadedFileDiffs = task.completedChanges?.diffs.orEmpty()
            withFrameMillis { }
            withFrameMillis { }
        } else {
            changeSummary = null
            loadedFileDiffs = emptyMap()
        }
    }
    LaunchedEffect(task.id, task.status) {
        // While the live CLI owns the pane, skip worktree diff fetches — the card is hidden
        // then, and refetching only changed bottom height mid-turn.
        if (task.worktreePath != null && !task.isActive) {
            diffSummary = services.agentRuns.worktreeDiffSummary(task.id)
        }
        expandedDiffPath = null
        loadingDiffPath = null
    }

    val supportsResume = true
    val interactiveTerminalIds by services.agentRuns.interactiveTerminalTaskIds.collectAsState()
    val transcriptEvents by services.agentRuns.events(task.id).collectAsState()
    val acpSessionLive = services.agentRuns.isLaneLive(task.id)
    val acpTask = task.lane == AgentLaneKind.Acp
    // Live PTY can accept input directly — hide Andy's queue/follow-up field to avoid dual entry,
    // unless the user has staged images that should ship with the next composed message.
    // Gate on session interactivity only (not attachedTerminalIds): waiting on attach briefly
    // showed the composer and stole terminal height on resume.
    val terminalSessionActive = if (acpTask) {
        false
    } else {
        isChatTerminalInteractive(task, task.id in interactiveTerminalIds)
    }
    val sessionActive = if (acpTask) task.isActive || acpSessionLive else terminalSessionActive
    val showFollowUpComposer = if (acpTask) {
        supportsResume
    } else {
        supportsResume && showsChatFollowUpComposer(terminalSessionActive, followUpImagePaths.isNotEmpty())
    }
    val canSendFollowUp = followUp.isNotBlank() || followUpImagePaths.isNotEmpty()
    val slashCommand = findActiveSlashCommand(followUp)
    val allowedSkillNames = remember(availableSkills) {
        availableSkills.mapTo(linkedSetOf()) { it.name.trim().lowercase() }
    }
    val providerCommands = remember(transcriptEvents, knownSkillNames, allowedSkillNames) {
        transcriptEvents.asReversed()
            .filterIsInstance<AgentEvent.AvailableCommands>()
            .firstOrNull()
            ?.commands
            .orEmpty()
            .filter { command ->
                val name = command.name.trim().trimStart('/', '$').lowercase()
                name !in knownSkillNames || name in allowedSkillNames
            }
            .map { command -> AgentNativeSlashCommand(command.name, command.description) }
    }
    val availableCommands = remember(task.agent, providerCommands) {
        (AgentNativeSlashCommands.forAgent(task.agent) + providerCommands).distinctBy { it.name }
    }
    val availableAcpModes = remember(transcriptEvents) {
        transcriptEvents.asReversed().filterIsInstance<AgentEvent.AvailableModes>().firstOrNull()?.modes.orEmpty()
    }
    val currentAcpModeId = remember(transcriptEvents) {
        transcriptEvents.asReversed().firstNotNullOfOrNull { event ->
            when (event) {
                is AgentEvent.ModeChanged -> event.modeId
                is AgentEvent.AvailableModes -> event.currentModeId
                else -> null
            }
        }
    }
    var modeMenuExpanded by remember(task.id) { mutableStateOf(false) }
    val matchingCommands = slashCommand?.let { command ->
        availableCommands.filter { nativeCommand ->
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
    val showConnectionStallBanner = remember(transcriptEvents, task.isActive) {
        shouldShowConnectionStallBanner(transcriptEvents, task.isActive)
    }
    val slashHighlight = rememberComposerSlashHighlight(
        agent = task.agent,
        availableSkills = availableSkills,
    )

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
        scrollToLatestRequest++
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        task.errorMessage?.let { error ->
            Text(error, color = app.andy.ui.theme.Red, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (task.status == AgentStatus.Error) {
            Text(
                "interrupted by an app restart — retry for a fresh run, or continue interactively to pick the session back up",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        // Redundant while the live CLI is on screen — showing it steals height from the
        // terminal on every idle↔working flip.
        if (task.status == AgentStatus.Done && !terminalSessionActive) {
            Text(
                "done at prompt — continue interactively to send your next message",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        if (showHeader) {
            AgentTaskHeader(
                task = task,
                terminalLive = sessionActive,
                onStop = { services.agentRuns.stop(task.id) },
                onCompleteBuild = if (task.workflowStage == app.andy.model.ProjectWorkflowStage.Build && task.isActive) {
                    { services.agentRuns.completeWorkflowRun(task.id) }
                } else {
                    null
                },
                onRetry = { scope.launch { services.agentRuns.retry(task.id) } },
                onDelete = { onDelete(task) },
                onCopyPrompt = { copyText(task.prompt) },
            )
        }
        val provenance = task.provenance
        if (provenance?.investigationId != null) {
            val openInvestigation = LocalOpenInvestigation.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "launched from ${provenance.sourceKind.name} · investigation ${provenance.investigationId}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = {
                        openInvestigation(
                            OpenInvestigationRequest(
                                investigationId = provenance.investigationId,
                                eventId = provenance.eventId,
                                playbackMillis = provenance.playbackMillis,
                            ),
                        )
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("open investigation", fontSize = 10.sp) }
            }
        }
        task.userInputRequest?.let { request ->
            if (!acpTask) {
                AgentUserInputCard(
                    request = request,
                    onSubmit = { answers -> services.agentRuns.respondToUserInput(task.id, request.id, answers) },
                )
            }
        }
        if (showConnectionStallBanner) {
            ConnectionStallBanner(
                onRetry = {
                    scope.launch {
                        services.agentRuns.resume(
                            taskId = task.id,
                            followUp = CONNECTION_STALL_RETRY_PROMPT,
                        )
                    }
                },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 280.dp),
        ) {
            val terminalModifier = remember { Modifier.fillMaxSize() }
            val imagesStagedLatest = rememberUpdatedState(
                newValue = { staged: List<String> ->
                    followUpImagePaths = attachChatImages(followUpImagePaths, staged)
                },
            )
            val onImagesStaged = remember<(List<String>) -> Unit>(task.id) {
                { staged -> imagesStagedLatest.value(staged) }
            }
            if (acpTask) {
                val pendingPermissionId = task.userInputRequest
                    ?.takeIf { it.origin == AgentUserInputOrigin.AcpPermission }
                    ?.id
                AgentTranscript(
                    events = transcriptEvents,
                    isActive = task.isActive,
                    agentLabel = task.agent.cliName,
                    originalPrompt = task.prompt.ifBlank { task.title },
                    originalImagePaths = task.imagePaths,
                    restoreScrollKey = task.id,
                    scrollMemory = transcriptScrollMemory,
                    scrollToLatestRequest = scrollToLatestRequest,
                    autoExpandActivitySections = workspaceState.agentTranscriptAutoExpandActivity,
                    collapseActivityBetweenMessages = workspaceState.agentTranscriptCollapseActivityBlocks,
                    pendingContent = task.userInputRequest?.let { request ->
                        {
                            AgentUserInputCard(
                                request = request,
                                onSubmit = { answers ->
                                    services.agentRuns.respondToUserInput(task.id, request.id, answers)
                                },
                            )
                        }
                    },
                    trailingContent = changeSummary
                        ?.takeIf { it.files.isNotEmpty() }
                        ?.let { summary ->
                            {
                                AgentChangeSummaryCard(
                                    summary = summary,
                                    filesExpanded = changedFilesExpanded,
                                    onFilesExpandedChange = { changedFilesExpanded = it },
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
                    activePermissionRequestId = pendingPermissionId,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AgentTerminalSurface(
                    services = services,
                    taskId = task.id,
                    sessionActive = terminalSessionActive,
                    onImagesStaged = onImagesStaged,
                    modifier = terminalModifier,
                )
            }
        }
        if (!acpTask) {
            changeSummary?.takeIf { it.files.isNotEmpty() && !terminalSessionActive }?.let { summary ->
                AgentChangeSummaryCard(
                    summary = summary,
                    filesExpanded = changedFilesExpanded,
                    onFilesExpandedChange = { changedFilesExpanded = it },
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
        }

        if (task.queuedFollowUps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${task.queuedFollowUps.size} queued message${if (task.queuedFollowUps.size == 1) "" else "s"}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
                task.queuedFollowUps.forEachIndexed { index, queuedFollowUp ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
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
                                    color = Cyan.copy(alpha = 0.85f),
                                    fontFamily = MonoFont,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Text(
                            "Remove",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { services.agentRuns.removeQueuedFollowUp(task.id, index) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        if (showFollowUpComposer) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.Row))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (acpTask && availableAcpModes.isNotEmpty()) {
                    val currentMode = availableAcpModes.firstOrNull { it.id == currentAcpModeId } ?: availableAcpModes.first()
                    Box {
                        Text(
                            "mode: ${currentMode.name}",
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { modeMenuExpanded = true }
                                .padding(vertical = 2.dp),
                        )
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false },
                        ) {
                            availableAcpModes.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                mode.name,
                                                color = if (mode.id == currentMode.id) Green else TextPrimary,
                                                fontFamily = MonoFont,
                                                fontSize = 12.sp,
                                            )
                                            mode.description?.takeIf { it.isNotBlank() }?.let { description ->
                                                Text(description, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    },
                                    onClick = {
                                        modeMenuExpanded = false
                                        services.agentRuns.setAcpSessionMode(task.id, mode.id)
                                    },
                                )
                            }
                        }
                    }
                }
                if (task.userInputRequest == null) {
                    task.goal?.let { goal ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { goalEditorOpen = !goalEditorOpen }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Goal", color = Green.copy(alpha = 0.85f), fontFamily = MonoFont, fontSize = 10.sp)
                            Text(
                                goal,
                                color = TextPrimary,
                                fontFamily = MonoFont,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (goalEditorOpen) "⌄" else "›", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
                        }
                    }
                    if (goalEditorOpen) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Persistent task goal", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Save goal",
                                    color = if (goalEditorText.isNotBlank()) Cyan else TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(enabled = goalEditorText.isNotBlank()) {
                                            services.agentRuns.updateGoal(task.id, goalEditorText)
                                            goalEditorOpen = false
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                                Text(
                                    "Clear goal",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { services.agentRuns.updateGoal(task.id, null) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
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
                            minLines = 1,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = 180.dp)
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
                                .onChatImagePaste(scope) { added ->
                                    followUpImagePaths = attachChatImages(followUpImagePaths, added)
                                }
                                .onImageFilesDropped(
                                    onFiles = { dropped -> followUpImagePaths = attachChatImages(followUpImagePaths, dropped) },
                                    onDragActiveChange = { active -> followUpImageDragActive = active },
                                ),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp),
                            colors = fieldColors(),
                            chromeStyle = FieldChromeStyle.Borderless,
                            visualTransformation = slashHighlight,
                            placeholder = {
                                Text(
                                    when {
                                        followUpImageDragActive -> "release to attach images"
                                        terminalSessionActive && followUpImagePaths.isNotEmpty() ->
                                            "add a message — staged images send with it, enter to submit"
                                        else ->
                                            "follow-up prompt — attach, paste, or drag images; / for commands"
                                    },
                                    color = if (followUpImageDragActive) Cyan else TextSecondary.copy(alpha = 0.75f),
                                    fontSize = 14.sp,
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
                }

                if (task.userInputRequest == null && (selectedSkills.isNotEmpty() || followUpImagePaths.isNotEmpty())) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selectedSkills.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                selectedSkills.forEach { skill ->
                                    FilterPill("/${skill.name} ×", true, Cyan) {
                                        followUp = followUp.removeSelectedSkill(skill)
                                    }
                                }
                            }
                        }
                        if (followUpImagePaths.isNotEmpty()) {
                            ChatAttachedImages(
                                paths = followUpImagePaths,
                                onRemove = { path -> followUpImagePaths = followUpImagePaths.filterNot { it == path } },
                                maxWidth = 140.dp,
                                maxHeight = 100.dp,
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    ChatImageAttachButton(
                        onImagesAttached = { added ->
                            followUpImagePaths = attachChatImages(followUpImagePaths, added)
                        },
                    )
                    Text(
                        if (copiedHint) "Opened" else "Terminal",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                services.agentRuns.interactiveResumeCommand(task.id)?.let {
                                    copyText(it)
                                    copiedHint = true
                                }
                                scope.launch { services.agentRuns.openInTerminal(task.id) }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    if (task.userInputRequest == null) {
                        ChatSendButton(onClick = { submitFollowUp() }, enabled = canSendFollowUp)
                    }
                }
            }
        }

        // Same rule as change-summary: never steal height from a live terminal.
        if (task.worktreePath != null && !terminalSessionActive) {
            PanelCard(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp),
                background = AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium),
                contentPadding = PaddingValues(AndySpace.Space3),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
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
    terminalLive: Boolean,
    onStop: () -> Unit,
    onCompleteBuild: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopyPrompt: () -> Unit,
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val elapsedEnd = rememberElapsedEndMillis(
        taskId = task.id,
        finishedAtMillis = task.finishedAtMillis,
        task = task,
    )
    // Clock only while the expanded header actually shows a live elapsed string.
    var nowMillis by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(expanded, task.id, task.status, task.finishedAtMillis) {
        if (!expanded || !isElapsedLive(task)) return@LaunchedEffect
        while (true) {
            nowMillis = currentTimeMillis()
            delay(1_000)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { expanded = !expanded },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    task.prompt.ifBlank { task.title },
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${task.agent.label} · ${task.modelConfigurationLabel()}",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(agentStatusLabel(task), color = agentStatusColor(task.status), fontFamily = MonoFont, fontSize = 10.sp)
                    if (!terminalLive) {
                        Text("read-only", color = TextSecondary.copy(alpha = 0.75f), fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (terminalLive) {
                    onCompleteBuild?.let { complete ->
                        Text(
                            "Complete",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = complete)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        if (task.status == AgentStatus.Blocked) "Cancel" else "Stop",
                        color = Rust,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onStop)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (task.status == AgentStatus.Error) {
                    Text(
                        "Retry",
                        color = Cyan,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Text(
                    "Delete",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Text(
                    if (expanded) "⌄" else "›",
                    color = TextSecondary.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 2.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    task.cwd ?: "no project context",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                task.goal?.let { goal ->
                    Text(
                        "Goal: $goal",
                        color = Green.copy(alpha = 0.9f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AgentContextWindowIndicator(task)

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Copy prompt",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onCopyPrompt)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    formatElapsed(task.startedAtMillis, elapsedEnd, nowMillis)?.let {
                        Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                    formatCost(task.totalCostUsd, task.costIsEstimated)?.let {
                        Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Compact context-window status for the chat header; hidden until a provider reports it. */
@Composable
private fun AgentContextWindowIndicator(task: AgentTask) {
    val liveContext = task.contextTokens
    val turnInput = task.inputTokens
    val used = liveContext ?: turnInput ?: return
    val capacity = task.contextWindowTokens
    val fraction = capacity?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }
    val color = when {
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
    val used = liveContext ?: turnInput ?: return ""
    val capacity = task.contextWindowTokens
    val fraction = capacity?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }
    return buildString {
        when {
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
    filesExpanded: Boolean,
    onFilesExpandedChange: (Boolean) -> Unit,
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
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        background = if (embedded) AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium) else AndyColors.Neutral850,
        borderColor = if (embedded) Green.copy(alpha = 0.22f) else Border,
        contentPadding = PaddingValues(vertical = AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space4)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onFilesExpandedChange(!filesExpanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            Text(
                if (filesExpanded) "v" else ">",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.width(10.dp),
            )
            Text("▣", color = Cyan, fontFamily = MonoFont, fontSize = 15.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    "Edited ${summary.files.size} ${if (summary.files.size == 1) "file" else "files"}",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                    Text("+${summary.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                    Text("-${summary.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
        }
        if (filesExpanded) {
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

    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        background = AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
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
            .background(AndyColors.Neutral850.copy(alpha = AndyOverlay.Strong))
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
