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
import app.andy.model.ProjectAgentProfile
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

/** Provider/model controls shared by the generic agent composer and project role profiles. */
@Composable
internal fun AgentProviderModelProfileControls(
    profile: ProjectAgentProfile,
    onChange: (ProjectAgentProfile) -> Unit,
    cliStatuses: List<AgentCliStatus>,
    providerSelectionActive: Boolean = true,
    showProviderControls: Boolean = true,
    showModelControls: Boolean = true,
    showUnavailableAsPills: Boolean = false,
    showProviderIcons: Boolean = true,
    showVersion: Boolean = false,
    showModelHelp: Boolean = false,
    wrapOptions: Boolean = false,
    showModelLabel: Boolean = true,
) {
    val selectedModel = AgentModelCatalog.option(profile.agent, profile.model)
    val customModel = profile.model != null && selectedModel == null

    if (showProviderControls) {
        Text("Agent", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        ProfileOptionRow(wrapOptions) {
            AgentKind.entries.forEach { agent ->
                val status = cliStatuses.firstOrNull { it.kind == agent }
                val ready = status?.ready == true || cliStatuses.isEmpty()
                if (ready || showUnavailableAsPills) {
                    FilterPill(
                        text = "${agent.label}${if (ready) "" else " · unavailable"}",
                        selected = providerSelectionActive && profile.agent == agent,
                        color = agentColor(agent),
                        leadingContent = if (showProviderIcons) ({ AgentPillIcon(agent) }) else null,
                    ) {
                        if (ready) onChange(
                            profile.copy(
                                agent = agent,
                                model = null,
                                reasoningEffort = null,
                                fastMode = false,
                            ),
                        )
                    }
                } else {
                    Text(
                        "${agent.label} — ${if (status?.issue != null) "needs repair" else "not found"}",
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
        cliStatuses.firstOrNull { it.kind == profile.agent }?.version?.let { version ->
            Text(version, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        }
    }
    if (showModelLabel) {
        Text("Model", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
    ProfileOptionRow(wrapOptions) {
        FilterPill("provider default", profile.model == null, Cyan) {
            onChange(profile.copy(model = null, reasoningEffort = null, fastMode = false))
        }
        AgentModelCatalog.options(profile.agent).forEach { option ->
            FilterPill(option.label, selectedModel?.id == option.id, agentColor(profile.agent)) {
                onChange(profile.copy(model = option.id, reasoningEffort = null, fastMode = false))
            }
        }
        FilterPill("custom", customModel, Rust) {
            onChange(profile.copy(model = profile.model.takeIf { customModel }.orEmpty(), reasoningEffort = null, fastMode = false))
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
        if (selectedModel.supportsFastMode) {
            FilterPill("fast", profile.fastMode, app.andy.ui.theme.Green) {
                onChange(profile.copy(fastMode = !profile.fastMode))
            }
        }
        if (showModelHelp) {
            Text(
                when (profile.agent) {
                    AgentKind.Cursor -> "Cursor receives the selected provider variant. Availability follows your Cursor account."
                    AgentKind.Antigravity -> "Antigravity receives its model plus level as one variant; its installed CLI/account remains authoritative."
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
