package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentPickerOption
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.LocalAgentRuntime
import app.andy.model.WorkspaceState
import app.andy.model.comboReady
import app.andy.model.isLocalModelBackend
import app.andy.service.AndyServices
import app.andy.ui.agents.AgentTaskDetail
import app.andy.ui.agents.ComposerCustomModelId
import app.andy.ui.agents.ComposerModelSelection
import app.andy.ui.agents.ComposerProfileChips
import app.andy.ui.agents.composerModelSelection
import app.andy.ui.agents.composerModelSelectionAfterCatalogUpdate
import app.andy.ui.components.ChatComposerLayout
import app.andy.ui.components.attachImagesFromPicker
import app.andy.ui.components.ChatSendButton
import app.andy.ui.components.ComposerPlaceholderHint
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.components.insertTextAtCursor
import kotlinx.coroutines.launch
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun SideChatPaneView(
    services: AndyServices,
    tab: DockTab,
    workspaceState: WorkspaceState,
    launching: Boolean,
    dictationActive: Boolean,
    onStart: (prompt: String, launch: SideChatLaunchConfig) -> Unit,
    viewedAgentTaskId: String? = null,
    modifier: Modifier = Modifier,
) {
    val tasks by services.agentRuns.tasks.collectAsState()
    val child = tab.agentTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    val parentId = tab.parentChatTaskId ?: viewedAgentTaskId
    val parent = parentId?.let { id -> tasks.firstOrNull { it.id == id } }
    val paneModifier = modifier.fillMaxSize().background(AndyColors.ContentBg)

    DisposableEffect(dictationActive, child?.id) {
        val taskId = child?.id?.takeIf { dictationActive }
        if (taskId != null) services.agentRuns.setChatViewing(taskId, viewing = true)
        onDispose {
            if (taskId != null) services.agentRuns.setChatViewing(taskId, viewing = false)
        }
    }

    when {
        child != null -> AgentTaskDetail(
            services = services,
            task = child,
            onDelete = {},
            showHeader = false,
            showDeleteDetailsActions = false,
            workspaceState = workspaceState,
            modifier = paneModifier,
            dictationActive = dictationActive,
        )
        parent == null -> EmptyState(
            "Open a chat first, then start a side chat from here",
            modifier = paneModifier,
        )
        else -> SideChatStarter(
            services = services,
            parent = parent,
            launching = launching,
            onStart = onStart,
            modifier = paneModifier,
        )
    }
}

@Composable
private fun SideChatStarter(
    services: AndyServices,
    parent: AgentTask,
    launching: Boolean,
    onStart: (prompt: String, launch: SideChatLaunchConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()
    val providerModels by services.agentRuns.providerModels.collectAsState()
    val localBackends by services.agentRuns.localModelBackends.collectAsState()

    var draft by remember(parent.id) { mutableStateOf("") }
    var agentChosen by remember(parent.id) { mutableStateOf(false) }
    var agent by remember(parent.id) { mutableStateOf(sideChatAgent(parent.agent, cliStatuses)) }
    var localRuntime by remember(parent.id) { mutableStateOf<LocalAgentRuntime?>(null) }
    var modelId by remember(parent.id) { mutableStateOf<String?>(null) }
    var customModel by remember(parent.id) { mutableStateOf("") }
    var reasoningEffort by remember(parent.id) { mutableStateOf<AgentReasoningEffort?>(null) }
    var sandboxMode by remember(parent.id) { mutableStateOf(AgentSandboxMode.ReadOnly) }
    var seededForAgent by remember(parent.id) { mutableStateOf<AgentKind?>(null) }
    val scope = rememberCoroutineScope()
    var refreshingProviders by remember { mutableStateOf(false) }

    LaunchedEffect(parent.id, cliStatuses) {
        if (!agentChosen) agent = sideChatAgent(parent.agent, cliStatuses)
    }
    LaunchedEffect(agent, providerDefaults, providerModels) {
        val defaults = providerDefaults[agent]
        if (seededForAgent != agent) {
            val selection = composerModelSelection(agent, defaults?.model, providerModels)
            modelId = selection.modelId
            customModel = selection.customModel
            reasoningEffort = defaults?.reasoningEffort
            localRuntime = when {
                !agent.isLocalModelBackend -> null
                else -> localRuntime ?: defaults?.localRuntime ?: LocalAgentRuntime.OpenCode
            }
            seededForAgent = agent
        } else {
            val next = composerModelSelectionAfterCatalogUpdate(
                ComposerModelSelection(modelId, customModel),
                agent,
                defaults?.model,
                providerModels,
            )
            if (next.modelId != modelId || next.customModel != customModel) {
                modelId = next.modelId
                customModel = next.customModel
                reasoningEffort = defaults?.reasoningEffort
            }
        }
    }

    val modelOptions = AgentModelCatalog.options(agent, providerModels)
    val selectedModel = AgentModelCatalog.option(agent, modelId, providerModels)
    LaunchedEffect(agent, modelId, selectedModel) {
        val model = selectedModel
        reasoningEffort = when {
            modelId == ComposerCustomModelId || model == null || model.efforts.isEmpty() -> null
            reasoningEffort !in model.efforts -> {
                if (agent == AgentKind.Cursor) model.preferredEffort() else null
            }
            else -> reasoningEffort
        }
    }

    val agentReady = AgentPickerOption(
        agent,
        localRuntime.takeIf { agent.isLocalModelBackend },
    ).comboReady(cliStatuses, localBackends)
    val localModelChosen = !agent.isLocalModelBackend ||
        (localRuntime != null && (
            (modelId == ComposerCustomModelId && customModel.isNotBlank()) ||
                (modelId != ComposerCustomModelId && !modelId.isNullOrBlank())
            ))
    val canSend = draft.isNotBlank() && !launching && agentReady && localModelChosen &&
        (modelId != ComposerCustomModelId || customModel.isNotBlank())

    fun launchConfig() = SideChatLaunchConfig(
        agent = agent,
        localRuntime = localRuntime,
        model = if (modelId == ComposerCustomModelId) customModel.trim().ifBlank { null } else modelId,
        reasoningEffort = reasoningEffort,
        sandboxMode = sandboxMode,
    )

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || !canSend) return
        onStart(text, launchConfig())
    }

    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(AndySpace.Space4),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Text(
                "Side chat",
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 16.sp,
            )
            Text(
                "Context from ${parent.title} · ${parent.agent.label}",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Ask a question. The new agent gets a snapshot of this chat and shares its workspace.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
        val scope = rememberCoroutineScope()
        var draftField by remember { mutableStateOf(TextFieldValue(draft)) }
        LaunchedEffect(draft) {
            if (draftField.text != draft) draftField = TextFieldValue(draft)
        }
        ChatComposerLayout(
            modifier = Modifier.fillMaxWidth(),
            onMentionClick = { draftField = insertTextAtCursor(draftField, "@"); draft = draftField.text },
            onAttachClick = {
                scope.launch {
                    attachImagesFromPicker { /* side chat does not support images yet */ }
                }
            },
            attachEnabled = false,
            input = {
                Box(Modifier.fillMaxWidth()) {
                    TextField(
                        value = draftField,
                        onValueChange = {
                            draftField = it
                            draft = it.text
                        },
                        enabled = !launching,
                        singleLine = false,
                        minLines = 2,
                        maxLines = 7,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 180.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                                if (event.isShiftPressed) return@onPreviewKeyEvent false
                                if (canSend) send()
                                true
                            },
                        textStyle = LocalTextStyle.current.copy(
                            color = TextPrimary,
                            fontFamily = DisplayFont,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                        colors = fieldColors(),
                        chromeStyle = FieldChromeStyle.Borderless,
                        placeholder = { ComposerPlaceholderHint("Ask me anything…") },
                    )
                }
            },
            belowInput = if (modelId == ComposerCustomModelId) {
                {
                    TextField(
                        customModel,
                        { customModel = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = TextPrimary,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                        ),
                        colors = fieldColors(),
                        placeholder = {
                            Text("custom model or variant", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                        },
                    )
                }
            } else {
                null
            },
            bottomBarLeading = {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerProfileChips(
                        agent = agent,
                        localRuntime = localRuntime,
                        modelId = modelId,
                        reasoningEffort = reasoningEffort,
                        sandboxMode = sandboxMode,
                        cliStatuses = cliStatuses,
                        localBackends = localBackends,
                        modelOptions = modelOptions,
                        selectedModel = selectedModel,
                        onAgentChange = { next, runtime ->
                            agentChosen = true
                            agent = next
                            localRuntime = runtime
                            seededForAgent = null
                        },
                        onModelChange = { modelId = it },
                        onReasoningEffortChange = { reasoningEffort = it },
                        onSandboxChange = { sandboxMode = it },
                        refreshingProviders = refreshingProviders,
                        onRefreshProviders = {
                            scope.launch {
                                refreshingProviders = true
                                try {
                                    services.agentRuns.refreshCliStatuses()
                                } finally {
                                    refreshingProviders = false
                                }
                            }
                        },
                    )
                }
            },
            bottomBarTrailing = {
                ChatSendButton(onClick = ::send, enabled = canSend, isSending = launching)
            },
        )
    }
}
