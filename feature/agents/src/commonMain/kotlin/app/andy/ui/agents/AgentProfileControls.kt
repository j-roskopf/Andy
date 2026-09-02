package app.andy.ui.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentPickerOption
import app.andy.model.ProjectAgentProfile
import app.andy.model.agentPickerOptions
import app.andy.model.comboReady
import app.andy.model.agentModelMenuSections
import app.andy.model.isLocalModelBackend
import app.andy.model.runtimeKind
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

/** Provider/model controls shared by the generic agent composer and project role profiles. */
@Composable
fun AgentProviderModelProfileControls(
    profile: ProjectAgentProfile,
    onChange: (ProjectAgentProfile) -> Unit,
    cliStatuses: List<AgentCliStatus>,
    providerModels: Map<AgentKind, List<AgentModelOption>> = emptyMap(),
    localBackends: Map<AgentKind, Boolean> = emptyMap(),
    providerSelectionActive: Boolean = true,
    showProviderControls: Boolean = true,
    showModelControls: Boolean = true,
    showUnavailableAsPills: Boolean = true,
    showProviderIcons: Boolean = true,
    showVersion: Boolean = false,
    showModelHelp: Boolean = false,
    wrapOptions: Boolean = false,
    showModelLabel: Boolean = true,
) {
    val modelOptions = AgentModelCatalog.options(profile.agent, providerModels)
    val selectedModel = AgentModelCatalog.option(profile.agent, profile.model, providerModels)
    val customModel = profile.model != null && selectedModel == null
    val groupedModels = agentModelMenuSections(profile.agent, modelOptions)

    if (showProviderControls) {
        Text("Agent", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        ProfileOptionRow(wrapOptions) {
            agentPickerOptions().forEach { option ->
                val ready = option.comboReady(cliStatuses, localBackends)
                val selected = ready && providerSelectionActive &&
                    profile.agent == option.agent &&
                    profile.localRuntime == option.localRuntime
                if (ready || showUnavailableAsPills) {
                    FilterPill(
                        text = "${option.label}${if (ready) "" else " · unavailable"}",
                        selected = selected,
                        color = agentColor(option.agent),
                        enabled = ready,
                        leadingContent = if (showProviderIcons) ({ AgentPillIcon(option.agent) }) else null,
                    ) {
                        onChange(
                            profile.copy(
                                agent = option.agent,
                                localRuntime = option.localRuntime,
                                model = null,
                                reasoningEffort = null,
                                fastMode = false,
                            ),
                        )
                    }
                } else {
                    Text(
                        "${option.label} — not found",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }

    if (!showModelControls) return
    if (showVersion) {
        cliStatuses.firstOrNull { it.kind == profile.runtimeKind() }?.version?.let { version ->
            Text(version, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        }
    }
    if (showModelLabel) {
        Text("Model", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
    if (groupedModels != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileOptionRow(wrapOptions) {
                if (!profile.agent.isLocalModelBackend) {
                    FilterPill("provider default", profile.model == null, Cyan) {
                        onChange(profile.copy(model = null, reasoningEffort = null, fastMode = false))
                    }
                }
                FilterPill("custom", customModel, Rust) {
                    onChange(profile.copy(model = profile.model.takeIf { customModel }.orEmpty(), reasoningEffort = null, fastMode = false))
                }
            }
            groupedModels.forEach { section ->
                section.header?.let { header ->
                    Text(
                        header,
                        color = TextSecondary.copy(alpha = 0.75f),
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                }
                ProfileOptionRow(wrapOptions) {
                    section.options.forEach { option ->
                        FilterPill(option.label, selectedModel?.id == option.id, agentColor(profile.agent)) {
                            onChange(profile.copy(model = option.id, reasoningEffort = null, fastMode = option.fastRequired))
                        }
                    }
                }
            }
        }
    } else {
        ProfileOptionRow(wrapOptions) {
            if (!profile.agent.isLocalModelBackend) {
                FilterPill("provider default", profile.model == null, Cyan) {
                    onChange(profile.copy(model = null, reasoningEffort = null, fastMode = false))
                }
            }
            modelOptions.forEach { option ->
                FilterPill(option.label, selectedModel?.id == option.id, agentColor(profile.agent)) {
                    onChange(profile.copy(model = option.id, reasoningEffort = null, fastMode = option.fastRequired))
                }
            }
            FilterPill("custom", customModel, Rust) {
                onChange(profile.copy(model = profile.model.takeIf { customModel }.orEmpty(), reasoningEffort = null, fastMode = false))
            }
        }
    }
    if (customModel) {
        LabeledField(
            "Exact model / variant",
            profile.model.orEmpty(),
            { onChange(profile.copy(model = it, reasoningEffort = null, fastMode = false)) },
            Modifier.fillMaxWidth(),
            placeholder = "passed to ${profile.agent.cliName} exactly",
        )
        if (showModelHelp) {
            Text(
                "Custom variants are passed as-is, so Andy does not add a reasoning or speed suffix.",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
    } else if (selectedModel != null) {
        if (selectedModel.efforts.isNotEmpty()) {
            Text("Reasoning", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            ProfileOptionRow(wrapOptions) {
                if (profile.agent != AgentKind.Cursor) {
                    FilterPill("provider default", profile.reasoningEffort == null, Cyan) {
                        onChange(profile.copy(reasoningEffort = null))
                    }
                }
                selectedModel.efforts.forEach { effort ->
                    FilterPill(effort.label, profile.reasoningEffort == effort, Rust) {
                        onChange(profile.copy(reasoningEffort = effort))
                    }
                }
            }
        }
        if (selectedModel.supportsFastMode && !selectedModel.fastRequired) {
            FilterPill("fast", profile.fastMode, app.andy.ui.theme.Green) {
                onChange(profile.copy(fastMode = !profile.fastMode))
            }
        }
        if (showModelHelp) {
            Text(
                when (profile.runtimeKind()) {
                    AgentKind.Cursor -> "Cursor receives the selected provider variant. Availability follows your Cursor account."
                    AgentKind.Antigravity -> "Antigravity receives its model slug plus effort as one variant from the live CLI model list."
                    AgentKind.OpenCode -> "OpenCode receives provider/model slugs (for example anthropic/claude-sonnet-5). Availability follows your configured providers."
                    AgentKind.Goose -> "Goose receives provider/model slugs (for example anthropic/claude-sonnet-4-5). Availability follows goose configure."
                    AgentKind.Pi -> "Pi receives provider/model ids; thinking effort is passed via --thinking."
                    else -> "The selected model and reasoning level are passed directly to the ${profile.agent.label} CLI."
                },
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileOptionRow(
    wrapOptions: Boolean,
    content: @Composable () -> Unit,
) {
    if (wrapOptions) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    } else {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
