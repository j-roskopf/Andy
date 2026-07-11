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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSkill
import app.andy.model.AgentTaskDraft
import app.andy.onImageFilesDropped
import app.andy.pickDirectory
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
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AgentTaskComposer(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject? = null,
    onDismiss: () -> Unit,
    onSubmit: (AgentTaskDraft) -> Unit,
) {
    val form = rememberAgentTaskComposerForm(services, cliStatuses, projectContext)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("New agent task", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            AgentTaskComposerFields(
                form = form,
                showProjectHeader = false,
                showContextPicker = true,
                modifier = Modifier.width(720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(form.buildDraft()) },
                enabled = form.canSubmit,
                colors = primaryButtonColors(),
            ) { Text("Start") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AgentTaskComposerPane(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject,
    onSubmit: (AgentTaskDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = rememberAgentTaskComposerForm(services, cliStatuses, projectContext)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentTaskComposerFields(
            form = form,
            showProjectHeader = true,
            showContextPicker = false,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    onSubmit(form.buildDraft())
                    form.clearPrompt()
                },
                enabled = form.canSubmit,
                colors = primaryButtonColors(),
            ) { Text("Start chat") }
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
    var modelId by mutableStateOf<String?>(null)
    var customModel by mutableStateOf("")
    var reasoningEffort by mutableStateOf<AgentReasoningEffort?>(null)
    var fastMode by mutableStateOf(false)
    var budgetText by mutableStateOf("")
    var directoryIsGitRepo by mutableStateOf(false)

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
            catalogModel != null -> savedModel
            else -> CUSTOM_MODEL_ID
        }
        customModel = if (catalogModel == null) savedModel.orEmpty() else ""
        reasoningEffort = defaults?.reasoningEffort
        fastMode = defaults?.fastMode == true
        autonomy = defaults?.autonomy ?: AgentAutonomy.Standard
        useWorktree = defaults?.useWorktree == true
        attachMcp = defaults?.attachAndyMcp == true
        budgetText = defaults?.maxBudgetUsd?.toString().orEmpty()
    }
}

@Composable
private fun rememberAgentTaskComposerForm(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject?,
): AgentTaskComposerForm {
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()
    val lastUsedAgent by services.agentRuns.lastUsedAgent.collectAsState()
    val availableSkills by services.agentRuns.availableSkills.collectAsState()
    val scope = rememberCoroutineScope()
    val state = remember(projectContext?.id) {
        AgentTaskComposerFormState(cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode)
    }

    val directory = projectContext?.contextDir
        ?: state.customDirectory.takeIf { state.usesCustomDirectory && it.isNotBlank() }
    val selectedCliAvailable = cliStatuses.any { it.kind == state.agent && it.available }
    val showModelSection = state.providerChosenInComposer && selectedCliAvailable
    val selectedModel = AgentModelCatalog.option(state.agent, state.modelId)
    val slashCommand = findComposerSlashCommand(state.prompt)
    val matchingSkills = slashCommand?.let { command ->
        availableSkills.filter { skill ->
            skill.name.contains(command.query, ignoreCase = true) ||
                skill.description.contains(command.query, ignoreCase = true)
        }.take(8)
    }.orEmpty()
    val selectedSkills = remember(state.prompt, availableSkills) {
        availableSkills.filter { skill -> state.prompt.referencesComposerSkill(skill) }
    }
    val canSubmit = state.prompt.isNotBlank() &&
        (!state.usesCustomModel || state.customModel.isNotBlank()) &&
        cliStatuses.any { it.kind == state.agent && it.available }

    LaunchedEffect(lastUsedAgent, cliStatuses, projectContext?.id) {
        if (!state.providerChosenInComposer) {
            val preferred = lastUsedAgent?.takeIf { preferred ->
                cliStatuses.any { it.kind == preferred && it.available }
            }
            if (preferred != null) {
                state.agent = preferred
                state.providerChosenInComposer = true
            } else {
                state.agent = cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode
            }
        }
    }

    LaunchedEffect(state.agent, providerDefaults[state.agent], projectContext?.id) {
        state.applyProviderDefaults(providerDefaults[state.agent], state.agent)
    }

    LaunchedEffect(directory, state.useWorktree) {
        state.directoryIsGitRepo = directory?.let { services.agentRuns.isGitRepo(it) } == true
        if (!state.directoryIsGitRepo) state.useWorktree = false
    }
    LaunchedEffect(state.agent, state.modelId) {
        if (state.usesCustomModel || selectedModel?.efforts?.contains(state.reasoningEffort) != true) {
            state.reasoningEffort = null
        }
        if (selectedModel?.supportsFastMode != true) state.fastMode = false
    }

    return AgentTaskComposerForm(
        state = state,
        services = services,
        cliStatuses = cliStatuses,
        projectContext = projectContext,
        directory = directory,
        showModelSection = showModelSection,
        selectedModel = selectedModel,
        slashCommand = slashCommand,
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
    val slashCommand: ComposerSlashCommand?,
    val matchingSkills: List<AgentSkill>,
    val selectedSkills: List<AgentSkill>,
    val canSubmit: Boolean,
    val scope: CoroutineScope,
) {
    fun clearPrompt() = state.clearPrompt()

    fun buildDraft(): AgentTaskDraft = AgentTaskDraft(
        title = "",
        prompt = state.prompt.trim(),
        agent = state.agent,
        projectId = projectContext?.id,
        directory = directory?.trim()?.takeIf { it.isNotBlank() },
        useWorktree = state.useWorktree,
        attachAndyMcp = state.attachMcp,
        autonomy = state.autonomy,
        model = if (state.usesCustomModel) state.customModel.trim().ifBlank { null } else state.modelId,
        reasoningEffort = if (state.usesCustomModel) null else state.reasoningEffort,
        fastMode = if (state.usesCustomModel) false else state.fastMode,
        imagePaths = state.imagePaths,
        skills = selectedSkills,
        maxBudgetUsd = state.budgetText.trim().toDoubleOrNull(),
    )

    fun selectSkill(skill: AgentSkill) {
        val command = slashCommand ?: return
        val insertion = "/${skill.name}"
        state.promptValue = TextFieldValue(
            text = state.prompt.replaceRange(command.start, command.end, insertion),
            selection = TextRange(command.start + insertion.length),
        )
        state.skillMenuDismissed = true
    }
}

@Composable
private fun AgentTaskComposerFields(
    form: AgentTaskComposerForm,
    showProjectHeader: Boolean,
    showContextPicker: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = form.state
    val scope = form.scope
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showProjectHeader && form.projectContext != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(form.projectContext.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(form.projectContext.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }

        Text("Agent", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentKind.entries.forEach { kind ->
                val status = form.cliStatuses.firstOrNull { it.kind == kind }
                if (status?.available == true) {
                    FilterPill(
                        "${agentMonogram(kind)} ${kind.label}",
                        state.providerChosenInComposer && state.agent == kind,
                        agentColor(kind),
                    ) {
                        state.providerChosenInComposer = true
                        state.agent = kind
                    }
                } else {
                    Text(
                        "${kind.label} — not found",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = form.showModelSection,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                form.cliStatuses.firstOrNull { it.kind == state.agent }?.version?.let { version ->
                    Text(version, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
                Text("Model", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("provider default", state.modelId == null, Cyan) { state.modelId = null }
                    AgentModelCatalog.options(state.agent).forEach { option ->
                        FilterPill(option.label, state.modelId == option.id, agentColor(state.agent)) { state.modelId = option.id }
                    }
                    FilterPill("custom", state.usesCustomModel, Rust) { state.modelId = CUSTOM_MODEL_ID }
                }
                if (state.usesCustomModel) {
                    LabeledField(
                        "Exact model / variant",
                        state.customModel,
                        { state.customModel = it },
                        Modifier.fillMaxWidth(),
                        placeholder = "passed to ${state.agent.cliName} exactly",
                    )
                    Text("Custom variants are passed as-is, so Andy does not add a reasoning or speed suffix.", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                } else if (form.selectedModel != null) {
                    Text("Reasoning", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("provider default", state.reasoningEffort == null, Cyan) { state.reasoningEffort = null }
                        form.selectedModel.efforts.forEach { effort ->
                            FilterPill(effort.label, state.reasoningEffort == effort, Rust) { state.reasoningEffort = effort }
                        }
                    }
                    if (form.selectedModel.supportsFastMode) {
                        FilterPill("fast", state.fastMode, Green) { state.fastMode = !state.fastMode }
                    }
                    Text(
                        when (state.agent) {
                            AgentKind.Cursor -> "Cursor receives the selected provider variant, e.g. Grok 4.5 High Fast. Availability follows your Cursor account."
                            AgentKind.Antigravity -> "Antigravity receives its model plus level as one variant; its installed CLI/account remains authoritative."
                            else -> "The selected model and reasoning level are passed directly to the ${state.agent.label} CLI."
                        },
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    placeholder = {
                        Text("type / for skills — drop image files here to attach them", color = TextSecondary, fontFamily = MonoFont)
                    },
                )
                DropdownMenu(
                    expanded = form.slashCommand != null && !state.skillMenuDismissed,
                    onDismissRequest = { state.skillMenuDismissed = true },
                    modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                    properties = PopupProperties(focusable = false),
                ) {
                    Text(
                        if (form.matchingSkills.isEmpty()) {
                            "no skills matching /${form.slashCommand?.query.orEmpty()}"
                        } else {
                            "skills matching /${form.slashCommand?.query.orEmpty()}"
                        },
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
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
        if (state.imageDragActive) {
            Text("release to attach image", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        } else if (state.imagePaths.isEmpty()) {
            Text("drop image files onto the prompt to attach them", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        } else {
            Text("Attached images", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.imagePaths.forEach { path ->
                    FilterPill("${imageFileName(path)} ×", false, Cyan) {
                        state.imagePaths = state.imagePaths.filterNot { it == path }
                    }
                }
            }
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
        if (form.showModelSection && state.agent == AgentKind.ClaudeCode) {
            LabeledField("Max budget USD (optional)", state.budgetText, { state.budgetText = it }, Modifier.fillMaxWidth(), placeholder = "e.g. 2.50")
        }
    }
}

private const val CUSTOM_MODEL_ID = "__custom__"

private fun imageFileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

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
