package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.andy.model.ActionProject
import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentNativeSlashCommand
import app.andy.model.AgentNativeSlashCommands
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTaskDraft
import app.andy.model.ProjectAgentProfile
import app.andy.model.defaultSandboxMode
import app.andy.model.descriptionFor
import app.andy.model.labelFor
import app.andy.model.parseAgentGoalCommand
import app.andy.model.sandboxControlLabel
import app.andy.onImageFilesDropped
import app.andy.pickDirectory
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AgentTaskComposerPane(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject?,
    onSubmit: (AgentTaskDraft) -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val form = rememberAgentTaskComposerForm(services, cliStatuses, projectContext)
    val copyText = rememberCopyText()
    val scope = rememberCoroutineScope()
    var showOptions by remember(projectContext?.id) { mutableStateOf(false) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showOptions) {
            AgentTaskComposerFields(
                form = form,
                showProjectHeader = projectContext != null,
                showContextPicker = projectContext == null,
                showAgentControls = false,
                showModelControls = false,
                showPrompt = false,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            )
        } else {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AgentMark(form.state.agent)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            projectContext?.let { "Make progress in ${it.name}" } ?: "Give an agent its next move",
                            color = TextPrimary,
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 28.sp,
                        )
                        Text(
                            "Write the outcome you want. Add context, an image, or a / skill when it helps.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        AgentCliIssueNotices(
            statuses = cliStatuses,
            onCopyRepairCommand = copyText,
            onRefresh = { scope.launch { services.agentRuns.refreshCliStatuses() } },
        )
        AgentChatComposer(
            form = form,
            showOptions = showOptions,
            onShowOptionsChange = { showOptions = it },
            onCancel = onCancel,
            onSubmit = {
                onSubmit(form.buildDraft())
                form.clearPrompt()
            },
        )
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
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.OrangeSubtle, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.65f), RoundedCornerShape(AndyRadius.R3))
                    .padding(10.dp),
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
    var providerChosenInComposer by mutableStateOf(false)
    var customDirectory by mutableStateOf("")
    var usesCustomDirectory by mutableStateOf(false)
    var useWorktree by mutableStateOf(false)
    var attachMcp by mutableStateOf(false)
    var autonomy by mutableStateOf(AgentAutonomy.Standard)
    var sandboxMode by mutableStateOf<AgentSandboxMode?>(null)
    var planMode by mutableStateOf(false)
    var modelId by mutableStateOf<String?>(null)
    var customModel by mutableStateOf("")
    var reasoningEffort by mutableStateOf<AgentReasoningEffort?>(null)
    var fastMode by mutableStateOf(false)
    var budgetText by mutableStateOf("")
    var directoryIsGitRepo by mutableStateOf(false)
    /** Last agent whose provider defaults were seeded into this draft; avoids clobbering restored drafts. */
    var defaultsSeededForAgent: AgentKind? = null

    val prompt: String get() = promptValue.text
    val usesCustomModel: Boolean get() = modelId == CUSTOM_MODEL_ID

    fun clearPrompt() {
        promptValue = TextFieldValue("")
        imagePaths = emptyList()
        skillMenuDismissed = false
    }

    fun applyProviderDefaults(defaults: AgentProviderDefaults?, agent: AgentKind) {
        val savedModel = defaults?.model
        val catalogModel = AgentModelCatalog.option(agent, savedModel)
        modelId = when {
            savedModel == null -> null
            catalogModel != null -> catalogModel.id
            else -> CUSTOM_MODEL_ID
        }
        customModel = if (catalogModel == null) savedModel.orEmpty() else ""
        reasoningEffort = defaults?.reasoningEffort
        fastMode = defaults?.fastMode == true
        autonomy = defaults?.autonomy ?: AgentAutonomy.Standard
        // Leave the sandbox unset unless it was explicitly saved. This lets the
        // provider derive it from whichever autonomy level the user chooses.
        sandboxMode = defaults?.sandboxMode
        planMode = defaults?.planMode == true
        useWorktree = defaults?.useWorktree == true
        attachMcp = defaults?.attachAndyMcp == true
        budgetText = defaults?.maxBudgetUsd?.toString().orEmpty()
    }
}

private fun AgentTaskComposerFormState.providerProfile(): ProjectAgentProfile = ProjectAgentProfile(
    agent = agent,
    model = if (usesCustomModel) customModel else modelId,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
)

private fun AgentTaskComposerFormState.applyProviderProfile(profile: ProjectAgentProfile) {
    providerChosenInComposer = true
    agent = profile.agent
    val catalogModel = AgentModelCatalog.option(profile.agent, profile.model)
    modelId = when {
        profile.model == null -> null
        catalogModel != null -> catalogModel.id
        else -> CUSTOM_MODEL_ID
    }
    customModel = if (catalogModel == null) profile.model.orEmpty() else ""
    reasoningEffort = profile.reasoningEffort
    fastMode = profile.fastMode
}

@Composable
private fun rememberAgentTaskComposerForm(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject?,
): AgentTaskComposerForm {
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()
    val lastUsedAgent by services.agentRuns.lastUsedAgent.collectAsState()
    val scope = rememberCoroutineScope()
    val formsByProject = remember { mutableMapOf<String?, AgentTaskComposerFormState>() }
    val projectKey = projectContext?.id
    val state = formsByProject.getOrPut(projectKey) {
        AgentTaskComposerFormState(cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode)
    }

    val directory = projectContext?.contextDir
        ?: state.customDirectory.takeIf { state.usesCustomDirectory && it.isNotBlank() }
    val availableSkills by remember(state.agent, directory) {
        services.agentRuns.skills(state.agent, directory)
    }.collectAsState()
    val selectedCliAvailable = cliStatuses.any { it.kind == state.agent && it.ready }
    val showModelSection = state.providerChosenInComposer && selectedCliAvailable
    val selectedModel = AgentModelCatalog.option(state.agent, state.modelId)
    val slashCommand = findComposerSlashCommand(state.prompt)
    val matchingCommands = slashCommand?.let { command ->
        AgentNativeSlashCommands.forAgent(state.agent).filter { nativeCommand ->
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
    val selectedSkills = remember(state.prompt, availableSkills) {
        availableSkills.filter { skill -> state.prompt.referencesComposerSkill(skill) }
    }
    val validBudget = state.budgetText.toMaxBudgetUsd()
    val canSubmit = state.prompt.isNotBlank() &&
        (!state.usesCustomModel || state.customModel.isNotBlank()) &&
        (state.budgetText.isBlank() || validBudget != null) &&
        cliStatuses.any { it.kind == state.agent && it.ready }

    LaunchedEffect(lastUsedAgent, cliStatuses, projectKey) {
        if (!state.providerChosenInComposer) {
            val preferred = lastUsedAgent?.takeIf { preferred ->
                cliStatuses.any { it.kind == preferred && it.ready }
            }
            if (preferred != null) {
                state.agent = preferred
                state.providerChosenInComposer = true
            } else {
                state.agent = cliStatuses.firstOrNull { it.ready }?.kind ?: AgentKind.ClaudeCode
            }
        }
    }

    // Apply defaults when the agent changes, or once for a newly created project draft.
    // Do not re-apply merely because we navigated back to an existing draft.
    LaunchedEffect(state, state.agent, providerDefaults[state.agent]) {
        val agent = state.agent
        if (state.defaultsSeededForAgent != agent) {
            state.applyProviderDefaults(providerDefaults[agent], agent)
            state.defaultsSeededForAgent = agent
        }
    }

    LaunchedEffect(directory, state.useWorktree) {
        state.directoryIsGitRepo = directory?.let { services.agentRuns.isGitRepo(it) } == true
        if (!state.directoryIsGitRepo) state.useWorktree = false
    }
    LaunchedEffect(state.agent, state.modelId) {
        val model = selectedModel
        when {
            state.usesCustomModel || model == null || model.efforts.isEmpty() -> state.reasoningEffort = null
            state.reasoningEffort !in model.efforts -> {
                // Cursor encodes effort in the model slug; leaving it unset yields invalid ids like cursor-grok-4.5.
                state.reasoningEffort = if (state.agent == AgentKind.Cursor) model.preferredEffort() else null
            }
        }
        if (model?.supportsFastMode != true) state.fastMode = false
    }

    return AgentTaskComposerForm(
        state = state,
        services = services,
        cliStatuses = cliStatuses,
        projectContext = projectContext,
        directory = directory,
        showModelSection = showModelSection,
        selectedModel = selectedModel,
        availableSkills = availableSkills,
        slashCommand = slashCommand,
        matchingCommands = matchingCommands,
        matchingSkills = matchingSkills,
        selectedSkills = selectedSkills,
        canSubmit = canSubmit,
        scope = scope,
    )
}

private class AgentTaskComposerForm(
    val state: AgentTaskComposerFormState,
    val services: AndyServices,
    val cliStatuses: List<AgentCliStatus>,
    val projectContext: ActionProject?,
    val directory: String?,
    val showModelSection: Boolean,
    val selectedModel: AgentModelOption?,
    val availableSkills: List<AgentSkill>,
    val slashCommand: ComposerSlashCommand?,
    val matchingCommands: List<AgentNativeSlashCommand>,
    val matchingSkills: List<AgentSkill>,
    val selectedSkills: List<AgentSkill>,
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
            projectId = projectContext?.id,
            directory = directory?.trim()?.takeIf { it.isNotBlank() },
            useWorktree = state.useWorktree,
            attachAndyMcp = state.attachMcp,
            autonomy = state.autonomy,
            sandboxMode = state.sandboxMode,
            planMode = state.planMode,
            model = if (state.usesCustomModel) state.customModel.trim().ifBlank { null } else state.modelId,
            reasoningEffort = if (state.usesCustomModel) null else state.reasoningEffort,
            fastMode = if (state.usesCustomModel) false else state.fastMode,
            imagePaths = state.imagePaths,
            skills = selectedSkills,
            goal = goalCommand?.goal,
            maxBudgetUsd = state.budgetText.toMaxBudgetUsd(),
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
    }

    fun selectCommand(command: AgentNativeSlashCommand) {
        val slash = slashCommand ?: return
        val insertion = "/${command.name} "
        state.promptValue = TextFieldValue(
            text = state.prompt.replaceRange(slash.start, slash.end, insertion),
            selection = TextRange(slash.start + insertion.length),
        )
        state.skillMenuDismissed = true
    }
}

@Composable
private fun rememberComposerSlashHighlight(form: AgentTaskComposerForm) =
    rememberComposerSlashHighlight(
        agent = form.state.agent,
        availableSkills = form.availableSkills,
    )

@Composable
internal fun rememberComposerSlashHighlight(
    agent: AgentKind,
    availableSkills: List<AgentSkill>,
): VisualTransformation {
    val skillNames = remember(availableSkills) { availableSkills.mapTo(linkedSetOf()) { it.name } }
    val commandNames = remember(agent) {
        AgentNativeSlashCommands.forAgent(agent).mapTo(linkedSetOf()) { it.name }
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
) = remember(skillNames, commandNames, Cyan, Green) {
    composerSlashTokenTransformation(
        skillNames = skillNames,
        commandNames = commandNames,
        skillColor = Cyan,
        commandColor = Green,
    )
}

@Composable
private fun AgentChatComposer(
    form: AgentTaskComposerForm,
    showOptions: Boolean,
    onShowOptionsChange: (Boolean) -> Unit,
    onCancel: (() -> Unit)?,
    onSubmit: () -> Unit,
) {
    val state = form.state
    var agentMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    var sandboxMenuExpanded by remember { mutableStateOf(false) }
    val canSubmit = form.canSubmit
    val slashHighlight = rememberComposerSlashHighlight(form)

    fun selectSkill(skill: AgentSkill) = form.selectSkill(skill)
    fun selectCommand(command: AgentNativeSlashCommand) = form.selectCommand(command)

    Column(
        Modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, if (state.imageDragActive) Cyan else Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 94.dp, max = 180.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Tab && (form.matchingCommands.isNotEmpty() || form.matchingSkills.isNotEmpty())) {
                            form.matchingCommands.firstOrNull()?.let(::selectCommand) ?: selectSkill(form.matchingSkills.first())
                            return@onPreviewKeyEvent true
                        }
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (event.isShiftPressed) return@onPreviewKeyEvent false
                        if (canSubmit) onSubmit()
                        true
                    }
                    .onImageFilesDropped(
                        onFiles = { dropped -> state.imagePaths = (state.imagePaths + dropped).distinct() },
                        onDragActiveChange = { active -> state.imageDragActive = active },
                    ),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont, fontSize = 13.sp),
                colors = fieldColors(),
                visualTransformation = slashHighlight,
                placeholder = {
                    Text(
                        if (state.imageDragActive) "release to attach image" else "What should ${state.agent.label} work on?",
                        color = if (state.imageDragActive) Cyan else TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 13.sp,
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
                                Text("/${command.name}", color = Green, fontFamily = MonoFont, fontSize = 12.sp)
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

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Box {
                FilterPill(
                    state.agent.label,
                    true,
                    agentColor(state.agent),
                    leadingContent = { AgentPillIcon(state.agent) },
                ) {
                    agentMenuExpanded = true
                }
                DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                    // Keep this in step with the expanded provider controls: an
                    // unavailable provider should still be discoverable here.
                    // It remains disabled until its CLI is available, so a task
                    // cannot be launched with an unusable provider.
                    AgentKind.entries.forEach { agent ->
                        val status = form.cliStatuses.firstOrNull { it.kind == agent }
                        val ready = status?.ready == true || form.cliStatuses.isEmpty()
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AgentPillIcon(agent)
                                    Text(
                                        "${agent.label}${if (ready) "" else " · ${if (status?.issue != null) "needs repair" else "unavailable"}"}",
                                        color = TextPrimary,
                                    )
                                }
                            },
                            enabled = ready,
                            onClick = {
                                state.providerChosenInComposer = true
                                state.agent = agent
                                agentMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Box {
                val modelLabel = when {
                    state.usesCustomModel -> "custom"
                    form.selectedModel != null -> form.selectedModel.label
                    else -> "model: default"
                }
                FilterPill(modelLabel, state.modelId != null, agentColor(state.agent)) { modelMenuExpanded = true }
                DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("provider default", color = TextPrimary) },
                        onClick = {
                            state.modelId = null
                            modelMenuExpanded = false
                        },
                    )
                    AgentModelCatalog.options(state.agent).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = TextPrimary) },
                            onClick = {
                                state.modelId = option.id
                                modelMenuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("custom", color = TextPrimary) },
                        onClick = {
                            state.modelId = CUSTOM_MODEL_ID
                            modelMenuExpanded = false
                        },
                    )
                }
            }
                form.selectedModel?.takeIf { it.efforts.isNotEmpty() }?.let { selectedModel ->
                    Box {
                        FilterPill(state.reasoningEffort?.label ?: "effort", state.reasoningEffort != null, Rust) { effortMenuExpanded = true }
                        DropdownMenu(expanded = effortMenuExpanded, onDismissRequest = { effortMenuExpanded = false }) {
                            if (state.agent != AgentKind.Cursor) {
                                DropdownMenuItem(text = { Text("provider default", color = TextPrimary) }, onClick = { state.reasoningEffort = null; effortMenuExpanded = false })
                            }
                            selectedModel.efforts.forEach { effort -> DropdownMenuItem(text = { Text(effort.label, color = TextPrimary) }, onClick = { state.reasoningEffort = effort; effortMenuExpanded = false }) }
                        }
                    }
                    if (selectedModel.supportsFastMode) FilterPill("fast", state.fastMode, Green) { state.fastMode = !state.fastMode }
                }
                FilterPill("plan", state.planMode, Green) { state.planMode = !state.planMode }
                Box {
                    val sandbox = state.sandboxMode ?: state.autonomy.defaultSandboxMode()
                    FilterPill(sandbox.labelFor(state.agent), true, if (sandbox == AgentSandboxMode.None) Rust else Cyan) { sandboxMenuExpanded = true }
                    DropdownMenu(expanded = sandboxMenuExpanded, onDismissRequest = { sandboxMenuExpanded = false }) {
                        AgentSandboxMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.labelFor(state.agent), color = TextPrimary) }, onClick = { state.sandboxMode = mode; sandboxMenuExpanded = false }) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Spacer(Modifier.weight(1f))
            AgentQuotaMenu(services = form.services, agent = state.agent)
            OutlinedButton(onClick = { onShowOptionsChange(!showOptions) }) {
                Text(if (showOptions) "hide options" else "options", fontSize = 11.sp)
            }
            onCancel?.let { cancel ->
                OutlinedButton(onClick = cancel) { Text("cancel", fontSize = 11.sp) }
            }
            Button(onClick = onSubmit, enabled = canSubmit, colors = primaryButtonColors()) {
                Text("start", fontSize = 11.sp)
            }
            }
        }
    }
}

@Composable
private fun AgentTaskComposerFields(
    form: AgentTaskComposerForm,
    showProjectHeader: Boolean,
    showContextPicker: Boolean,
    showAgentControls: Boolean = true,
    showModelControls: Boolean = true,
    showPrompt: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state = form.state
    val scope = form.scope
    val slashHighlight = rememberComposerSlashHighlight(form)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showProjectHeader && form.projectContext != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(form.projectContext.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(form.projectContext.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }

        if (showAgentControls) {
            AgentProviderModelProfileControls(
                profile = state.providerProfile(),
                onChange = state::applyProviderProfile,
                cliStatuses = form.cliStatuses,
                providerSelectionActive = state.providerChosenInComposer,
                showModelControls = false,
            )
        }

        AnimatedVisibility(
            visible = showModelControls && form.showModelSection,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentProviderModelProfileControls(
                    profile = state.providerProfile(),
                    onChange = state::applyProviderProfile,
                    cliStatuses = form.cliStatuses,
                    showProviderControls = false,
                    showVersion = true,
                    showModelHelp = true,
                )
            }
        }

        if (showPrompt) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Prompt", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Box {
                TextField(
                    state.promptValue,
                    {
                        state.promptValue = it
                        state.skillMenuDismissed = false
                    },
                    singleLine = false,
                    minLines = if (showProjectHeader) 8 else 6,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = if (showProjectHeader) 180.dp else 140.dp)
                        .onImageFilesDropped(
                            onFiles = { dropped -> state.imagePaths = (state.imagePaths + dropped).distinct() },
                            onDragActiveChange = { active -> state.imageDragActive = active },
                        )
                        .border(
                            if (state.imageDragActive) 2.dp else 1.dp,
                            if (state.imageDragActive) Cyan else Border,
                            RoundedCornerShape(AndyRadius.R3),
                        )
                        .background(
                            if (state.imageDragActive) Cyan.copy(alpha = 0.12f) else AndyColors.Neutral900.copy(alpha = 0.2f),
                            RoundedCornerShape(AndyRadius.R3),
                        ),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                    colors = fieldColors(),
                    visualTransformation = slashHighlight,
                    placeholder = {
                        Text("type / for ${state.agent.label} commands or skills — drop image files here to attach them", color = TextSecondary, fontFamily = MonoFont)
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
                                    Text("/${command.name}", color = Green, fontFamily = MonoFont, fontSize = 12.sp)
                                    Text(command.description, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                                }
                            },
                            onClick = { form.selectCommand(command) },
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
                            onClick = { form.selectSkill(skill) },
                        )
                    }
                }
            }
            if (form.selectedSkills.isNotEmpty()) {
                Text("selected skills", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.selectedSkills.forEach { skill ->
                        FilterPill("/${skill.name} ×", true, Cyan) {
                            state.promptValue = TextFieldValue(state.prompt.removeComposerSkill(skill))
                        }
                    }
                }
            }
        }
        if (showPrompt && state.imageDragActive) {
            Text("release to attach image", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        } else if (showPrompt && state.imagePaths.isEmpty()) {
            Text("drop image files onto the prompt to attach them", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        } else if (showPrompt) {
            Text("Attached images", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            ChatAttachedImages(
                paths = state.imagePaths,
                onRemove = { path -> state.imagePaths = state.imagePaths.filterNot { it == path } },
                maxWidth = 140.dp,
                maxHeight = 100.dp,
            )
        }

        if (showContextPicker) {
            if (form.projectContext != null) {
                Text("Project context", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Text(form.projectContext.name, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
                Text(form.projectContext.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            } else {
                Text("Context (optional)", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("no context", !state.usesCustomDirectory, Cyan) { state.usesCustomDirectory = false }
                    FilterPill("custom dir", state.usesCustomDirectory, Cyan) {
                        state.usesCustomDirectory = true
                    }
                }
            }
            if (form.projectContext == null && state.usesCustomDirectory) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        state.customDirectory,
                        { state.customDirectory = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(54.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                        colors = fieldColors(),
                        placeholder = { Text("directory the agent works in", color = TextSecondary, fontFamily = MonoFont) },
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                pickDirectory(state.customDirectory.ifBlank { null })?.let { state.customDirectory = it }
                            }
                        },
                        colors = primaryButtonColors(),
                    ) { Text("browse") }
                }
            }
        }

        Text("Options", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.directoryIsGitRepo) {
                FilterPill("isolate in git worktree", state.useWorktree, Green) { state.useWorktree = !state.useWorktree }
            }
            FilterPill("andy device tools (mcp)", state.attachMcp, Cyan) { state.attachMcp = !state.attachMcp }
        }

        Text("Autonomy", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentAutonomy.entries.forEach { level ->
                FilterPill(level.label, state.autonomy == level, Rust) { state.autonomy = level }
            }
        }
        Text(
            "standard may stop when the agent needs approval; use full for unattended runs in trusted or worktree dirs",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        FilterPill("plan mode", state.planMode, Green) { state.planMode = !state.planMode }
        if (state.planMode) {
            Text(
                "Plan mode takes precedence: ${state.agent.label} analyzes and proposes changes without editing the workspace.",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
        Text(
            "${state.agent.label} ${state.agent.sandboxControlLabel()}",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentSandboxMode.entries.forEach { mode ->
                FilterPill(mode.labelFor(state.agent), state.sandboxMode == mode, if (mode == AgentSandboxMode.None) Rust else Cyan) {
                    state.sandboxMode = mode
                }
            }
        }
        Text(
            (state.sandboxMode ?: state.autonomy.defaultSandboxMode()).descriptionFor(state.agent),
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 10.sp,
        )
        if (form.showModelSection && state.agent == AgentKind.ClaudeCode) {
            LabeledField("Max budget USD (optional)", state.budgetText, { state.budgetText = it }, Modifier.fillMaxWidth(), placeholder = "e.g. 2.50")
        }
    }
}

private const val CUSTOM_MODEL_ID = "__custom__"

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
