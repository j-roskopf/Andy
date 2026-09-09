package com.joetr.andy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.andy.ui.components.Button
import app.andy.ui.components.IconButton
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.attention.AttentionPushService
import com.joetr.andy.mobile.data.networkaccess.AgentDto
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.ProjectDto
import com.joetr.andy.mobile.data.networkaccess.StartChatRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    client: NetworkAccessClient,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var agents by remember { mutableStateOf<List<AgentDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var prompt by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf("") }
    var autonomy by remember { mutableStateOf("Standard") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var requiresModel by remember { mutableStateOf(false) }
    var runtime by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val slashCommands = rememberSlashCommands(client, agentId)
    val slashToken = remember(prompt) { findActiveSlashToken(prompt) }

    LaunchedEffect(Unit) {
        runCatching {
            agents = client.listAgents().filter { it.webChat }
            projects = client.listProjects()
            agentId = agents.firstOrNull()?.id.orEmpty()
        }.onFailure { error = it.message }
    }

    LaunchedEffect(agentId) {
        if (agentId.isBlank()) return@LaunchedEffect
        runCatching {
            val response = client.listModels(agentId)
            models = response.models.map { it.id }
            model = response.defaultModel.orEmpty()
            requiresModel = response.requiresModel || response.models.isNotEmpty() && response.defaultModel == null
            runtime = response.defaultRuntime
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.palette.windowBg)
            .imePadding()
            .padding(AndySpace.Space4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(AndyLayout.ControlHeightMd),
                contentDescription = "Back",
            ) {
                LucideIcon(
                    Lucide.ArrowLeft,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = tokens.palette.textPrimary,
                )
            }
            Text(
                "New chat",
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                color = tokens.palette.textPrimary,
            )
        }
        Spacer(Modifier.height(AndySpace.Space3))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            MobileField(
                label = "Prompt",
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "What should the agent do? Type / for commands",
                singleLine = false,
            )
            if (slashToken != null) {
                SlashCommandSuggestions(
                    query = slashToken.query,
                    commands = slashCommands,
                    onSelect = { command ->
                        prompt = insertSlashCommand(prompt, slashToken, command.name)
                    },
                )
            }
            SimpleDropdown(
                label = "Agent",
                value = agentId,
                options = agents.map { it.id to it.label.ifBlank { it.id } },
                onSelected = { agentId = it },
            )
            SimpleDropdown(
                label = "Project",
                value = projectId,
                options = listOf("" to "No project") + projects.map { it.id to it.name },
                onSelected = { projectId = it },
            )
            SimpleDropdown(
                label = "Autonomy",
                value = autonomy,
                options = listOf("ReadOnly", "Standard", "Full").map { it to it },
                onSelected = { autonomy = it },
            )
            if (models.isNotEmpty()) {
                SimpleDropdown(
                    label = "Model",
                    value = model,
                    options = models.map { it to it },
                    onSelected = { model = it },
                )
            }
            error?.let { Text(it, color = tokens.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Button(
            onClick = {
                if (prompt.isBlank() || agentId.isBlank()) {
                    error = "Prompt and agent are required"
                    return@Button
                }
                if (requiresModel && model.isBlank()) {
                    error = "Choose a model to continue"
                    return@Button
                }
                // Re-arm the listener from the foreground click path. startChat awaits the host,
                // so starting the service only in onStarted can run after the user backgrounds the
                // app, which Android 12+ rejects with ForegroundServiceStartNotAllowedException.
                AttentionPushService.ensureRunning(context)
                scope.launch {
                    starting = true
                    error = null
                    try {
                        val response = client.startChat(
                            StartChatRequest(
                                prompt = prompt.trim(),
                                agent = agentId,
                                autonomy = autonomy,
                                projectId = projectId.takeIf { it.isNotBlank() },
                                model = model.takeIf { it.isNotBlank() },
                                runtime = runtime,
                            ),
                        )
                        val id = response.id
                        if (id.isNullOrBlank()) {
                            error = response.error ?: "Start failed"
                        } else {
                            onStarted(id)
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        starting = false
                    }
                }
            },
            enabled = !starting,
            modifier = Modifier.fillMaxWidth(),
            shape = AndyShape.Interactive,
        ) {
            Text(if (starting) "Starting…" else "Start chat")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    val tokens = andyTokens()
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = AndyShape.Interactive,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accent,
                unfocusedBorderColor = tokens.palette.borderMedium,
                focusedLabelColor = tokens.accent,
                focusedTextColor = tokens.palette.textPrimary,
                unfocusedTextColor = tokens.palette.textPrimary,
                focusedContainerColor = tokens.palette.surfaceRaised,
                unfocusedContainerColor = tokens.palette.surfaceRaised,
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = AndyShape.Menu,
            containerColor = tokens.palette.surfacePopover,
        ) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text, color = tokens.palette.textPrimary) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
