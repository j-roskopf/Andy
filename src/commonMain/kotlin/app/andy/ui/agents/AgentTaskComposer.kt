package app.andy.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.git_branch
import app.andy.model.ActionProject
import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentNativeSlashCommand
import app.andy.model.AgentNativeSlashCommands
import app.andy.model.AgentPickerOption
import app.andy.model.LocalAgentRuntime
import app.andy.model.agentPickerOptions
import app.andy.model.comboReady
import app.andy.model.isLocalModelBackend
import app.andy.model.prefixedLocalModelId
import app.andy.model.runtimeKind
import app.andy.model.composerSkillsForSlashMenu
import app.andy.model.mergedComposerSlashCommands
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTaskDraft
import app.andy.model.withImportedVendorSession
import app.andy.model.WorktreeBaseOption
import app.andy.model.WorkspaceState
import app.andy.model.composerCommandName
import app.andy.model.composerCommandToken
import app.andy.model.defaultSandboxMode
import app.andy.model.groupedByModelFamily
import app.andy.model.hasAvailableAgentProvider
import app.andy.model.HostSearchResult
import app.andy.model.labelFor
import app.andy.model.parseAgentGoalCommand
import app.andy.onImageFilesDropped
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.ui.components.ChatComposerFrame
import app.andy.ui.components.ChatImageAttachButton
import app.andy.ui.components.ChatSendButton
import app.andy.ui.components.ChatVoiceDictationButton
import app.andy.ui.components.ComposerChip
import app.andy.ui.components.ComposerPlaceholderHint
import app.andy.ui.components.ComposerToolbarRow
import app.andy.ui.components.HoverTooltip
import app.andy.ui.components.KeyCombo
import app.andy.ui.components.onVoiceDictationShortcut
import app.andy.ui.components.rememberVoiceDictationController
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.attachChatImages
import app.andy.ui.components.insertTextAtCursor
import app.andy.ui.components.onChatImagePaste
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AgentTaskComposerPane(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject?,
    onSubmit: (AgentTaskDraft) -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    workspaceState: WorkspaceState = WorkspaceState(),
    /** False while this pane is retained but not visible (e.g. under [RetainedDestination]). */
    dictationActive: Boolean = true,
    initialPrompt: String? = null,
    wrapComposerControls: Boolean = false,
) {
    val form = rememberAgentTaskComposerForm(services, cliStatuses, projectContext)
    val copyText = rememberCopyText()
    val scope = rememberCoroutineScope()
    var importingThread by remember { mutableStateOf(false) }
    LaunchedEffect(dictationActive) {
        if (!dictationActive) importingThread = false
    }
    CollectChatComposerInbox(active = dictationActive) { item ->
        val (text, images) = applyChatComposerAttachment(form.state.promptValue, form.state.imagePaths, item)
        form.state.promptValue = text
        form.state.imagePaths = images
    }
    LaunchedEffect(projectContext?.id, initialPrompt) {
        if (form.state.promptValue.text.isBlank() && !initialPrompt.isNullOrBlank()) {
            form.state.promptValue = TextFieldValue(
                initialPrompt,
                TextRange(initialPrompt.length),
            )
        }
    }
    if (importingThread) {
        ImportThreadFromProviderPane(
            initialAgent = form.state.agent,
            cliStatuses = cliStatuses,
            onBack = { importingThread = false },
            onCancel = {
                importingThread = false
                onCancel?.invoke()
            },
            onImport = { agent, sessionId ->
                form.state.agent = agent
                if (!agent.isLocalModelBackend) form.state.localRuntime = null
                importingThread = false
                onSubmit(form.buildDraft().withImportedVendorSession(sessionId))
                form.clearPrompt()
            },
            modifier = modifier,
        )
        return
    }
    Box(modifier.fillMaxSize().background(AndyColors.ContentBg)) {
        val backgroundUri = workspaceState.newChatBackgroundUri
        if (backgroundUri.isNotBlank()) {
            NewChatBackground(uri = backgroundUri, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(
                        if (projectContext != null) {
                            Modifier.padding(horizontal = AndySpace.Space4)
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (form.hasAvailableProvider) {
                    AgentMark(form.state.agent)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (form.hasAvailableProvider) {
                        projectContext?.let { "What do you want to work on in ${it.name}?" }
                            ?: "What can I help you with?"
                    } else {
                        "No chat providers are available"
                    },
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (form.hasAvailableProvider) {
                        "Import thread from provider"
                    } else {
                        "Install a supported provider CLI, then refresh the check to start a chat."
                    },
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 13.sp,
                    modifier = if (form.hasAvailableProvider) {
                        Modifier.clickable(role = Role.Button) { importingThread = true }
                    } else {
                        Modifier
                    },
                )
            }
            AgentCliIssueNotices(
                statuses = cliStatuses,
                onCopyRepairCommand = copyText,
                onRefresh = { scope.launch { services.agentRuns.refreshCliStatuses() } },
            )
            AgentChatComposer(
                form = form,
                wrapComposerControls = wrapComposerControls,
                onCancel = onCancel,
                voiceShortcut = remember(workspaceState.voiceDictationShortcut) { KeyCombo.decode(workspaceState.voiceDictationShortcut) },
                dictationActive = dictationActive,
                onSubmit = {
                    if (form.canSubmit) {
                        onSubmit(form.buildDraft())
                        form.clearPrompt()
                    }
                },
            )
        }
    }
}

@Composable
private fun AgentCliIssueNotices(
    statuses: List<AgentCliStatus>,
    onCopyRepairCommand: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        statuses.mapNotNull { status -> status.issue?.let { status to it } }.forEach { (status, issue) ->
            PanelCard(
                modifier = Modifier.fillMaxWidth(),
                background = AndyColors.OrangeSubtle,
                borderColor = AndyColors.OrangeBorder.copy(alpha = 0.65f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "${status.kind.label}: ${issue.title}",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(issue.detail, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    issue.repairCommand?.let { command ->
                        OutlinedButton(onClick = { onCopyRepairCommand(command) }) {
                            Text("copy repair command", fontSize = 10.sp)
                        }
                    }
                    OutlinedButton(onClick = onRefresh) { Text("refresh check", fontSize = 10.sp) }
                }
            }
        }
    }
}

private class AgentTaskComposerFormState(
    initialAgent: AgentKind,
) {
    var promptValue by mutableStateOf(TextFieldValue(""))
    var skillMenuDismissed by mutableStateOf(false)
    var imagePaths by mutableStateOf<List<String>>(emptyList())
    var imageDragActive by mutableStateOf(false)
    var agent by mutableStateOf(initialAgent)
    var localRuntime by mutableStateOf<LocalAgentRuntime?>(null)
    var providerChosenInComposer by mutableStateOf(false)
    var customDirectory by mutableStateOf("")
    var usesCustomDirectory by mutableStateOf(false)
    var useWorktree by mutableStateOf(false)
    var attachMcp by mutableStateOf(false)
    var autonomy by mutableStateOf(AgentAutonomy.Standard)
    var sandboxMode by mutableStateOf<AgentSandboxMode?>(null)
    var planMode by mutableStateOf(false)
    var confirmToolCalls by mutableStateOf(false)
    var modelId by mutableStateOf<String?>(null)
    var customModel by mutableStateOf("")
    var reasoningEffort by mutableStateOf<AgentReasoningEffort?>(null)
    var fastMode by mutableStateOf(false)
    var openClawNewSession by mutableStateOf(true)
    var budgetText by mutableStateOf("")
    var directoryIsGitRepo by mutableStateOf(false)
    /**
     * Ephemeral chat. Deliberately not part of [applyProviderDefaults]: a sticky value would
     * make the next ordinary chat evaporate on close, and nothing was written to recover.
     */
    var temporary by mutableStateOf(false)
    var currentBranch by mutableStateOf<String?>(null)
    var baseWorktreeTaskId by mutableStateOf<String?>(null)
    var availableBases by mutableStateOf<List<WorktreeBaseOption>>(emptyList())
    /** Last agent whose provider defaults were seeded into this draft; avoids clobbering restored drafts. */
    var defaultsSeededForAgent: AgentKind? = null

    val prompt: String get() = promptValue.text
    val usesCustomModel: Boolean get() = modelId == ComposerCustomModelId

    fun clearPrompt() {
        promptValue = TextFieldValue("")
        imagePaths = emptyList()
        skillMenuDismissed = false
        // Off for every new chat: carrying it over would silently make the next ordinary chat
        // vanish on close, and nothing was persisted to get it back.
        temporary = false
    }

    fun applyProviderDefaults(defaults: AgentProviderDefaults?, agent: AgentKind, discovered: Map<AgentKind, List<AgentModelOption>> = emptyMap()) {
        // Pi model ids are always provider/model; drop sticky bare provider names from bad probes.
        val selection = composerModelSelection(agent, defaults?.model, discovered)
        modelId = selection.modelId
        customModel = selection.customModel
        reasoningEffort = defaults?.reasoningEffort
        fastMode = defaults?.fastMode == true
        openClawNewSession = defaults?.openClawNewSession ?: true
        autonomy = defaults?.autonomy ?: AgentAutonomy.Standard
        // Leave the sandbox unset unless it was explicitly saved. This lets the
        // provider derive it from whichever autonomy level the user chooses.
        sandboxMode = defaults?.sandboxMode
        planMode = defaults?.planMode == true
        confirmToolCalls = defaults?.confirmToolCalls == true
        useWorktree = defaults?.useWorktree == true
        attachMcp = defaults?.attachAndyMcp == true
        budgetText = defaults?.maxBudgetUsd?.toString().orEmpty()
        localRuntime = when {
            !agent.isLocalModelBackend -> null
            else -> localRuntime ?: defaults?.localRuntime ?: LocalAgentRuntime.OpenCode
        }
    }
}

@Composable
private fun rememberAgentTaskComposerForm(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject?,
): AgentTaskComposerForm {
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()
    val providerModels by services.agentRuns.providerModels.collectAsState()
    val lastUsedAgent by services.agentRuns.lastUsedAgent.collectAsState()
    val localBackends by services.agentRuns.localModelBackends.collectAsState()
    val scope = rememberCoroutineScope()
    val formsByProject = remember { mutableMapOf<String?, AgentTaskComposerFormState>() }
    val projectKey = projectContext?.id
    val state = formsByProject.getOrPut(projectKey) {
        AgentTaskComposerFormState(cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode)
    }

    val directory = projectContext?.contextDir
        ?: state.customDirectory.takeIf { state.usesCustomDirectory && it.isNotBlank() }
    val runtimeKind = state.agent.runtimeKind(state.localRuntime)
    val availableSkills by remember(runtimeKind, directory) {
        services.agentRuns.skills(runtimeKind, directory)
    }.collectAsState()
    val providerSlashCommands by remember(runtimeKind, directory) {
        services.agentRuns.slashCommands(runtimeKind, directory)
    }.collectAsState()
    val availableCommands = remember(runtimeKind, providerSlashCommands) {
        mergedComposerSlashCommands(runtimeKind, providerSlashCommands)
    }
    LaunchedEffect(runtimeKind, directory) {
        services.agentRuns.refreshSlashCommands(runtimeKind, directory)
    }
    val selectedOption = AgentPickerOption(state.agent, state.localRuntime.takeIf { state.agent.isLocalModelBackend })
    val selectedCliAvailable = selectedOption.comboReady(cliStatuses, localBackends)
    // An empty status list means discovery has not completed yet, so preserve the optimistic
    // initial composer state. Once discovery has reported every option unavailable, do not
    // present the fallback agent as a usable selection.
    val hasAvailableProvider = hasAvailableAgentProvider(cliStatuses, localBackends)
    val modelOptions = AgentModelCatalog.options(state.agent, providerModels)
    val selectedModel = AgentModelCatalog.option(state.agent, state.modelId, providerModels)
    val slashCommand = findComposerSlashCommand(state.prompt)
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
    val fileMention = if (slashCommand == null && services.capabilities.hostAutomation) {
        findComposerFileMention(state.prompt)
    } else {
        null
    }
    val mentionResults = composerFileMentionResults(
        query = fileMention?.query,
        hostFiles = services.hostFiles,
        roots = listOfNotNull(directory),
    )
    val selectedSkills = remember(state.prompt, availableSkills) {
        availableSkills.filter { skill -> state.prompt.referencesComposerSkill(skill) }
    }
    val validBudget = state.budgetText.toMaxBudgetUsd()
    val localModelChosen = !state.agent.isLocalModelBackend ||
        (state.localRuntime != null && (
            (state.usesCustomModel && state.customModel.isNotBlank()) ||
                (!state.usesCustomModel && !state.modelId.isNullOrBlank())
            ))
    val canSubmit = (state.prompt.isNotBlank() || state.imagePaths.isNotEmpty()) &&
        (!state.usesCustomModel || state.customModel.isNotBlank()) &&
        (state.budgetText.isBlank() || validBudget != null) &&
        selectedCliAvailable &&
        localModelChosen &&
        hasAvailableProvider

    LaunchedEffect(lastUsedAgent, cliStatuses, localBackends, projectKey, providerDefaults) {
        if (!state.providerChosenInComposer) {
            val preferred = lastUsedAgent
            val preferredRuntime = preferred?.let { kind ->
                providerDefaults[kind]?.localRuntime
                    ?: LocalAgentRuntime.OpenCode.takeIf { kind.isLocalModelBackend }
            }
            val preferredOption = preferred?.let { AgentPickerOption(it, preferredRuntime) }
            if (preferredOption != null && preferredOption.comboReady(cliStatuses, localBackends)) {
                state.agent = preferredOption.agent
                state.localRuntime = preferredOption.localRuntime
                state.providerChosenInComposer = true
            } else {
                val fallback = agentPickerOptions().firstOrNull { it.comboReady(cliStatuses, localBackends) }
                state.agent = fallback?.agent ?: AgentKind.ClaudeCode
                state.localRuntime = fallback?.localRuntime
            }
        }
    }

    // Apply defaults when the agent changes, or once for a newly created project draft.
    // Do not re-apply merely because we navigated back to an existing draft.
    LaunchedEffect(state, state.agent, providerDefaults[state.agent], providerModels) {
        val agent = state.agent
        val defaults = providerDefaults[agent]
        if (state.defaultsSeededForAgent != agent) {
            state.applyProviderDefaults(defaults, agent, providerModels)
            state.defaultsSeededForAgent = agent
        } else {
            val next = composerModelSelectionAfterCatalogUpdate(
                ComposerModelSelection(state.modelId, state.customModel),
                agent,
                defaults?.model,
                providerModels,
            )
            if (next.modelId != state.modelId || next.customModel != state.customModel) {
                state.modelId = next.modelId
                state.customModel = next.customModel
                state.reasoningEffort = defaults?.reasoningEffort
                state.fastMode = defaults?.fastMode == true
            }
        }
    }

    LaunchedEffect(directory, state.useWorktree) {
        state.directoryIsGitRepo = directory?.let { services.agentRuns.isGitRepo(it) } == true
        if (!state.directoryIsGitRepo) {
            state.useWorktree = false
            state.currentBranch = null
            state.availableBases = emptyList()
            state.baseWorktreeTaskId = null
            return@LaunchedEffect
        }
        state.currentBranch = directory?.let { services.agentRuns.currentBranch(it) }
        state.availableBases = if (state.useWorktree) {
            directory?.let { services.agentRuns.worktreeBaseOptions(it) }.orEmpty()
        } else {
            emptyList()
        }
        if (state.baseWorktreeTaskId != null && state.availableBases.none { it.taskId == state.baseWorktreeTaskId }) {
            state.baseWorktreeTaskId = null
        }
    }
    LaunchedEffect(state.agent, state.modelId, selectedModel) {
        val model = selectedModel
        when {
            state.usesCustomModel || model == null || model.efforts.isEmpty() -> state.reasoningEffort = null
            state.reasoningEffort !in model.efforts -> {
                // Cursor encodes effort in the model slug; leaving it unset yields invalid ids like cursor-grok-4.5.
                state.reasoningEffort = if (state.agent == AgentKind.Cursor) model.preferredEffort() else null
            }
        }
        if (model?.supportsFastMode != true) {
            state.fastMode = false
        } else if (model.fastRequired) {
            state.fastMode = true
        }
    }

    return AgentTaskComposerForm(
        state = state,
        services = services,
        cliStatuses = cliStatuses,
        localBackends = localBackends,
        providerModels = providerModels,
        modelOptions = modelOptions,
        projectContext = projectContext,
        directory = directory,
        selectedModel = selectedModel,
        availableSkills = availableSkills,
        availableCommands = availableCommands,
        slashCommand = slashCommand,
        matchingCommands = matchingCommands,
        matchingSkills = matchingSkills,
        fileMention = fileMention,
        mentionResults = mentionResults,
        selectedSkills = selectedSkills,
        hasAvailableProvider = hasAvailableProvider,
        canSubmit = canSubmit,
        scope = scope,
    )
}

private class AgentTaskComposerForm(
    val state: AgentTaskComposerFormState,
    val services: AndyServices,
    val cliStatuses: List<AgentCliStatus>,
    val localBackends: Map<AgentKind, Boolean>,
    val providerModels: Map<AgentKind, List<AgentModelOption>>,
    val modelOptions: List<AgentModelOption>,
    val projectContext: ActionProject?,
    val directory: String?,
    val selectedModel: AgentModelOption?,
    val availableSkills: List<AgentSkill>,
    val availableCommands: List<AgentNativeSlashCommand>,
    val slashCommand: ComposerSlashCommand?,
    val matchingCommands: List<AgentNativeSlashCommand>,
    val matchingSkills: List<AgentSkill>,
    val fileMention: ComposerFileMention?,
    val mentionResults: List<HostSearchResult>,
    val selectedSkills: List<AgentSkill>,
    val hasAvailableProvider: Boolean,
    val canSubmit: Boolean,
    val scope: CoroutineScope,
) {
    fun clearPrompt() = state.clearPrompt()

    fun buildDraft(): AgentTaskDraft {
        val goalCommand = state.prompt.takeIf { AgentNativeSlashCommands.supportsGoal(state.agent) }?.parseAgentGoalCommand()
        return AgentTaskDraft(
            title = "",
            prompt = goalCommand?.remainingPrompt?.ifBlank { goalCommand.goal.orEmpty() } ?: state.prompt.trim(),
            agent = state.agent,
            localRuntime = state.localRuntime,
            projectId = projectContext?.id,
            directory = directory?.trim()?.takeIf { it.isNotBlank() },
            useWorktree = state.useWorktree,
            baseWorktreeTaskId = state.baseWorktreeTaskId,
            attachAndyMcp = state.attachMcp,
            autonomy = state.autonomy,
            sandboxMode = state.sandboxMode,
            planMode = state.planMode,
            confirmToolCalls = state.confirmToolCalls,
            model = (if (state.usesCustomModel) state.customModel.trim().ifBlank { null } else state.modelId)
                ?.let { if (state.agent.isLocalModelBackend) prefixedLocalModelId(state.agent, it) else it },
            reasoningEffort = if (state.usesCustomModel) null else state.reasoningEffort,
            fastMode = if (state.usesCustomModel) false else state.fastMode,
            openClawNewSession = state.openClawNewSession,
            imagePaths = state.imagePaths,
            skills = selectedSkills,
            goal = goalCommand?.goal,
            maxBudgetUsd = state.budgetText.toMaxBudgetUsd(),
            temporary = state.temporary,
        )
    }

    fun selectSkill(skill: AgentSkill) {
        val command = slashCommand ?: return
        val insertion = "/${skill.name}"
        state.promptValue = TextFieldValue(
            text = state.prompt.replaceRange(command.start, command.end, insertion),
            selection = TextRange(command.start + insertion.length),
        )
        state.skillMenuDismissed = true
        state.attachMcp = attachMcpAfterSkillSelection(skill.name, state.attachMcp)
    }

    fun selectCommand(command: AgentNativeSlashCommand) {
        val slash = slashCommand ?: return
        val insertion = "${command.name.composerCommandToken()} "
        state.promptValue = TextFieldValue(
            text = state.prompt.replaceRange(slash.start, slash.end, insertion),
            selection = TextRange(slash.start + insertion.length),
        )
        state.skillMenuDismissed = true
    }

    fun selectFileMention(result: HostSearchResult) {
        val mention = fileMention ?: return
        state.promptValue = insertFileMention(state.prompt, mention, result)
        state.skillMenuDismissed = true
    }
}

@Composable
private fun rememberComposerSlashHighlight(form: AgentTaskComposerForm) =
    rememberComposerSlashHighlight(
        agent = form.state.agent,
        availableSkills = form.availableSkills,
        availableCommands = form.availableCommands,
    )

@Composable
internal fun rememberComposerSlashHighlight(
    agent: AgentKind,
    availableSkills: List<AgentSkill>,
    availableCommands: List<AgentNativeSlashCommand> = mergedComposerSlashCommands(agent, emptyList()),
): VisualTransformation {
    val menuSkills = remember(availableSkills, availableCommands) {
        composerSkillsForSlashMenu(availableSkills, availableCommands)
    }
    val skillNames = remember(menuSkills) { menuSkills.mapTo(linkedSetOf()) { it.name } }
    val commandNames = remember(availableCommands) {
        availableCommands.mapTo(linkedSetOf()) { it.name.composerCommandName() }
    }
    return rememberComposerSlashHighlight(
        skillNames = skillNames,
        commandNames = commandNames,
    )
}

@Composable
internal fun rememberComposerSlashHighlight(
    skillNames: Set<String>,
    commandNames: Set<String>,
) = remember(skillNames, commandNames, Cyan, Green, Yellow) {
    composerSlashTokenTransformation(
        skillNames = skillNames,
        commandNames = commandNames,
        skillColor = Cyan,
        commandColor = Green,
        mentionColor = Yellow,
    )
}

@Composable
private fun AgentChatComposer(
    form: AgentTaskComposerForm,
    wrapComposerControls: Boolean,
    onCancel: (() -> Unit)?,
    voiceShortcut: KeyCombo?,
    dictationActive: Boolean,
    onSubmit: () -> Unit,
) {
    val state = form.state
    var agentMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    var sandboxMenuExpanded by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val canSubmit = form.canSubmit
    val hasAvailableProvider = form.hasAvailableProvider
    val slashHighlight = rememberComposerSlashHighlight(form)
    val voiceController = rememberVoiceDictationController(
        voice = form.services.voiceDictation,
        onText = { spoken ->
            voiceError = null
            state.promptValue = insertTextAtCursor(state.promptValue, spoken)
        },
        onError = { voiceError = it },
        active = dictationActive,
    )

    fun selectSkill(skill: AgentSkill) = form.selectSkill(skill)
    fun selectCommand(command: AgentNativeSlashCommand) = form.selectCommand(command)
    fun selectFileMention(result: HostSearchResult) = form.selectFileMention(result)

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        ComposerBranchWorktreeChip(
            showGitControls = state.directoryIsGitRepo,
            branch = state.currentBranch,
            useWorktree = state.useWorktree,
            onUseWorktreeChange = { state.useWorktree = it },
            temporary = state.temporary,
            onTemporaryChange = { state.temporary = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space4),
        )
        ChatComposerFrame(
            modifier = Modifier.fillMaxWidth().onVoiceDictationShortcut(voiceShortcut, voiceController),
            highlighted = state.imageDragActive,
        ) {
        Box(Modifier.fillMaxWidth()) {
            TextField(
                state.promptValue,
                {
                    state.promptValue = it
                    state.skillMenuDismissed = false
                },
                singleLine = false,
                minLines = 3,
                maxLines = 7,
                enabled = hasAvailableProvider,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 88.dp, max = 180.dp)
                    .onVoiceDictationShortcut(voiceShortcut, voiceController)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Tab && (form.matchingCommands.isNotEmpty() || form.matchingSkills.isNotEmpty())) {
                            form.matchingCommands.firstOrNull()?.let(::selectCommand) ?: selectSkill(form.matchingSkills.first())
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.Tab && form.mentionResults.isNotEmpty()) {
                            selectFileMention(form.mentionResults.first())
                            return@onPreviewKeyEvent true
                        }
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (event.isShiftPressed) return@onPreviewKeyEvent false
                        if (canSubmit) onSubmit()
                        true
                    }
                    .onChatImagePaste(form.scope) { added ->
                        if (!form.services.remoteSession.isRemote) {
                            state.imagePaths = attachChatImages(state.imagePaths, added)
                        }
                    }
                    .onImageFilesDropped(
                        onFiles = { dropped ->
                            if (!form.services.remoteSession.isRemote) {
                                state.imagePaths = attachChatImages(state.imagePaths, dropped)
                            }
                        },
                        onDragActiveChange = { active ->
                            if (!form.services.remoteSession.isRemote) {
                                state.imageDragActive = active
                            }
                        },
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
                            !hasAvailableProvider -> "Install a provider CLI to start a chat"
                            state.imageDragActive -> "Release to attach images"
                            state.imagePaths.isNotEmpty() -> "Add a message, or send the attached images"
                            else -> "Message the agent, tag @files, or use /commands and /skills"
                        },
                        highlighted = state.imageDragActive,
                    )
                },
            )
            DropdownMenu(
                expanded = form.slashCommand != null && !state.skillMenuDismissed,
                onDismissRequest = { state.skillMenuDismissed = true },
                modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    if (form.matchingCommands.isEmpty() && form.matchingSkills.isEmpty()) {
                        "no ${state.agent.label} commands or skills matching /${form.slashCommand?.query.orEmpty()}"
                    } else {
                        "${state.agent.label} commands and skills matching /${form.slashCommand?.query.orEmpty()}"
                    },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
                form.matchingCommands.forEach { command ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(command.name.composerCommandToken(), color = Green, fontFamily = MonoFont, fontSize = 12.sp)
                                Text(command.description, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                            }
                        },
                        onClick = { selectCommand(command) },
                    )
                }
                form.matchingSkills.forEach { skill ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("/${skill.name}", color = Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                                skill.description.takeIf { it.isNotBlank() }?.let { description ->
                                    Text(description, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                                }
                            }
                        },
                        onClick = { selectSkill(skill) },
                    )
                }
            }
            DropdownMenu(
                expanded = form.fileMention != null && !state.skillMenuDismissed,
                onDismissRequest = { state.skillMenuDismissed = true },
                modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                properties = PopupProperties(focusable = false),
            ) {
                Text(
                    if (form.mentionResults.isEmpty()) {
                        "no files matching @${form.fileMention?.query.orEmpty()}"
                    } else {
                        "files matching @${form.fileMention?.query.orEmpty()}"
                    },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
                form.mentionResults.forEach { result ->
                    DropdownMenuItem(
                        text = {
                            Text(result.relativePath(), color = Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                        },
                        onClick = { selectFileMention(result) },
                    )
                }
            }
        }

        if (state.usesCustomModel) {
            TextField(
                state.customModel,
                { state.customModel = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp),
                colors = fieldColors(),
                placeholder = { Text("custom model or variant", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp) },
            )
        }

        if (form.selectedSkills.isNotEmpty() || state.imagePaths.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (form.selectedSkills.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        form.selectedSkills.forEach { skill ->
                            FilterPill("/${skill.name} ×", true, Cyan) {
                                state.promptValue = TextFieldValue(state.prompt.removeComposerSkill(skill))
                            }
                        }
                    }
                }
                if (state.imagePaths.isNotEmpty()) {
                    ChatAttachedImages(
                        paths = state.imagePaths,
                        onRemove = { path -> state.imagePaths = state.imagePaths.filterNot { it == path } },
                        maxWidth = 140.dp,
                        maxHeight = 100.dp,
                    )
                }
            }
        }

        val leadingControls: @Composable () -> Unit = {
                    Box {
                        ComposerChip(
                            text = if (hasAvailableProvider) {
                                AgentPickerOption(
                                    state.agent,
                                    state.localRuntime.takeIf { state.agent.isLocalModelBackend },
                                ).label
                            } else {
                                "No provider available"
                            },
                            selected = true,
                            onClick = { agentMenuExpanded = true },
                            enabled = hasAvailableProvider,
                            leadingContent = if (hasAvailableProvider) ({ AgentPillIcon(state.agent) }) else null,
                        )
                        DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                            // Keep this in step with the expanded provider controls: an
                            // unavailable provider should still be discoverable here.
                            // It remains disabled until its CLI is available, so a task
                            // cannot be launched with an unusable provider.
                            agentPickerOptions().forEach { option ->
                                val ready = option.comboReady(form.cliStatuses, form.localBackends)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AgentPillIcon(option.agent)
                                            Text(
                                                "${option.label}${if (ready) "" else " · ${if (!option.agent.isLocalModelBackend && form.cliStatuses.firstOrNull { it.kind == option.agent }?.issue != null) "needs repair" else "unavailable"}"}",
                                                color = TextPrimary,
                                            )
                                        }
                                    },
                                    enabled = ready,
                                    onClick = {
                                        val agentChanged = state.agent != option.agent
                                        state.providerChosenInComposer = true
                                        state.agent = option.agent
                                        state.localRuntime = option.localRuntime
                                        if (agentChanged) state.defaultsSeededForAgent = null
                                        agentMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (hasAvailableProvider) Box {
                        val modelLabel = when {
                            state.usesCustomModel -> "custom"
                            form.selectedModel != null -> form.selectedModel.label
                            else -> "Default model"
                        }
                        ComposerChip(
                            text = modelLabel,
                            selected = state.modelId != null,
                            onClick = { modelMenuExpanded = true },
                        )
                        DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                            if (!state.agent.isLocalModelBackend) {
                                DropdownMenuItem(
                                    text = { Text("provider default", color = TextPrimary) },
                                    onClick = {
                                        state.modelId = null
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                            if (state.agent == AgentKind.Cursor) {
                                form.modelOptions.groupedByModelFamily().forEach { (family, options) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                family.label.uppercase(),
                                                color = TextSecondary,
                                                fontFamily = MonoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                            )
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    options.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label, color = TextPrimary) },
                                            onClick = {
                                                state.modelId = option.id
                                                modelMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            } else {
                                form.modelOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, color = TextPrimary) },
                                        onClick = {
                                            state.modelId = option.id
                                            modelMenuExpanded = false
                                        },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text("custom", color = TextPrimary) },
                                onClick = {
                                    state.modelId = ComposerCustomModelId
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                    if (hasAvailableProvider) form.selectedModel?.takeIf { it.efforts.isNotEmpty() }?.let { selectedModel ->
                        Box {
                            ComposerChip(
                                text = state.reasoningEffort?.label ?: "Effort",
                                selected = state.reasoningEffort != null,
                                onClick = { effortMenuExpanded = true },
                            )
                            DropdownMenu(expanded = effortMenuExpanded, onDismissRequest = { effortMenuExpanded = false }) {
                                if (state.agent != AgentKind.Cursor) {
                                    DropdownMenuItem(text = { Text("provider default", color = TextPrimary) }, onClick = { state.reasoningEffort = null; effortMenuExpanded = false })
                                }
                                selectedModel.efforts.forEach { effort -> DropdownMenuItem(text = { Text(effort.label, color = TextPrimary) }, onClick = { state.reasoningEffort = effort; effortMenuExpanded = false }) }
                            }
                        }
                        if (selectedModel.supportsFastMode && !selectedModel.fastRequired) {
                            ComposerChip(
                                text = "Fast",
                                selected = state.fastMode,
                                showChevron = false,
                                onClick = { state.fastMode = !state.fastMode },
                            )
                        }
                    }
                    if (hasAvailableProvider) ComposerChip(
                        text = "Plan",
                        selected = state.planMode,
                        showChevron = false,
                        onClick = { state.planMode = !state.planMode },
                    )
                    if (hasAvailableProvider && state.agent == AgentKind.OpenClaw) {
                        ComposerChip(
                            text = if (state.openClawNewSession) "New session" else "Main session",
                            selected = state.openClawNewSession,
                            showChevron = false,
                            onClick = { state.openClawNewSession = !state.openClawNewSession },
                        )
                    }
                    if (hasAvailableProvider) Box {
                        val sandbox = state.sandboxMode ?: state.autonomy.defaultSandboxMode()
                        ComposerChip(
                            text = sandbox.labelFor(state.agent.runtimeKind(state.localRuntime)),
                            selected = true,
                            onClick = { sandboxMenuExpanded = true },
                        )
                        DropdownMenu(expanded = sandboxMenuExpanded, onDismissRequest = { sandboxMenuExpanded = false }) {
                            AgentSandboxMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.labelFor(state.agent.runtimeKind(state.localRuntime)), color = TextPrimary) }, onClick = { state.sandboxMode = mode; sandboxMenuExpanded = false }) }
                        }
                    }
                    onCancel?.let { cancel ->
                        ComposerChip(
                            text = "Cancel",
                            selected = false,
                            showChevron = false,
                            onClick = cancel,
                        )
                    }
        }
        val trailingControls: @Composable () -> Unit = {
            if (!hasAvailableProvider) {
                ChatSendButton(onClick = onSubmit, enabled = false)
            } else {
                AgentQuotaMenu(services = form.services, agent = state.agent)
                if (form.services.remoteSession.isRemote) {
                    var remoteImagePath by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = remoteImagePath,
                        onValueChange = { remoteImagePath = it },
                        singleLine = true,
                        placeholder = { Text("Remote image path", fontSize = 11.sp) },
                        modifier = Modifier.widthIn(max = 220.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 11.sp, color = TextPrimary),
                    )
                    ComposerChip(
                        text = "Attach",
                        selected = false,
                        showChevron = false,
                        enabled = remoteImagePath.isNotBlank(),
                        onClick = {
                            val path = remoteImagePath.trim()
                            if (path.isNotEmpty()) {
                                state.imagePaths = attachChatImages(state.imagePaths, listOf(path))
                                remoteImagePath = ""
                            }
                        },
                    )
                } else {
                    ChatImageAttachButton(
                        onImagesAttached = { added -> state.imagePaths = attachChatImages(state.imagePaths, added) },
                    )
                }
                ChatVoiceDictationButton(controller = voiceController)
                ChatSendButton(onClick = onSubmit, enabled = canSubmit)
            }
        }
        if (wrapComposerControls) {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    leadingControls()
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    trailingControls()
                }
            }
        } else {
            ComposerToolbarRow(
                leading = {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingControls()
                    }
                },
                trailing = { trailingControls() },
            )
        }
        voiceError?.let { err ->
            Text(err, color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
        }
        }
    }
}

/**
 * The row always renders so a temporary chat can be started anywhere — outside a git repo and
 * in chats with no project context, which is exactly where a throwaway question tends to start.
 * Only the branch label and worktree checkbox are gated on being in a repo.
 */
@Composable
private fun ComposerBranchWorktreeChip(
    showGitControls: Boolean,
    branch: String?,
    useWorktree: Boolean,
    onUseWorktreeChange: (Boolean) -> Unit,
    temporary: Boolean,
    onTemporaryChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = TextSecondary
    Row(
        modifier.height(AndyLayout.ControlHeightSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showGitControls) {
            Row(
                Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.git_branch),
                    contentDescription = "git branch",
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    branch ?: "detached HEAD",
                    color = content,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                Modifier.clickable(role = Role.Checkbox) { onUseWorktreeChange(!useWorktree) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ComposerWorktreeCheckbox(checked = useWorktree, color = content)
                Text(
                    "worktree",
                    color = content,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        ComposerTemporaryToggle(
            temporary = temporary,
            onTemporaryChange = onTemporaryChange,
            offColor = content,
        )
    }
}

/**
 * Icon-only toggle: tinted when on, neutral when off. It carries no label, so the state has to
 * read from the tint alone.
 *
 * Drawn rather than resampled from the source PNG. That artwork is a 100px thin-stroke line icon,
 * and at this size its dashed outline and interior lines land below one pixel and blur into a
 * smudge. Stroke widths here are chosen in dp so they stay crisp at any scale factor, matching
 * how [ComposerWorktreeCheckbox] beside it is drawn.
 */
@Composable
private fun ComposerTemporaryToggle(
    temporary: Boolean,
    onTemporaryChange: (Boolean) -> Unit,
    offColor: Color,
    modifier: Modifier = Modifier,
) {
    val tint = if (temporary) Yellow else offColor.copy(alpha = 0.85f)
    HoverTooltip(
        text = if (temporary) {
            "Temporary chat: on — discarded when closed"
        } else {
            "Temporary chat — never saved to history"
        },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(AndyLayout.ControlHeightSm)
                .clickable(role = Role.Checkbox) { onTemporaryChange(!temporary) }
                .semantics {
                    contentDescription = if (temporary) "temporary chat on" else "temporary chat off"
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(TemporaryIconSize)) {
                val stroke = 1.3.dp.toPx()
                val inset = stroke / 2f
                val bubbleHeight = size.height * 0.72f
                // A rounded rectangle rather than an ellipse: its straight runs give the dashes
                // somewhere to read as dashes instead of collapsing into a dotted ring.
                val bubble = RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = bubbleHeight - inset,
                    cornerRadius = CornerRadius(size.height * 0.30f),
                )
                // Dash lengths must come from dp too — a fixed pixel array turns into a solid
                // outline at 2x and a dotted one at 1x.
                drawPath(
                    path = Path().apply { addRoundRect(bubble) },
                    color = tint,
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(2.6.dp.toPx(), 1.7.dp.toPx()),
                            phase = 0f,
                        ),
                    ),
                )
                // Tail hangs below the bubble rather than crossing into it, and is filled rather
                // than stroked — an outlined triangle this small collapses into a pinched hook.
                drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.30f, bubbleHeight - stroke)
                        lineTo(size.width * 0.25f, size.height - inset)
                        lineTo(size.width * 0.50f, bubbleHeight - stroke)
                        close()
                    },
                    color = tint,
                )
                // Two rows, not the source artwork's three: a third leaves well under a pixel of
                // gap at this size and all three merge into a solid block.
                listOf(0.34f to 0.48f, 0.62f to 0.32f).forEach { (y, widthFraction) ->
                    val lineY = bubbleHeight * y
                    val start = size.width * 0.26f
                    drawLine(
                        color = tint,
                        start = Offset(start, lineY),
                        end = Offset(start + size.width * widthFraction, lineY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private val TemporaryIconSize = 17.dp

@Composable
private fun ComposerWorktreeCheckbox(
    checked: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(12.dp)
            .border(1.dp, color.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
            .background(
                if (checked) color.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size(8.dp)) {
                val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round)
                val path = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.55f)
                    lineTo(size.width * 0.42f, size.height * 0.82f)
                    lineTo(size.width * 0.88f, size.height * 0.22f)
                }
                drawPath(path, color, style = stroke)
            }
        }
    }
}

/** User-invocable orchestration skills that require Andy MCP attach on new-task submit. */
internal fun isOrchestrationSkillName(name: String): Boolean =
    name.lowercase() in setOf("andy-handoff", "andy-loop", "andy-advisor", "andy-committee")

/** Returns the attachMcp value after selecting [skillName] in the new-task composer. */
internal fun attachMcpAfterSkillSelection(skillName: String, currentAttachMcp: Boolean): Boolean =
    if (isOrchestrationSkillName(skillName)) true else currentAttachMcp

internal fun String.toMaxBudgetUsd(): Double? = trim()
    .toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0.0 }

private data class ComposerSlashCommand(val start: Int, val end: Int, val query: String)

private fun findComposerSlashCommand(text: String): ComposerSlashCommand? {
    val match = Regex("(?:^|\\s)/([A-Za-z0-9:_-]*)$").find(text) ?: return null
    val tokenStart = match.range.first + if (match.value.startsWith('/')) 0 else 1
    return ComposerSlashCommand(tokenStart, text.length, match.groupValues[1])
}

private fun String.referencesComposerSkill(skill: AgentSkill): Boolean =
    Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)").containsMatchIn(this)

private fun String.removeComposerSkill(skill: AgentSkill): String =
    replace(Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
