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
import kotlinx.coroutines.launch

@Composable
internal fun AgentTaskComposer(
    services: AndyServices,
    cliStatuses: List<AgentCliStatus>,
    projectContext: ActionProject? = null,
    onDismiss: () -> Unit,
    onSubmit: (AgentTaskDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()
    val lastUsedAgent by services.agentRuns.lastUsedAgent.collectAsState()
    val availableSkills by services.agentRuns.availableSkills.collectAsState()
    var promptValue by remember { mutableStateOf(TextFieldValue("")) }
    val prompt = promptValue.text
    var skillMenuDismissed by remember { mutableStateOf(false) }
    var imagePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var imageDragActive by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf(cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode) }
    var providerChosenInComposer by remember { mutableStateOf(false) }
    var customDirectory by remember(projectContext) { mutableStateOf("") }
    var usesCustomDirectory by remember(projectContext) { mutableStateOf(false) }
    var useWorktree by remember { mutableStateOf(false) }
    var attachMcp by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(AgentAutonomy.Standard) }
    var modelId by remember(agent) { mutableStateOf<String?>(null) }
    var customModel by remember(agent) { mutableStateOf("") }
    var reasoningEffort by remember(agent) { mutableStateOf<AgentReasoningEffort?>(null) }
    var fastMode by remember(agent) { mutableStateOf(false) }
    var budgetText by remember { mutableStateOf("") }
    var directoryIsGitRepo by remember { mutableStateOf(false) }

    val directory = projectContext?.contextDir
        ?: customDirectory.takeIf { usesCustomDirectory && it.isNotBlank() }
    val selectedCliAvailable = cliStatuses.any { it.kind == agent && it.available }
    val showModelSection = providerChosenInComposer && selectedCliAvailable
    val selectedModel = AgentModelCatalog.option(agent, modelId)
    val usesCustomModel = modelId == CUSTOM_MODEL_ID
    val slashCommand = findComposerSlashCommand(prompt)
    val matchingSkills = slashCommand?.let { command ->
        availableSkills.filter { skill ->
            skill.name.contains(command.query, ignoreCase = true) ||
                skill.description.contains(command.query, ignoreCase = true)
        }.take(8)
    }.orEmpty()
    val selectedSkills = remember(prompt, availableSkills) {
        availableSkills.filter { skill -> prompt.referencesComposerSkill(skill) }
    }

    fun selectSkill(skill: AgentSkill) {
        val command = findComposerSlashCommand(prompt) ?: return
        val insertion = "/${skill.name}"
        promptValue = TextFieldValue(
            text = prompt.replaceRange(command.start, command.end, insertion),
            selection = TextRange(command.start + insertion.length),
        )
        skillMenuDismissed = true
    }

    LaunchedEffect(lastUsedAgent, cliStatuses) {
        if (!providerChosenInComposer) {
            val preferred = lastUsedAgent?.takeIf { preferred ->
                cliStatuses.any { it.kind == preferred && it.available }
            }
            agent = preferred ?: cliStatuses.firstOrNull { it.available }?.kind ?: AgentKind.ClaudeCode
        }
    }

    fun applyProviderDefaults(defaults: AgentProviderDefaults?) {
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

    LaunchedEffect(agent, providerDefaults[agent]) {
        applyProviderDefaults(providerDefaults[agent])
    }

    LaunchedEffect(directory, useWorktree) {
        directoryIsGitRepo = directory?.let { services.agentRuns.isGitRepo(it) } == true
        if (!directoryIsGitRepo) useWorktree = false
    }
    LaunchedEffect(agent, modelId) {
        if (usesCustomModel || selectedModel?.efforts?.contains(reasoningEffort) != true) reasoningEffort = null
        if (selectedModel?.supportsFastMode != true) fastMode = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("New agent task", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.width(720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Agent", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentKind.entries.forEach { kind ->
                        val status = cliStatuses.firstOrNull { it.kind == kind }
                        if (status?.available == true) {
                            FilterPill(
                                "${agentMonogram(kind)} ${kind.label}",
                                providerChosenInComposer && agent == kind,
                                agentColor(kind),
                            ) {
                                providerChosenInComposer = true
                                agent = kind
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
                    visible = showModelSection,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        cliStatuses.firstOrNull { it.kind == agent }?.version?.let { version ->
                            Text(version, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                        }
                        Text("Model", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("provider default", modelId == null, Cyan) { modelId = null }
                            AgentModelCatalog.options(agent).forEach { option ->
                                FilterPill(option.label, modelId == option.id, agentColor(agent)) { modelId = option.id }
                            }
                            FilterPill("custom", usesCustomModel, Rust) { modelId = CUSTOM_MODEL_ID }
                        }
                        if (usesCustomModel) {
                            LabeledField(
                                "Exact model / variant",
                                customModel,
                                { customModel = it },
                                Modifier.fillMaxWidth(),
                                placeholder = "passed to ${agent.cliName} exactly",
                            )
                            Text("Custom variants are passed as-is, so Andy does not add a reasoning or speed suffix.", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                        } else if (selectedModel != null) {
                            Text("Reasoning", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterPill("provider default", reasoningEffort == null, Cyan) { reasoningEffort = null }
                                selectedModel.efforts.forEach { effort ->
                                    FilterPill(effort.label, reasoningEffort == effort, Rust) { reasoningEffort = effort }
                                }
                            }
                            if (selectedModel.supportsFastMode) {
                                FilterPill("fast", fastMode, Green) { fastMode = !fastMode }
                            }
                            Text(
                                when (agent) {
                                    AgentKind.Cursor -> "Cursor receives the selected provider variant, e.g. Grok 4.5 High Fast. Availability follows your Cursor account."
                                    AgentKind.Antigravity -> "Antigravity receives its model plus level as one variant; its installed CLI/account remains authoritative."
                                    else -> "The selected model and reasoning level are passed directly to the ${agent.label} CLI."
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
                            promptValue,
                            {
                                promptValue = it
                                skillMenuDismissed = false
                            },
                            singleLine = false,
                            minLines = 6,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 140.dp)
                                .onImageFilesDropped(
                                    onFiles = { dropped -> imagePaths = (imagePaths + dropped).distinct() },
                                    onDragActiveChange = { active -> imageDragActive = active },
                                )
                                .border(
                                    if (imageDragActive) 2.dp else 1.dp,
                                    if (imageDragActive) Cyan else Border,
                                    RoundedCornerShape(AndyRadius.R3),
                                )
                                .background(
                                    if (imageDragActive) Cyan.copy(alpha = 0.12f) else AndyColors.Neutral900.copy(alpha = 0.2f),
                                    RoundedCornerShape(AndyRadius.R3),
                                ),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                            colors = fieldColors(),
                            placeholder = {
                                Text("type / for skills — drop image files here to attach them", color = TextSecondary, fontFamily = MonoFont)
                            },
                        )
                        DropdownMenu(
                            expanded = slashCommand != null && !skillMenuDismissed,
                            onDismissRequest = { skillMenuDismissed = true },
                            modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                            properties = PopupProperties(focusable = false),
                        ) {
                            Text(
                                if (matchingSkills.isEmpty()) "no skills matching /${slashCommand?.query.orEmpty()}" else "skills matching /${slashCommand?.query.orEmpty()}",
                                color = TextSecondary,
                                fontFamily = MonoFont,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                            matchingSkills.forEach { skill ->
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
                    if (selectedSkills.isNotEmpty()) {
                        Text("selected skills", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            selectedSkills.forEach { skill ->
                                FilterPill("/${skill.name} ×", true, Cyan) {
                                    promptValue = TextFieldValue(prompt.removeComposerSkill(skill))
                                }
                            }
                        }
                    }
                }
                if (imageDragActive) {
                    Text("release to attach image", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                } else if (imagePaths.isEmpty()) {
                    Text("drop image files onto the prompt to attach them", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                } else {
                    Text("Attached images", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        imagePaths.forEach { path ->
                            FilterPill("${imageFileName(path)} ×", false, Cyan) {
                                imagePaths = imagePaths.filterNot { it == path }
                            }
                        }
                    }
                }

                if (projectContext != null) {
                    Text("Project context", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text(projectContext.name, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
                    Text(projectContext.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                } else {
                    Text("Context (optional)", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("no context", !usesCustomDirectory, Cyan) { usesCustomDirectory = false }
                        FilterPill("custom dir", usesCustomDirectory, Cyan) {
                            usesCustomDirectory = true
                        }
                    }
                }
                if (projectContext == null && usesCustomDirectory) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            customDirectory,
                            { customDirectory = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(54.dp),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                            colors = fieldColors(),
                            placeholder = { Text("directory the agent works in", color = TextSecondary, fontFamily = MonoFont) },
                        )
                        Button(onClick = { scope.launch { pickDirectory(customDirectory.ifBlank { null })?.let { customDirectory = it } } }, colors = primaryButtonColors()) { Text("browse") }
                    }
                }

                Text("Options", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (directoryIsGitRepo) {
                        FilterPill("isolate in git worktree", useWorktree, Green) { useWorktree = !useWorktree }
                    }
                    FilterPill("andy device tools (mcp)", attachMcp, Cyan) { attachMcp = !attachMcp }
                }

                Text("Autonomy", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentAutonomy.entries.forEach { level ->
                        FilterPill(level.label, autonomy == level, Rust) { autonomy = level }
                    }
                }
                Text(
                    "standard may stop when the agent needs approval; use full for unattended runs in trusted or worktree dirs",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
                if (showModelSection && agent == AgentKind.ClaudeCode) {
                    LabeledField("Max budget USD (optional)", budgetText, { budgetText = it }, Modifier.fillMaxWidth(), placeholder = "e.g. 2.50")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        AgentTaskDraft(
                            title = "",
                            prompt = prompt.trim(),
                            agent = agent,
                            projectId = projectContext?.id,
                            directory = directory?.trim()?.takeIf { it.isNotBlank() },
                            useWorktree = useWorktree,
                            attachAndyMcp = attachMcp,
                            autonomy = autonomy,
                            model = if (usesCustomModel) customModel.trim().ifBlank { null } else modelId,
                            reasoningEffort = if (usesCustomModel) null else reasoningEffort,
                            fastMode = if (usesCustomModel) false else fastMode,
                            imagePaths = imagePaths,
                            skills = selectedSkills,
                            maxBudgetUsd = budgetText.trim().toDoubleOrNull(),
                        ),
                    )
                },
                enabled = prompt.isNotBlank() && (!usesCustomModel || customModel.isNotBlank()) && cliStatuses.any { it.kind == agent && it.available },
                colors = primaryButtonColors(),
            ) { Text("Start") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
