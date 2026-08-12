package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.andy.HostCodeEditor
import app.andy.rememberCopyText
import app.andy.currentTimeMillis
import app.andy.domain.ToolCallFileContent
import app.andy.domain.diffFromToolCallFileContent
import app.andy.model.AgentMessageDeliveryMode
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentChangeSummary
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.IMPLEMENT_PLAN_PROMPT
import app.andy.model.AgentEvent
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentNativeSlashCommand
import app.andy.model.AgentNativeSlashCommands
import app.andy.model.composerSkillsForSlashMenu
import app.andy.model.mergedComposerSlashCommands
import app.andy.model.AgentSkill
import app.andy.model.HostFileDocument
import app.andy.model.HostFileSaveResult
import app.andy.model.HostSearchMode
import app.andy.model.ProjectWorkflowStage
import app.andy.model.WorkspaceState
import app.andy.model.AgentTask
import app.andy.model.AgentStatus
import app.andy.model.composerCommandToken
import app.andy.model.modelConfigurationLabel
import app.andy.model.parseAgentGoalCommand
import app.andy.model.latestPlanHasPendingEntries
import app.andy.model.looksLikePlanMode
import app.andy.model.shouldShowConnectionStallBanner
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.ChatComposerFrame
import app.andy.ui.components.ChatImageAttachButton
import app.andy.ui.components.ChatSendButton
import app.andy.ui.components.ChatVoiceDictationButton
import app.andy.ui.components.ComposerChip
import app.andy.ui.components.ComposerPlaceholderHint
import app.andy.ui.components.ComposerToolbarRow
import app.andy.ui.components.KeyCombo
import app.andy.ui.components.LocalOnOpenFileLink
import app.andy.ui.components.onVoiceDictationShortcut
import app.andy.ui.components.rememberVoiceDictationController
import app.andy.ui.components.FilterPill
import app.andy.service.OpenInvestigationRequest
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.shell.LocalOpenInvestigation
import app.andy.ui.components.StatusTag
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.TextField
import app.andy.ui.components.attachChatImages
import app.andy.ui.components.insertTextAtCursor
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

@Composable
internal fun AgentTaskDetail(
    services: AndyServices,
    task: AgentTask,
    onDelete: (AgentTask) -> Unit,
    showHeader: Boolean = true,
    /**
     * When false, omit delete/details from the in-pane header (e.g. project tab-row chrome
     * owns those actions). Expanded details can still be driven via [detailsExpanded].
     */
    showDeleteDetailsActions: Boolean = true,
    /** Null keeps expansion local to the header; non-null is controlled by the caller. */
    detailsExpanded: Boolean? = null,
    onDetailsExpandedChange: ((Boolean) -> Unit)? = null,
    transcriptScrollMemory: TranscriptScrollMemory? = null,
    workspaceState: WorkspaceState = WorkspaceState(),
    modifier: Modifier = Modifier,
    /** False while this pane is retained but not visible (e.g. under [RetainedDestination]). */
    dictationActive: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    val skillDirectory = task.worktreePath ?: task.cwd
    val availableSkills by remember(task.agent, skillDirectory) {
        services.agentRuns.skills(task.agent, skillDirectory)
    }.collectAsState()
    val providerSlashCommands by remember(task.agent, skillDirectory) {
        services.agentRuns.slashCommands(task.agent, skillDirectory)
    }.collectAsState()
    LaunchedEffect(task.agent, skillDirectory) {
        services.agentRuns.refreshSlashCommands(task.agent, skillDirectory)
    }
    var followUpValue by remember(task.id) { mutableStateOf(TextFieldValue("")) }
    var skillMenuDismissed by remember(task.id) { mutableStateOf(false) }
    var diffSummary by remember(task.id) { mutableStateOf<String?>(null) }
    var changeSummary by remember(task.id) { mutableStateOf<AgentChangeSummary?>(null) }
    var changedFilesExpanded by remember(task.id) { mutableStateOf(false) }
    var showAllChangedFiles by remember(task.id) { mutableStateOf(false) }
    var expandedDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var loadedFileDiffs by remember(task.id) { mutableStateOf<Map<String, AgentFileDiff>>(emptyMap()) }
    var loadingDiffPath by remember(task.id) { mutableStateOf<String?>(null) }
    var diffViewMode by remember(task.id) { mutableStateOf(DiffViewMode.Unified) }
    var toolDiffPane by remember(task.id) { mutableStateOf<AgentFileDiff?>(null) }
    var filePreviewPane by remember(task.id) { mutableStateOf<FileLinkPreviewState?>(null) }
    val fileLinkRoots = remember(task.worktreePath, task.cwd, task.originDir) {
        listOfNotNull(task.worktreePath, task.cwd, task.originDir).distinct()
    }
    var copiedHint by remember(task.id) { mutableStateOf(false) }
    var followUpImagePaths by remember(task.id) { mutableStateOf<List<String>>(emptyList()) }
    var followUpImageDragActive by remember(task.id) { mutableStateOf(false) }
    var voiceError by remember(task.id) { mutableStateOf<String?>(null) }
    val voiceController = rememberVoiceDictationController(
        voice = services.voiceDictation,
        onText = { spoken ->
            voiceError = null
            followUpValue = insertTextAtCursor(followUpValue, spoken)
        },
        onError = { voiceError = it },
        active = dictationActive,
    )
    val voiceShortcut = remember(workspaceState.voiceDictationShortcut) { KeyCombo.decode(workspaceState.voiceDictationShortcut) }
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
    val knownAgentTasks by services.agentRuns.tasks.collectAsState()
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
    val followUp = followUpValue.text
    val canSendFollowUp = followUp.isNotBlank() || followUpImagePaths.isNotEmpty()
    val queueMode = workspaceState.agentMessageDeliveryMode == AgentMessageDeliveryMode.Queue
    val slashCommand = findActiveSlashCommand(followUp)
    val sessionCommands = remember(transcriptEvents) {
        transcriptEvents.asReversed()
            .filterIsInstance<AgentEvent.AvailableCommands>()
            .firstOrNull()
            ?.commands
            .orEmpty()
    }
    val availableCommands = remember(task.agent, providerSlashCommands, sessionCommands) {
        mergedComposerSlashCommands(
            agent = task.agent,
            providerCommands = (providerSlashCommands + sessionCommands).distinctBy {
                it.name.trim().trimStart('/', '$').lowercase()
            },
        )
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
    val currentAcpMode = remember(availableAcpModes, currentAcpModeId) {
        availableAcpModes.firstOrNull { it.id == currentAcpModeId } ?: availableAcpModes.firstOrNull()
    }
    val acpPlanModeActive = currentAcpMode?.looksLikePlanMode() == true
    val planModeActive = task.planMode || acpPlanModeActive
    val hasPendingPlanEntries = remember(transcriptEvents) {
        latestPlanHasPendingEntries(transcriptEvents)
    }
    val awaitingPlanConfirmation = isAwaitingPlanConfirmation(
        task = task,
        planModeActive = planModeActive,
        hasPendingPlanEntries = hasPendingPlanEntries,
    )
    val slashMenuSkills = remember(availableSkills, availableCommands) {
        composerSkillsForSlashMenu(availableSkills, availableCommands)
    }
    val matchingCommands = slashCommand?.let { command ->
        availableCommands.filter { nativeCommand ->
            nativeCommand.name.contains(command.query, ignoreCase = true) ||
                nativeCommand.description.contains(command.query, ignoreCase = true)
        }
    }.orEmpty()
    val matchingSkills = slashCommand?.let { command ->
        slashMenuSkills.filter { skill ->
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
        availableCommands = availableCommands,
    )

    fun selectSkill(skill: AgentSkill) {
        val command = findActiveSlashCommand(followUp) ?: return
        val insertion = "/${skill.name} "
        followUpValue = TextFieldValue(
            text = followUp.replaceRange(command.start, command.end, insertion),
            selection = TextRange(command.start + insertion.length),
        )
        skillMenuDismissed = true
    }

    fun selectCommand(command: AgentNativeSlashCommand) {
        val slash = findActiveSlashCommand(followUp) ?: return
        val insertion = "${command.name.composerCommandToken()} "
        followUpValue = TextFieldValue(
            text = followUp.replaceRange(slash.start, slash.end, insertion),
            selection = TextRange(slash.start + insertion.length),
        )
        skillMenuDismissed = true
    }

    fun submitFollowUp() {
        if (!supportsResume || !canSendFollowUp) return
        fun sendOrQueue(message: String, skills: List<AgentSkill>) {
            when {
                queueMode && (task.isActive || task.queuedFollowUps.isNotEmpty()) ->
                    services.agentRuns.queueFollowUp(task.id, message, followUpImagePaths, skills)
                task.isActive ->
                    services.agentRuns.queueFollowUp(task.id, message, followUpImagePaths, skills)
                else ->
                    services.agentRuns.resume(task.id, message, followUpImagePaths, skills)
            }
        }
        val goalCommand = if (AgentNativeSlashCommands.supportsGoal(task.agent)) followUp.parseAgentGoalCommand() else null
        if (goalCommand != null) {
            services.agentRuns.updateGoal(task.id, goalCommand.goal)
            val remainder = goalCommand.remainingPrompt
            if (remainder.isBlank()) {
                followUpValue = TextFieldValue("")
                followUpImagePaths = emptyList()
                return
            }
            sendOrQueue(remainder, selectedSkills.filter { remainder.referencesSkill(it) })
        } else {
            sendOrQueue(followUp.trim(), selectedSkills)
        }
        followUpValue = TextFieldValue("")
        followUpImagePaths = emptyList()
        scrollToLatestRequest++
    }

    LaunchedEffect(task.goal) {
        goalEditorText = task.goal.orEmpty()
        if (task.goal == null) goalEditorOpen = false
    }

    fun openToolFile(content: ToolCallFileContent) {
        toolDiffPane = diffFromToolCallFileContent(content)
    }

    /** Handles a markdown link click that isn't a real web URL — opens it in Andy's own code viewer instead. */
    fun openFileLink(uri: String): Boolean {
        filePreviewPane = FileLinkPreviewState(requestedPath = uri, loading = true)
        scope.launch {
            filePreviewPane = resolveFileLink(services, fileLinkRoots, uri)
        }
        return true
    }

    fun saveFilePreview(path: String, text: String) {
        val current = filePreviewPane ?: return
        scope.launch {
            when (val result = services.hostFiles.save(path, text, current.document?.modifiedMillis ?: 0L)) {
                is HostFileSaveResult.Saved -> {
                    filePreviewPane = current.copy(
                        document = current.document?.copy(content = text, modifiedMillis = result.modifiedMillis),
                        draft = null,
                        error = null,
                    )
                }
                is HostFileSaveResult.Conflict -> {
                    // Keep the draft and error visible so the user's edits survive and a save can be retried.
                    filePreviewPane = current.copy(error = "Changed on disk since it was opened — not saved.")
                }
                is HostFileSaveResult.Failed -> {
                    filePreviewPane = current.copy(error = result.message)
                }
            }
        }
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

    CompositionLocalProvider(LocalOnOpenFileLink provides ::openFileLink) {
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
        if (showHeader) {
            AgentTaskHeader(
                task = task,
                planModeActive = planModeActive,
                hasPendingPlanEntries = hasPendingPlanEntries,
                terminalLive = sessionActive,
                showDeleteDetailsActions = showDeleteDetailsActions,
                detailsExpanded = detailsExpanded,
                onDetailsExpandedChange = onDetailsExpandedChange,
                onStop = { services.agentRuns.stop(task.id) },
                onCompleteBuild = if (task.workflowStage == ProjectWorkflowStage.Build && task.isActive) {
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
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentTranscript(
                        events = transcriptEvents,
                        isActive = task.isActive,
                        awaitingPlanConfirmation = awaitingPlanConfirmation,
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
                                    )
                                }
                            },
                        activePermissionRequestId = pendingPermissionId,
                        onToolFileOpen = ::openToolFile,
                        knownTasks = knownAgentTasks,
                        currentTaskId = task.id,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    filePreviewPane?.let { preview ->
                        FileLinkPreviewPane(
                            state = preview,
                            onTextChange = { _, text -> filePreviewPane = filePreviewPane?.copy(draft = text) },
                            onSave = ::saveFilePreview,
                            onClose = { filePreviewPane = null },
                            modifier = Modifier.width(420.dp).fillMaxHeight(),
                        )
                    } ?: toolDiffPane?.let { diff ->
                        AgentToolDiffSidePane(
                            diff = diff,
                            viewMode = diffViewMode,
                            onViewModeChange = { diffViewMode = it },
                            onClose = { toolDiffPane = null },
                            modifier = Modifier.width(420.dp).fillMaxHeight(),
                        )
                    }
                }
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
                )
            }
        }

        if (task.queuedFollowUps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${task.queuedFollowUps.size} queued message${if (task.queuedFollowUps.size == 1) "" else "s"}",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                    if (!task.isActive) {
                        Text(
                            "Send queued",
                            color = Green,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { services.agentRuns.sendNextQueuedFollowUp(task.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
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
        if (awaitingPlanConfirmation && showFollowUpComposer) {
            PlanApprovalCard(
                showImplementAction = task.workflowStage != ProjectWorkflowStage.Spec,
                onImplement = {
                    services.agentRuns.updatePlanMode(task.id, false)
                    services.agentRuns.resume(task.id, IMPLEMENT_PLAN_PROMPT)
                },
                onRefine = { feedback ->
                    if (task.isActive) {
                        services.agentRuns.queueFollowUp(task.id, feedback)
                    } else {
                        services.agentRuns.resume(task.id, feedback)
                    }
                },
            )
        }

        if (showFollowUpComposer) {
            ChatComposerFrame(
                modifier = Modifier.fillMaxWidth().onVoiceDictationShortcut(voiceShortcut, voiceController),
                highlighted = followUpImageDragActive,
            ) {
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
                            followUpValue,
                            {
                                followUpValue = it
                                skillMenuDismissed = false
                            },
                            singleLine = false,
                            minLines = 3,
                            maxLines = 7,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 94.dp, max = 180.dp)
                                .onVoiceDictationShortcut(voiceShortcut, voiceController)
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
                            textStyle = LocalTextStyle.current.copy(
                                color = TextPrimary,
                                fontFamily = DisplayFont,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                            colors = fieldColors(),
                            chromeStyle = FieldChromeStyle.Borderless,
                            visualTransformation = slashHighlight,
                            placeholder = {
                                ComposerPlaceholderHint(
                                    text = when {
                                        followUpImageDragActive -> "Release to attach images"
                                        followUpImagePaths.isNotEmpty() -> "Add a message, or send the attached images"
                                        awaitingPlanConfirmation -> "Refine the plan, or implement above"
                                        else -> "Message the agent, tag @files, or use /commands and /skills"
                                    },
                                    highlighted = followUpImageDragActive,
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
                                            Text(command.name.composerCommandToken(), color = Green, fontFamily = MonoFont, fontSize = 12.sp)
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
                                        followUpValue = TextFieldValue(followUp.removeSelectedSkill(skill))
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

                ComposerToolbarRow(
                    leading = {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComposerChip(
                                text = "Plan",
                                selected = planModeActive,
                                showChevron = false,
                                onClick = { services.agentRuns.updatePlanMode(task.id, !planModeActive) },
                            )
                            if (acpTask && availableAcpModes.isNotEmpty() && currentAcpMode != null) {
                                Box {
                                    ComposerChip(
                                        text = currentAcpMode.name,
                                        selected = true,
                                        onClick = { modeMenuExpanded = true },
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
                                                            color = if (mode.id == currentAcpMode.id) Green else TextPrimary,
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
                            ComposerChip(
                                text = if (copiedHint) "Opened" else "Terminal",
                                selected = false,
                                showChevron = false,
                                onClick = {
                                    services.agentRuns.interactiveResumeCommand(task.id)?.let {
                                        copyText(it)
                                        copiedHint = true
                                    }
                                    scope.launch { services.agentRuns.openInTerminal(task.id) }
                                },
                            )
                        }
                    },
                    trailing = {
                        ChatImageAttachButton(
                            onImagesAttached = { added ->
                                followUpImagePaths = attachChatImages(followUpImagePaths, added)
                            },
                        )
                        if (task.userInputRequest == null) {
                            ChatVoiceDictationButton(controller = voiceController)
                            ChatSendButton(onClick = { submitFollowUp() }, enabled = canSendFollowUp)
                        }
                    },
                )
                voiceError?.let { err ->
                    Text(err, color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
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
                            val parentPath = task.parentWorktreeTaskId?.let { parentId ->
                                services.agentRuns.tasks.value.firstOrNull { it.id == parentId }?.worktreePath
                            }
                            val targetDir = parentPath ?: task.originDir ?: return@OutlinedButton
                            copyText(services.agentRuns.mergeCommand(targetDir, branch))
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
}

/** One-shot request to preview a markdown file link, jumped to from assistant/tool text. */
private data class FileLinkPreviewState(
    val requestedPath: String,
    val loading: Boolean = true,
    val document: HostFileDocument? = null,
    /** Unsaved edits typed into the preview editor, kept across failed saves so they are never lost. */
    val draft: String? = null,
    val error: String? = null,
)

private fun isAbsoluteHostPath(path: String): Boolean =
    path.startsWith("/") || path.startsWith("~/") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(path)

/** Resolves a clicked markdown link against the task's project directories instead of the OS browser. */
private suspend fun resolveFileLink(
    services: AndyServices,
    roots: List<String>,
    uri: String,
): FileLinkPreviewState {
    val cleaned = uri.trim().removePrefix("file://").substringBefore('#')
    if (cleaned.isBlank()) {
        return FileLinkPreviewState(requestedPath = uri, loading = false, error = "Not a file link.")
    }
    val candidates = if (isAbsoluteHostPath(cleaned)) {
        listOf(cleaned)
    } else {
        roots.map { root -> root.trimEnd('/', '\\') + "/" + cleaned.trimStart('/', '\\') }
    }
    candidates.forEach { candidate ->
        runCatching { services.hostFiles.read(candidate) }.getOrNull()?.let { doc ->
            return FileLinkPreviewState(requestedPath = doc.path, loading = false, document = doc)
        }
    }
    val fileName = cleaned.substringAfterLast('/').substringAfterLast('\\')
    if (fileName.isNotBlank()) {
        val hit = runCatching {
            services.hostFiles.search(fileName, HostSearchMode.FileName, roots.ifEmpty { listOf(".") }, limit = 5)
                .firstOrNull { it.path.substringAfterLast('/').substringAfterLast('\\') == fileName }
        }.getOrNull()
        if (hit != null) {
            runCatching { services.hostFiles.read(hit.path) }.getOrNull()?.let { doc ->
                return FileLinkPreviewState(requestedPath = doc.path, loading = false, document = doc)
            }
        }
    }
    return FileLinkPreviewState(requestedPath = cleaned, loading = false, error = "Could not find \"$cleaned\" in this task's project.")
}

/** Embedded read/write preview of a host source file jumped to from a chat markdown link. */
@Composable
private fun FileLinkPreviewPane(
    state: FileLinkPreviewState,
    onTextChange: (path: String, text: String) -> Unit,
    onSave: (path: String, text: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier,
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Code",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    state.requestedPath.substringAfterLast('/').ifBlank { state.requestedPath },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onClose) { Text("Close", fontSize = 11.sp) }
        }
        when {
            state.loading -> Text(
                "Loading…",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            state.document != null -> Column(Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    Text(
                        error,
                        color = Red,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                HostCodeEditor(
                    path = state.document.path,
                    text = state.draft ?: state.document.content,
                    languageHint = state.document.languageHint,
                    modifier = Modifier.fillMaxSize(),
                    onTextChange = onTextChange,
                    onSave = onSave,
                    onClose = onClose,
                )
            }
            state.error != null -> Text(
                state.error,
                color = Red,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun AgentTaskHeader(
    task: AgentTask,
    planModeActive: Boolean,
    hasPendingPlanEntries: Boolean,
    terminalLive: Boolean,
    showDeleteDetailsActions: Boolean = true,
    detailsExpanded: Boolean? = null,
    onDetailsExpandedChange: ((Boolean) -> Unit)? = null,
    onStop: () -> Unit,
    onCompleteBuild: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopyPrompt: () -> Unit,
) {
    var localExpanded by remember(task.id) { mutableStateOf(false) }
    val expanded = detailsExpanded ?: localExpanded
    fun setExpanded(value: Boolean) {
        if (onDetailsExpandedChange != null) onDetailsExpandedChange(value) else localExpanded = value
    }
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
    val statusColor = agentStatusColor(task, planModeActive, hasPendingPlanEntries)
    val statusLabel = agentStatusLabel(task, planModeActive, hasPendingPlanEntries)
    val hasSessionActions = terminalLive || task.status == AgentStatus.Error
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { setExpanded(!expanded) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    task.prompt.ifBlank { task.title },
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${task.agent.label} · ${task.modelConfigurationLabel()}",
                        color = TextSecondary.copy(alpha = 0.78f),
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!terminalLive) {
                        Text("read-only", color = TextSecondary.copy(alpha = 0.62f), fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }
            StatusTag(statusLabel, statusColor)
        }

        if (hasSessionActions || showDeleteDetailsActions) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                if (terminalLive) {
                    onCompleteBuild?.let { complete ->
                        AgentHeaderAction("complete", Green, complete)
                    }
                    AgentHeaderAction(
                        label = if (task.status == AgentStatus.Blocked) "cancel" else "stop",
                        color = Rust,
                        onClick = onStop,
                    )
                }
                if (task.status == AgentStatus.Error) {
                    AgentHeaderAction("retry", Cyan, onRetry)
                }
                if (showDeleteDetailsActions) {
                    Spacer(Modifier.weight(1f))
                    AgentHeaderAction("delete", TextSecondary, onDelete)
                    AgentHeaderAction(
                        label = if (expanded) "hide details" else "details",
                        onClick = { setExpanded(!expanded) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                AgentHeaderInfoRow(
                    label = "workspace",
                    value = task.cwd ?: "no project context",
                )

                task.goal?.let { goal ->
                    AgentHeaderInfoRow(
                        label = "goal",
                        value = goal,
                        valueColor = Green.copy(alpha = 0.9f),
                    )
                }

                AgentContextWindowIndicator(task)

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AgentHeaderAction("copy prompt", TextSecondary, onCopyPrompt)
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

@Composable
internal fun AgentHeaderAction(
    label: String,
    color: Color = TextSecondary,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color.copy(alpha = 0.88f),
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
    )
}

@Composable
private fun AgentHeaderInfoRow(
    label: String,
    value: String,
    valueColor: Color = TextSecondary,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Text(
            label,
            color = TextSecondary.copy(alpha = 0.62f),
            fontFamily = MonoFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            modifier = Modifier.width(58.dp),
        )
        Text(
            value,
            color = valueColor,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
) {
    val displayedFiles = if (showAllFiles) summary.files else summary.files.take(3)
    val remaining = summary.files.size - displayedFiles.size
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space4)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onFilesExpandedChange(!filesExpanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Text(
                if (filesExpanded) "v" else ">",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.width(10.dp),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Edited ${summary.files.size} ${if (summary.files.size == 1) "file" else "files"}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                    Text("+${summary.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                    Text("-${summary.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
        }
        if (filesExpanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 22.dp),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
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
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Text(if (showAllFiles) "show fewer files" else "show $remaining more files", fontSize = 10.sp)
                    }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
