package app.andy.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentPickerOption
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.LocalAgentRuntime
import app.andy.model.agentPickerOptions
import app.andy.model.comboReady
import app.andy.model.agentModelMenuSections
import app.andy.model.isLocalModelBackend
import app.andy.model.labelFor
import app.andy.model.runtimeKind
import app.andy.ui.components.ComposerEffortChip
import app.andy.ui.components.ComposerModelChip
import app.andy.ui.components.ComposerPermissionsChip
import app.andy.ui.components.ComposerProviderChip
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

const val ComposerCustomModelId = "__custom__"

@Composable
fun ComposerProfileChips(
    agent: AgentKind,
    localRuntime: LocalAgentRuntime?,
    modelId: String?,
    reasoningEffort: AgentReasoningEffort?,
    sandboxMode: AgentSandboxMode,
    cliStatuses: List<AgentCliStatus>,
    localBackends: Map<AgentKind, Boolean>,
    modelOptions: List<AgentModelOption>,
    selectedModel: AgentModelOption?,
    onAgentChange: (AgentKind, LocalAgentRuntime?) -> Unit,
    onModelChange: (String?) -> Unit,
    onReasoningEffortChange: (AgentReasoningEffort?) -> Unit,
    onSandboxChange: (AgentSandboxMode) -> Unit,
    onRefreshProviders: (() -> Unit)? = null,
    refreshingProviders: Boolean = false,
) {
    var agentMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var permissionsMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    val runtime = agent.runtimeKind(localRuntime)
    val modelLabel = when {
        modelId == ComposerCustomModelId -> "custom"
        selectedModel != null -> selectedModel.label
        else -> "Default model"
    }
    val permissionsLabel = sandboxMode.labelFor(runtime)

    Box {
        ComposerProviderChip(
            text = AgentPickerOption(agent, localRuntime.takeIf { agent.isLocalModelBackend }).label,
            onClick = { agentMenuExpanded = true },
            leadingContent = { AgentPillIcon(agent) },
        )
        DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
            agentPickerOptions().forEach { option ->
                val ready = option.comboReady(cliStatuses, localBackends)
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AgentPillIcon(option.agent)
                            Text(
                                "${option.label}${if (ready) "" else " · unavailable"}",
                                color = TextPrimary,
                            )
                        }
                    },
                    enabled = ready,
                    onClick = {
                        onAgentChange(option.agent, option.localRuntime)
                        agentMenuExpanded = false
                    },
                )
            }
            if (onRefreshProviders != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (refreshingProviders) "refreshing providers…" else "refresh providers",
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                        )
                    },
                    enabled = !refreshingProviders,
                    onClick = {
                        agentMenuExpanded = false
                        onRefreshProviders()
                    },
                )
            }
        }
    }
    Box {
        ComposerModelChip(
            text = modelLabel,
            onClick = { modelMenuExpanded = true },
        )
        DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
            if (!agent.isLocalModelBackend) {
                DropdownMenuItem(
                    text = { Text("provider default", color = TextPrimary) },
                    onClick = {
                        onModelChange(null)
                        modelMenuExpanded = false
                    },
                )
            }
            val modelSections = agentModelMenuSections(agent, modelOptions)
            if (modelSections != null) {
                modelSections.forEach { section ->
                    section.header?.let { header ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    header,
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    section.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = TextPrimary) },
                            onClick = {
                                onModelChange(option.id)
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            } else {
                modelOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = TextPrimary) },
                        onClick = {
                            onModelChange(option.id)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("custom", color = TextPrimary) },
                onClick = {
                    onModelChange(ComposerCustomModelId)
                    modelMenuExpanded = false
                },
            )
        }
    }
    selectedModel?.takeIf { it.efforts.isNotEmpty() }?.let { model ->
        Box {
            ComposerEffortChip(
                text = reasoningEffort?.label ?: "Effort",
                onClick = { effortMenuExpanded = true },
            )
            DropdownMenu(expanded = effortMenuExpanded, onDismissRequest = { effortMenuExpanded = false }) {
                if (agent != AgentKind.Cursor) {
                    DropdownMenuItem(
                        text = { Text("provider default", color = TextPrimary) },
                        onClick = {
                            onReasoningEffortChange(null)
                            effortMenuExpanded = false
                        },
                    )
                }
                model.efforts.forEach { effort ->
                    DropdownMenuItem(
                        text = { Text(effort.label, color = TextPrimary) },
                        onClick = {
                            onReasoningEffortChange(effort)
                            effortMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
    Box {
        ComposerPermissionsChip(
            text = permissionsLabel,
            onClick = { permissionsMenuExpanded = true },
        )
        DropdownMenu(expanded = permissionsMenuExpanded, onDismissRequest = { permissionsMenuExpanded = false }) {
            AgentSandboxMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.labelFor(runtime), color = TextPrimary) },
                    onClick = {
                        onSandboxChange(mode)
                        permissionsMenuExpanded = false
                    },
                )
            }
        }
    }
}
