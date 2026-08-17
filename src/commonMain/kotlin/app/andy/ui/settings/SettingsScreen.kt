package app.andy.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.AndyDestination
import app.andy.EditorSyntaxThemePreview
import app.andy.isToggleableInSidebar
import app.andy.loadImageBitmap
import app.andy.model.WorkspaceState
import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentMessageDeliveryMode
import app.andy.model.AgentNotificationSound
import app.andy.model.AgentNotificationTiming
import app.andy.model.EditorSyntaxTheme
import app.andy.model.OrchestrationPreferences
import app.andy.model.OrchestrationProviderRole
import app.andy.model.ProxyStartOptions
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset
import app.andy.model.acpSupported
import app.andy.model.defaultLane
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.service.DeviceService
import app.andy.service.McpServerService
import app.andy.service.OrchestrationPreferencesService
import app.andy.service.ProxyService
import app.andy.service.RetentionSweepResult
import app.andy.service.RuntimeBundleService
import app.andy.service.RuntimeBundleSnapshot
import app.andy.service.RuntimeBundleState
import app.andy.service.UnavailableAgentRetentionService
import app.andy.service.UnavailableOrchestrationPreferencesService
import app.andy.service.UnavailableRuntimeBundleService
import app.andy.service.UnavailableUpdateService
import app.andy.service.UnavailableVoiceSetupService
import app.andy.service.VoiceSetupService
import app.andy.service.VoiceSetupState
import app.andy.service.WebServices
import app.andy.updates.AndyBuildInfo
import app.andy.ui.components.Button
import app.andy.ui.components.KeyCombo
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTint
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DesktopSettingsCategory(
    val label: String,
    val subtitle: String,
) {
    Appearance("Appearance", "Tint, background, editor, and terminal"),
    Navigation("Navigation", "Show or hide sidebar pages"),
    Agents("Agents", "Sessions, orchestration, and notifications"),
    Proxy("Proxy", "HTTP debug capture proxy"),
    Mcp("MCP", "Server, tools, and client setup"),
    Updates("Updates", "Version, desktop app, CLI, andyd, and extras"),
    Onboarding("Onboarding", "Replay guided introductions"),
}

private enum class WebSettingsCategory(
    val label: String,
    val subtitle: String,
) {
    Appearance("Appearance", "Tint, background, editor, and terminal"),
    Navigation("Navigation", "Show or hide sidebar pages"),
    Connection("Connection", "ADB WebSocket and WebUSB"),
    Data("Data", "Browser storage and authorization"),
    About("About", "Origins and platform support"),
}

@Composable
internal fun SettingsScreen(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices,
    initialCategory: String? = null,
    onInitialCategoryConsumed: () -> Unit = {},
) {
    services.web?.let { web ->
        WebSettingsScreen(web, workspaceState, onUpdateWorkspace, services.capabilities.destinations)
        return
    }
    var category by remember { mutableStateOf(DesktopSettingsCategory.Appearance) }
    LaunchedEffect(initialCategory) {
        val match = DesktopSettingsCategory.entries.firstOrNull {
            it.label.equals(initialCategory, ignoreCase = true) ||
                it.name.equals(initialCategory, ignoreCase = true)
        }
        if (match != null) {
            category = match
            onInitialCategoryConsumed()
        } else if (initialCategory != null) {
            onInitialCategoryConsumed()
        }
    }
    var portText by remember(workspaceState.mcpServerPort) { mutableStateOf(workspaceState.mcpServerPort.toString()) }
    val toolNames = remember { services.mcp.getToolNames() }
    val providerModels by services.agentRuns.providerModels.collectAsState()

    val mcpStatus by services.mcp.status.collectAsState("stopped")
    val mcpRunning by services.mcp.running.collectAsState(false)
    val proxyStatus by services.proxy.status.collectAsState("Proxy stopped")
    val proxyRunning = proxyStatus.contains("listening on")

    SettingsShell(
        title = "Settings",
        subtitle = category.subtitle,
        categories = DesktopSettingsCategory.entries.map { it.label to it.subtitle },
        selectedIndex = category.ordinal,
        onSelect = { category = DesktopSettingsCategory.entries[it] },
    ) {
        when (category) {
            DesktopSettingsCategory.Appearance -> AppearancePanel(workspaceState, onUpdateWorkspace)
            DesktopSettingsCategory.Navigation -> NavigationPanel(
                workspace = workspaceState,
                update = onUpdateWorkspace,
                destinations = services.capabilities.destinations,
            )
            DesktopSettingsCategory.Agents -> {
                if (services.orchestrationPreferences !is UnavailableOrchestrationPreferencesService) {
                    OrchestrationPreferencesPanel(services.orchestrationPreferences, providerModels)
                }
                AgentExecutionPreferencesPanel(services)
                AgentSessionsPanel(workspaceState, onUpdateWorkspace)
                AgentChatMessagingPanel(workspaceState, onUpdateWorkspace)
                AgentTranscriptPanel(workspaceState, onUpdateWorkspace)
                if (services.agentRetention !is UnavailableAgentRetentionService) {
                    AgentRetentionPanel(workspaceState, onUpdateWorkspace, services)
                }
                AgentNotificationsPanel(workspaceState, onUpdateWorkspace, services)
                if (services.voiceSetup !is UnavailableVoiceSetupService) {
                    VoiceDictationPanel(services.voiceSetup, workspaceState, onUpdateWorkspace)
                }
            }
            DesktopSettingsCategory.Proxy -> ProxyPanel(
                workspaceState = workspaceState,
                onUpdateWorkspace = onUpdateWorkspace,
                proxy = services.proxy,
                proxyStatus = proxyStatus,
                proxyRunning = proxyRunning,
            )
            DesktopSettingsCategory.Mcp -> {
                McpServerPanel(
                    workspaceState = workspaceState,
                    onUpdateWorkspace = onUpdateWorkspace,
                    portText = portText,
                    onPortTextChange = { portText = it },
                    mcpStatus = mcpStatus,
                    mcpRunning = mcpRunning,
                )
                NetworkAccessPanel(
                    workspaceState = workspaceState,
                    onUpdateWorkspace = onUpdateWorkspace,
                    mcpService = services.mcp,
                    devices = services.devices,
                )
                McpToolsPanel(toolNames)
                McpClientsPanel(
                    mcpService = services.mcp,
                    mcpServerPort = workspaceState.mcpServerPort,
                )
            }
            DesktopSettingsCategory.Updates -> UpdatesPanel(
                updates = services.updates,
                runtimeBundle = services.runtimeBundle,
            )
            DesktopSettingsCategory.Onboarding -> OnboardingPanel(workspaceState, onUpdateWorkspace)
        }
    }
}

@Composable
private fun SettingsShell(
    title: String,
    subtitle: String,
    categories: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
    ) {
        Toolbar(title, subtitle)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 820.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space5),
                ) {
                    SettingsCategoryRail(
                        categories = categories,
                        selectedIndex = selectedIndex,
                        onSelect = onSelect,
                        modifier = Modifier.width(200.dp).fillMaxHeight(),
                    )
                    SettingsCategoryBody(
                        selectedIndex = selectedIndex,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        content = content,
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
                ) {
                    SettingsCategoryPills(
                        categories = categories,
                        selectedIndex = selectedIndex,
                        onSelect = onSelect,
                    )
                    SettingsCategoryBody(
                        selectedIndex = selectedIndex,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRail(
    categories: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier,
        background = AndyColors.Neutral800.copy(alpha = AndyOverlay.Strong),
        contentPadding = PaddingValues(AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Text(
            "categories",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        categories.forEachIndexed { index, (label, hint) ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AndyRadius.Control))
                    .background(if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral800.copy(alpha = 0f))
                    .border(
                        1.dp,
                        if (selected) AndyColors.OrangeBorder else AndyColors.Neutral800.copy(alpha = 0f),
                        RoundedCornerShape(AndyRadius.Control),
                    )
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = "$label settings" }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    label,
                    color = if (selected) AndyColors.Neutral100 else TextPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Text(
                    hint,
                    color = TextSecondary.copy(alpha = if (selected) 0.92f else 0.72f),
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCategoryPills(
    categories: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEachIndexed { index, (label, _) ->
            val selected = index == selectedIndex
            TextButton(
                onClick = { onSelect(index) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) AndyColors.Neutral100 else TextSecondary,
                ),
                modifier = Modifier
                    .background(
                        if (selected) AndyColors.OrangeSubtle else PanelSoft,
                        RoundedCornerShape(AndyRadius.Control),
                    )
                    .border(
                        1.dp,
                        if (selected) AndyColors.OrangeBorder else Border,
                        RoundedCornerShape(AndyRadius.Control),
                    )
                    .semantics { contentDescription = "$label settings" },
            ) {
                Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SettingsCategoryBody(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    key(selectedIndex) {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
        ) {
            content()
            Spacer(Modifier.height(AndySpace.Space5))
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val selectedTint = AndyTint.fromId(workspace.tintId)
    val selectedSurface = AndySurfaceMode.fromId(workspace.surfaceModeId)
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Accent",
            description = "Punctuation for selection, links, and status. Surfaces stay neutral.",
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AndyTint.entries.forEach { tint ->
                val selected = tint == selectedTint
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(tint.color, CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) AndyColors.Neutral100 else Border, CircleShape)
                        .selectable(
                            selected = selected,
                            onClick = { update { it.copy(tintId = tint.id) } },
                            role = Role.RadioButton,
                        )
                        .semantics { contentDescription = "${tint.label} tint" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(Modifier.size(8.dp).background(AndyColors.Neutral900, CircleShape))
                    }
                }
            }
        }
        Text("Selected: ${selectedTint.label}", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
    }
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Background",
            description = "Tinted washes chrome with the accent hue. Dark uses quiet macOS neutrals. Light uses an independently tuned bright palette.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AndySurfaceMode.entries.forEach { mode ->
                SettingsChoicePill(
                    label = mode.label,
                    selected = mode == selectedSurface,
                    contentDescription = "${mode.label} background",
                    onClick = { update { it.copy(surfaceModeId = mode.id) } },
                )
            }
        }
    }
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Code editor theme",
            description = "Syntax highlighting for Computer Files. Andy is the built-in scheme; the rest are RSyntaxTextArea presets.",
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val selectedTheme = EditorSyntaxTheme.fromId(workspace.editorSyntaxThemeId)
            EditorSyntaxTheme.entries.forEach { theme ->
                SettingsChoicePill(
                    label = theme.label,
                    selected = theme == selectedTheme,
                    contentDescription = "${theme.label} editor theme",
                    onClick = { update { it.copy(editorSyntaxThemeId = theme.id) } },
                )
            }
        }
        Text("Selected: ${EditorSyntaxTheme.fromId(workspace.editorSyntaxThemeId).label}", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
        EditorSyntaxThemePreview(
            syntaxThemeId = workspace.editorSyntaxThemeId,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TerminalAppearancePanel(workspace, update)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalAppearancePanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val selectedPresetId = TerminalThemePreset.fromId(workspace.terminalThemeId).id
    val selectedFont = TerminalFontFamily.fromId(workspace.terminalFontFamilyId)
    val selectedSize = TerminalThemePreset.coerceFontSize(workspace.terminalFontSize)

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Terminal",
            description = "Terminal theme and font for agent and project terminals. Changes apply to new and live sessions.",
        )

        Text("Theme", color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TerminalThemePreset.entries.forEach { preset ->
                SettingsChoicePill(
                    label = preset.label,
                    selected = preset.id == selectedPresetId,
                    contentDescription = "${preset.label} terminal theme",
                    onClick = { update { preset.applyTo(it) } },
                )
            }
        }

        Text("Font", color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TerminalFontFamily.entries.forEach { font ->
                SettingsChoicePill(
                    label = font.label,
                    selected = font == selectedFont,
                    contentDescription = "${font.label} terminal font",
                    onClick = { update { it.copy(terminalFontFamilyId = font.id) } },
                )
            }
        }

        Text("Font size", color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TerminalThemePreset.FontSizes.forEach { size ->
                SettingsChoicePill(
                    label = size.toInt().toString(),
                    selected = size == selectedSize,
                    contentDescription = "Terminal font size ${size.toInt()}",
                    onClick = { update { it.copy(terminalFontSize = size) } },
                )
            }
        }

        Text("Preview", color = TextSecondary, fontSize = 12.sp)
        TerminalThemePreview(
            terminalThemeId = workspace.terminalThemeId,
            fontFamilyId = workspace.terminalFontFamilyId,
            fontSize = workspace.terminalFontSize,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NavigationPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
    destinations: List<AndyDestination>,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Sidebar pages",
            description = "Choose which pages appear in the sidebar. Settings is always available.",
        )
        destinations.filter { it.isToggleableInSidebar() }.forEach { destination ->
            val enabled = destination.name !in workspace.disabledDestinations
            SettingsCheckboxRow(
                label = destination.label,
                checked = enabled,
                onCheckedChange = { checked ->
                    update { state ->
                        val disabled = if (checked) {
                            state.disabledDestinations - destination.name
                        } else {
                            state.disabledDestinations + destination.name
                        }
                        state.copy(disabledDestinations = disabled)
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsChoicePill(
    label: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AndyRadius.Control)
    Box(
        modifier
            .height(AndyLayout.ControlHeightSm)
            .background(
                if (selected) AndyColors.SurfaceSelected else Color.Transparent,
                shape,
            )
            .border(1.dp, Border, shape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** Approximate checkbox glyph + gap width, used to indent helper text under a checkbox label. */
private val CheckboxDescriptionIndent = 32.dp

/**
 * Standard checkbox settings row: full row is the click target, checkbox and label
 * share one consistent gap, and optional helper text sits indented under the label.
 */
@Composable
private fun SettingsCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(
                label,
                color = if (enabled) TextPrimary else AndyColors.TextDisabled,
                fontSize = 13.sp,
            )
        }
        if (description != null) {
            Text(
                description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = CheckboxDescriptionIndent),
            )
        }
    }
}

/** Checkbox + label pair for use inside a larger custom row (status, port field, etc). */
@Composable
private fun SettingsInlineCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            label,
            color = if (enabled) TextPrimary else AndyColors.TextDisabled,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun OnboardingPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    var status by remember { mutableStateOf<String?>(null) }
    val completed = workspace.projectsIntroductionCompleted
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Projects",
            description = "The Projects intro walks through specs, builds, verification, and runbooks. Reset it to show the guided tour again the next time you open Projects.",
        )
        Text(
            if (completed) "Status: completed" else "Status: not completed",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 12.sp,
        )
        Button(
            onClick = {
                update { it.copy(projectsIntroductionCompleted = false) }
                status = "Project onboarding reset. Open Projects to view the intro again."
            },
            enabled = completed,
            colors = primaryButtonColors(),
        ) {
            Text("Reset project onboarding")
        }
        status?.let {
            Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp)
        }
    }
}


@Composable
private fun AgentTranscriptPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Transcript",
            description = "How thinking steps and tool calls appear in agent chats.",
        )
        SettingsCheckboxRow(
            label = "Auto-expand thinking and tool sections",
            checked = workspace.agentTranscriptAutoExpandActivity,
            onCheckedChange = { value -> update { it.copy(agentTranscriptAutoExpandActivity = value) } },
            description = "Opens each thinking step and tool call when it appears. You can still collapse sections manually.",
        )
        SettingsCheckboxRow(
            label = "Collapse activity between messages",
            checked = workspace.agentTranscriptCollapseActivityBlocks,
            onCheckedChange = { value -> update { it.copy(agentTranscriptCollapseActivityBlocks = value) } },
            description = "Groups consecutive thinking and tool steps into one block between user and assistant messages.",
        )
    }
}

@Composable
private fun OrchestrationPreferencesPanel(
    service: OrchestrationPreferencesService,
    providerModels: Map<AgentKind, List<AgentModelOption>>,
) {
    var prefs by remember { mutableStateOf(service.load()) }
    // Keep notes draft independent of normalized prefs so trailing newlines/spaces aren't wiped mid-edit.
    var notesText by remember { mutableStateOf(prefs.preferences.joinToString("\n")) }
    var expandedMenu by remember { mutableStateOf<Pair<OrchestrationProviderRole, OrchestrationMenu>?>(null) }

    fun persist(next: OrchestrationPreferences) {
        val normalized = next.normalized()
        prefs = normalized
        service.save(normalized)
    }

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Orchestration",
            description = "Default providers for /andy-loop, handoff, advisor, and committee. " +
                "Choose a model and permission dial for each role; unset values inherit the provider " +
                "default or the parent task. Loop uses Implementation as the worker and Audit as the verifier. " +
                "Saved to ~/.andy/orchestration-preferences.json.",
        )
        OrchestrationProviderRole.entries.forEach { role ->
            val roleSettings = prefs.settingsFor(role)
            val agent = prefs.agentFor(role)
            val modelOptions = AgentModelCatalog.options(agent, providerModels)
            val modelLabel = roleSettings.model?.let { model ->
                AgentModelCatalog.option(agent, model, providerModels)?.label ?: model
            } ?: "provider default"
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    role.label,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.width(180.dp),
                )
                Box {
                    SettingsChoicePill(
                        label = agent.label,
                        selected = true,
                        contentDescription = "${role.label} provider",
                        onClick = { expandedMenu = role to OrchestrationMenu.Provider },
                    )
                    DropdownMenu(
                        expanded = expandedMenu == (role to OrchestrationMenu.Provider),
                        onDismissRequest = { expandedMenu = null },
                        containerColor = AndyColors.Neutral750,
                    ) {
                        AgentKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.label, color = TextPrimary) },
                                onClick = {
                                    val next = prefs.withAgent(role, kind)
                                    val selectedModel = next.settingsFor(role).model
                                    persist(
                                        if (selectedModel == null || AgentModelCatalog.option(kind, selectedModel, providerModels) != null) {
                                            next
                                        } else {
                                            next.withModel(role, null)
                                        },
                                    )
                                    expandedMenu = null
                                },
                            )
                        }
                    }
                }
                Box {
                    SettingsChoicePill(
                        label = modelLabel,
                        selected = true,
                        contentDescription = "${role.label} model",
                        onClick = { expandedMenu = role to OrchestrationMenu.Model },
                    )
                    DropdownMenu(
                        expanded = expandedMenu == (role to OrchestrationMenu.Model),
                        onDismissRequest = { expandedMenu = null },
                        containerColor = AndyColors.Neutral750,
                    ) {
                        DropdownMenuItem(
                            text = { Text("provider default", color = TextPrimary) },
                            onClick = {
                                persist(prefs.withModel(role, null))
                                expandedMenu = null
                            },
                        )
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label, color = TextPrimary) },
                                onClick = {
                                    persist(prefs.withModel(role, option.id))
                                    expandedMenu = null
                                },
                            )
                        }
                    }
                }
                Box {
                    SettingsChoicePill(
                        label = prefs.autonomyFor(role)?.label ?: "inherit parent",
                        selected = true,
                        contentDescription = "${role.label} permissions",
                        onClick = { expandedMenu = role to OrchestrationMenu.Autonomy },
                    )
                    DropdownMenu(
                        expanded = expandedMenu == (role to OrchestrationMenu.Autonomy),
                        onDismissRequest = { expandedMenu = null },
                        containerColor = AndyColors.Neutral750,
                    ) {
                        DropdownMenuItem(
                            text = { Text("inherit parent", color = TextPrimary) },
                            onClick = {
                                persist(prefs.withAutonomy(role, null))
                                expandedMenu = null
                            },
                        )
                        AgentAutonomy.entries.forEach { autonomy ->
                            DropdownMenuItem(
                                text = { Text(autonomy.label, color = TextPrimary) },
                                onClick = {
                                    persist(prefs.withAutonomy(role, autonomy))
                                    expandedMenu = null
                                },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Preference notes (one per line; woven into spawned agent prompts)",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        TextField(
            value = notesText,
            onValueChange = { value ->
                notesText = value
                persist(prefs.withPreferenceNotes(value.lines()))
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 96.dp),
            singleLine = false,
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp),
            colors = fieldColors(),
        )
    }
}

private enum class OrchestrationMenu {
    Provider,
    Model,
    Autonomy,
}

@Composable
private fun AgentExecutionPreferencesPanel(services: AndyServices) {
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Chat interface",
            description = "Choose how new chats start for each provider. ACP is the default wherever the provider supports it.",
        )
        AgentKind.entries.forEach { agent ->
            val selectedLane = providerDefaults[agent]?.lane ?: agent.defaultLane()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(agent.label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                if (agent.acpSupported) {
                    SettingsChoicePill(
                        label = "ACP",
                        selected = selectedLane == AgentLaneKind.Acp,
                        contentDescription = "Use ACP for ${agent.label}",
                        onClick = { services.agentRuns.setProviderLane(agent, AgentLaneKind.Acp) },
                    )
                } else {
                    Text("ACP unavailable", color = TextSecondary, fontSize = 12.sp)
                }
                SettingsChoicePill(
                    label = "Terminal",
                    selected = selectedLane == AgentLaneKind.Terminal,
                    contentDescription = "Use terminal for ${agent.label}",
                    onClick = { services.agentRuns.setProviderLane(agent, AgentLaneKind.Terminal) },
                )
            }
        }
    }
}

@Composable
private fun AgentChatMessagingPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Messaging",
            description = "How follow-up messages are delivered while an agent is working.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentMessageDeliveryMode.entries.forEach { mode ->
                SettingsChoicePill(
                    label = mode.label,
                    selected = mode == workspace.agentMessageDeliveryMode,
                    contentDescription = mode.label,
                    onClick = { update { it.copy(agentMessageDeliveryMode = mode) } },
                )
            }
        }
        Text(
            when (workspace.agentMessageDeliveryMode) {
                AgentMessageDeliveryMode.Immediate ->
                    "Send immediately delivers follow-ups to the live agent session as soon as you press send."
                AgentMessageDeliveryMode.Queue ->
                    "Queue messages holds follow-ups while a run is in progress, or when you are stacking multiple messages on an idle chat."
            },
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AgentSessionsPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Sessions",
            description = "What happens to running agent CLIs when you quit Andy or stop andyd.",
        )
        SettingsCheckboxRow(
            label = "Keep agent sessions alive after quit",
            checked = workspace.keepAgentSessionsOnShutdown,
            onCheckedChange = { value -> update { it.copy(keepAgentSessionsOnShutdown = value) } },
            description = "When off, Andy stops all tmux agent sessions on quit so claude, codex, and agy processes do not linger.",
        )
    }
}

@Composable
private fun AgentRetentionPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices,
) {
    val scope = rememberCoroutineScope()
    var archiveDaysText by remember(workspace.retentionCompressArchiveAfterDays) {
        mutableStateOf(workspace.retentionCompressArchiveAfterDays.toString())
    }
    var deleteDaysText by remember(workspace.retentionPermanentDeleteAfterDays) {
        mutableStateOf(workspace.retentionPermanentDeleteAfterDays.toString())
    }
    var sweepInProgress by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<RetentionSweepResult?>(null) }

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Cleanup",
            description = "Automatically compress and remove old chats.",
        )
        SettingsCheckboxRow(
            label = "Automatically clean up old chats",
            checked = workspace.retentionCleanupEnabled,
            onCheckedChange = { value -> update { it.copy(retentionCleanupEnabled = value) } },
            description = "Unread or active chats, and chats archived manually, are always kept.",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Compress & archive chats after", color = TextSecondary, fontSize = 13.sp)
            TextField(
                value = archiveDaysText,
                onValueChange = { value ->
                    val filtered = value.filter(Char::isDigit).take(4)
                    archiveDaysText = filtered
                    filtered.toIntOrNull()?.takeIf { it in 1..3650 }?.let { newArchiveDays ->
                        update { state ->
                            val newDeleteDays = if (state.retentionPermanentDeleteAfterDays <= newArchiveDays) {
                                newArchiveDays + 1
                            } else {
                                state.retentionPermanentDeleteAfterDays
                            }
                            state.copy(
                                retentionCompressArchiveAfterDays = newArchiveDays,
                                retentionPermanentDeleteAfterDays = newDeleteDays,
                            )
                        }
                    }
                },
                enabled = workspace.retentionCleanupEnabled,
                modifier = Modifier.width(64.dp).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                colors = fieldColors(),
            )
            Text("days", color = TextSecondary, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Permanently delete archived chats after", color = TextSecondary, fontSize = 13.sp)
            TextField(
                value = deleteDaysText,
                onValueChange = { value ->
                    val filtered = value.filter(Char::isDigit).take(4)
                    deleteDaysText = filtered
                    filtered.toIntOrNull()
                        ?.takeIf { it in (workspace.retentionCompressArchiveAfterDays + 1)..3650 }
                        ?.let { newDeleteDays ->
                            update { state -> state.copy(retentionPermanentDeleteAfterDays = newDeleteDays) }
                        }
                },
                enabled = workspace.retentionCleanupEnabled,
                modifier = Modifier.width(64.dp).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                colors = fieldColors(),
            )
            Text("days", color = TextSecondary, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        sweepInProgress = true
                        lastResult = null
                        try {
                            lastResult = services.agentRetention.runSweepNow()
                        } finally {
                            sweepInProgress = false
                        }
                    }
                },
                enabled = !sweepInProgress,
                colors = primaryButtonColors(),
            ) {
                if (sweepInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Cleaning up…")
                } else {
                    Text("Clean up now")
                }
            }
        }
        lastResult?.let { result ->
            Text(
                "Archived ${result.chatsCompressedArchived} · Deleted ${result.chatsPermanentlyDeleted} · " +
                    "Project folders ${result.projectLocalFoldersDeleted} · Reclaimed ${formatRetentionBytes(result.bytesReclaimed)}",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatRetentionBytes(bytes: Long): String {
    val value = bytes.toDouble()
    val (amount, suffix) = when {
        bytes >= 1024L * 1024L * 1024L -> value / (1024.0 * 1024.0 * 1024.0) to "GB"
        bytes >= 1024L * 1024L -> value / (1024.0 * 1024.0) to "MB"
        bytes >= 1024L -> value / 1024.0 to "KB"
        else -> value to "B"
    }
    val rounded = (amount * 10).toInt() / 10.0
    return "$rounded $suffix"
}

@Composable
private fun AgentNotificationsPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices,
) {
    var timingExpanded by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    val sound = AgentNotificationSound.entries.firstOrNull { it.id == workspace.agentNotificationSoundId } ?: AgentNotificationSound.Chime
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Notifications",
            description = "How Andy calls attention to completed work and input requests.",
        )
        SettingsCheckboxRow(
            label = "OS notifications",
            checked = workspace.agentOsNotificationsEnabled,
            onCheckedChange = { value -> update { it.copy(agentOsNotificationsEnabled = value) } },
        )
        SettingsCheckboxRow(
            label = "Notification sound",
            checked = workspace.agentNotificationSoundEnabled,
            onCheckedChange = { value -> update { it.copy(agentNotificationSoundEnabled = value) } },
        )
        SettingsCheckboxRow(
            label = "Dock icon badge",
            checked = workspace.agentIconBadgeEnabled,
            onCheckedChange = { value -> update { it.copy(agentIconBadgeEnabled = value) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("When to notify", color = TextSecondary, fontSize = 13.sp)
            Box {
                SettingsChoicePill(
                    label = if (workspace.agentNotificationTiming == AgentNotificationTiming.Always) "Always" else "Background only",
                    selected = true,
                    contentDescription = "When to notify",
                    onClick = { timingExpanded = true },
                )
                DropdownMenu(
                    expanded = timingExpanded,
                    onDismissRequest = { timingExpanded = false },
                    containerColor = AndyColors.Neutral750,
                ) {
                    AgentNotificationTiming.entries.forEach { timing -> DropdownMenuItem({ Text(if (timing == AgentNotificationTiming.Always) "Always" else "Background only") }, { update { it.copy(agentNotificationTiming = timing) }; timingExpanded = false }) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sound", color = if (workspace.agentNotificationSoundEnabled) TextSecondary else TextSecondary.copy(alpha = .5f), fontSize = 13.sp)
            Box {
                SettingsChoicePill(
                    label = sound.label,
                    selected = workspace.agentNotificationSoundEnabled,
                    contentDescription = "Notification sound",
                    onClick = { if (workspace.agentNotificationSoundEnabled) soundExpanded = true },
                )
                DropdownMenu(
                    expanded = soundExpanded,
                    onDismissRequest = { soundExpanded = false },
                    containerColor = AndyColors.Neutral750,
                ) {
                    AgentNotificationSound.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { update { it.copy(agentNotificationSoundId = option.id) }; soundExpanded = false }) }
                }
            }
            SettingsChoicePill(
                label = "Preview",
                selected = false,
                contentDescription = "Preview notification sound",
                onClick = { services.notificationSounds.play(sound.id) },
            )
        }
    }
}

@Composable
private fun VoiceDictationPanel(
    voiceSetup: VoiceSetupService,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by voiceSetup.state.collectAsState()
    val enabled = state !is VoiceSetupState.NotEnabled
    val shortcut = remember(workspaceState.voiceDictationShortcut) { KeyCombo.decode(workspaceState.voiceDictationShortcut) }
    var confirmReset by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Bump after delete so enablement refreshes even when state stays NotEnabled.
    var downloadsEpoch by remember { mutableStateOf(0) }
    val hasDownloads = remember(state, downloadsEpoch) { voiceSetup.hasDownloads() }
    val canResetVoice = hasDownloads || shortcut != null || enabled
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Voice dictation",
            description = "Click-to-toggle mic in the new-task and follow-up composers. Downloads a local whisper.cpp binary and English model on first enable (~150 MB).",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsInlineCheckbox(
                label = "Voice dictation",
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) scope.launch { voiceSetup.enable() }
                    else voiceSetup.disable()
                },
            )
            when (val s = state) {
                is VoiceSetupState.Downloading -> {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Downloading ${s.what}…", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    }
                }
                is VoiceSetupState.Failed -> {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${s.what}: ${s.message}", color = Rust, fontSize = 12.sp, fontFamily = MonoFont)
                        TextButton(onClick = { scope.launch { voiceSetup.enable() } }) {
                            Text("Retry")
                        }
                    }
                }
                VoiceSetupState.Ready -> {
                    Text("Ready", color = Green, fontSize = 12.sp, fontFamily = MonoFont)
                }
                VoiceSetupState.NotEnabled -> {
                    Text("Off", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
                }
            }
        }
        VoiceDictationShortcutRow(
            shortcut = shortcut,
            onChange = { combo -> onUpdateWorkspace { it.copy(voiceDictationShortcut = combo?.encode()) } },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { confirmReset = true },
                enabled = canResetVoice && !deleting,
            ) {
                if (deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Resetting…")
                } else {
                    Text("Delete downloads & reset")
                }
            }
            Text(
                "Removes the downloaded whisper binary and model (~150 MB). Keeps packaged Andy natives. Clears the mic shortcut and turns dictation off.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmReset = false },
            title = { Text("Delete voice downloads and reset?") },
            text = {
                Text(
                    "This deletes on-demand voice files under ~/.andy/voice (binary, libraries, model, and setup state). " +
                        "Packaged native bridges that ship with Andy are kept. Voice dictation turns off and the mic shortcut is cleared.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            try {
                                voiceSetup.deleteDownloads()
                                downloadsEpoch += 1
                                // Only clear the shortcut after a full wipe; partial deletes leave
                                // VoiceSetupState.Failed and keep the existing binding.
                                if (voiceSetup.state.value is VoiceSetupState.NotEnabled) {
                                    onUpdateWorkspace { it.copy(voiceDictationShortcut = null) }
                                }
                            } finally {
                                deleting = false
                                confirmReset = false
                            }
                        }
                    },
                    enabled = !deleting,
                ) { Text("Delete & reset", color = Rust) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReset = false },
                    enabled = !deleting,
                ) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = PanelSoft,
        )
    }
}

@Composable
internal fun VoiceDictationShortcutRow(
    shortcut: KeyCombo?,
    onChange: (KeyCombo?) -> Unit,
) {
    var capturing by remember { mutableStateOf(false) }
    var heldModifiers by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(capturing) {
        if (capturing) focusRequester.requestFocus() else heldModifiers = ""
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Toggle mic shortcut", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        SettingsChoicePill(
            label = when {
                capturing && heldModifiers.isNotEmpty() -> "$heldModifiers…"
                capturing -> "press keys…"
                shortcut != null -> shortcut.label()
                else -> "not set"
            },
            selected = shortcut != null || capturing,
            contentDescription = "Voice dictation shortcut",
            onClick = { capturing = true },
            modifier = Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (!capturing) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        heldModifiers = modifierPrefix(event)
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Escape) {
                        capturing = false
                        return@onPreviewKeyEvent true
                    }
                    val combo = KeyCombo.fromKeyDown(event)
                    if (combo == null) {
                        heldModifiers = modifierPrefix(event)
                        return@onPreviewKeyEvent true
                    }
                    onChange(combo)
                    capturing = false
                    true
                },
        )
        if (shortcut != null) {
            TextButton(onClick = { onChange(null) }) { Text("clear") }
        }
    }
}

private fun modifierPrefix(event: androidx.compose.ui.input.key.KeyEvent): String = buildString {
    if (event.isCtrlPressed) append("Ctrl+")
    if (event.isAltPressed) append("Alt+")
    if (event.isShiftPressed) append("Shift+")
    if (event.isMetaPressed) append("Cmd+")
}

@Composable
private fun UpdatesPanel(
    updates: AppUpdateService,
    runtimeBundle: RuntimeBundleService,
) {
    val scope = rememberCoroutineScope()
    val updateState by updates.state.collectAsState()
    val bundleState by runtimeBundle.state.collectAsState()

    LaunchedEffect(runtimeBundle) {
        if (runtimeBundle !is UnavailableRuntimeBundleService) {
            runtimeBundle.refresh(checkLatest = true)
        }
    }

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "About",
            description = "This Andy build and where updates come from.",
        )
        Text(
            "Andy ${AndyBuildInfo.versionName}",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MonoFont,
        )
        Text(
            "github.com/${AndyBuildInfo.githubOwner}/${AndyBuildInfo.githubRepo}",
            color = TextSecondary,
            fontSize = 12.sp,
            fontFamily = MonoFont,
        )
    }

    if (updates !is UnavailableUpdateService) {
        PanelCard(Modifier.fillMaxWidth()) {
            SettingsSectionHeader(
                title = "Desktop app",
                description = "Check GitHub for a newer Andy.app / installer for this platform.",
            )
            val statusText = when (val s = updateState) {
                AppUpdateState.Idle -> "Not checked yet"
                AppUpdateState.Checking -> "Checking for updates…"
                AppUpdateState.Current -> "You're on the latest desktop release"
                is AppUpdateState.Available -> "Update available: v${s.update.versionName}"
                is AppUpdateState.Installing -> {
                    val pct = s.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                    "${s.message}$pct"
                }
                is AppUpdateState.Failed -> s.message
            }
            val statusColor = when (updateState) {
                is AppUpdateState.Available -> Rust
                is AppUpdateState.Failed -> Rust
                AppUpdateState.Current -> Green
                else -> TextSecondary
            }
            Text(statusText, color = statusColor, fontSize = 12.sp, fontFamily = MonoFont)
            if (updateState is AppUpdateState.Available) {
                val notes = (updateState as AppUpdateState.Available).update.releaseNotes
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (notes != null) {
                    SelectionContainer {
                        Text(
                            notes.take(600) + if (notes.length > 600) "…" else "",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val checking = updateState is AppUpdateState.Checking
                val installing = updateState is AppUpdateState.Installing
                OutlinedButton(
                    onClick = { scope.launch { updates.checkForUpdates() } },
                    enabled = !checking && !installing,
                ) {
                    Text(if (checking) "Checking…" else "Check for updates")
                }
                val canInstall = updateState is AppUpdateState.Available ||
                    (updateState is AppUpdateState.Failed &&
                        (updateState as AppUpdateState.Failed).lastKnownUpdate != null)
                if (canInstall) {
                    Button(
                        onClick = { scope.launch { updates.installAvailableUpdate() } },
                        enabled = !installing,
                        colors = primaryButtonColors(),
                    ) {
                        Text(if (installing) "Installing…" else "Install update")
                    }
                }
            }
            if (updateState is AppUpdateState.Installing) {
                val progress = (updateState as AppUpdateState.Installing).progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }
        }
    }

    if (runtimeBundle !is UnavailableRuntimeBundleService) {
        RuntimeBundlePanel(
            state = bundleState,
            onRefresh = { scope.launch { runtimeBundle.refresh(checkLatest = true) } },
            onInstall = { scope.launch { runtimeBundle.installOrUpdateFromLatest() } },
        )
    }
}

@Composable
private fun RuntimeBundlePanel(
    state: RuntimeBundleState,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
) {
    val snapshot: RuntimeBundleSnapshot? = when (state) {
        is RuntimeBundleState.Ready -> state.snapshot
        is RuntimeBundleState.Installing -> state.snapshot
        is RuntimeBundleState.Failed -> state.snapshot
        else -> null
    }
    val installing = state is RuntimeBundleState.Installing
    val checking = state is RuntimeBundleState.Checking

    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "CLI, andyd, and extras",
            description = "Install or update ~/.andy from the latest GitHub release " +
                "(andy, andyd, tmux, status hook). Pi extension and orchestration skills " +
                "refresh from this Andy build.",
        )

        when (state) {
            RuntimeBundleState.Idle, RuntimeBundleState.Checking -> {
                Text(
                    if (checking) "Checking installs…" else "Loading…",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = MonoFont,
                )
            }
            is RuntimeBundleState.Failed -> {
                Text(state.message, color = Rust, fontSize = 12.sp, fontFamily = MonoFont)
            }
            is RuntimeBundleState.Installing -> {
                Text(state.message, color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
                state.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }
            is RuntimeBundleState.Ready -> Unit
        }

        snapshot?.let { snap ->
            if (!snap.platformSupported) {
                Text(
                    "The andy CLI and andyd are only supported on macOS (arm64) and Linux (x86_64).",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            } else {
                val versionLine = buildString {
                    append("Installed: ")
                    append(snap.installedReleaseVersion?.let { "v$it" } ?: "unknown")
                    snap.latestReleaseVersion?.let { latest ->
                        append(" · Latest: v$latest")
                    }
                    if (snap.andydRunning) append(" · andyd running")
                }
                Text(
                    versionLine,
                    color = if (snap.updateAvailable) Rust else TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = MonoFont,
                )
                if (snap.updateAvailable) {
                    Text(
                        "Update available for CLI / andyd.",
                        color = Rust,
                        fontSize = 12.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    snap.components.forEach { component ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (component.installed) "●" else "○",
                                color = if (component.installed) Green else TextSecondary,
                                fontSize = 11.sp,
                            )
                            Text(
                                component.label,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.width(140.dp),
                            )
                            Text(
                                listOfNotNull(
                                    if (component.installed) "installed" else "missing",
                                    component.detail,
                                ).joinToString(" · "),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = MonoFont,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                snap.pathHint?.let { hint ->
                    Text(hint, color = TextSecondary, fontSize = 11.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !checking && !installing,
            ) {
                Text(if (checking) "Checking…" else "Check versions")
            }
            val canInstall = snapshot?.platformSupported == true
            Button(
                onClick = onInstall,
                enabled = canInstall && !checking && !installing,
                colors = primaryButtonColors(),
            ) {
                val cliInstalled = snapshot?.components?.any { it.id == "cli" && it.installed } == true
                val label = when {
                    installing -> "Installing…"
                    !cliInstalled -> "Install from latest"
                    snapshot?.updateAvailable == true -> "Update from latest"
                    else -> "Reinstall from latest"
                }
                Text(label)
            }
        }
    }
}

@Composable
private fun ProxyPanel(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    proxy: ProxyService,
    proxyStatus: String,
    proxyRunning: Boolean,
) {
    val scope = rememberCoroutineScope()
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "HTTP debug proxy",
            description = "Start Andy's mitmdump capture proxy automatically when the app opens.",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsInlineCheckbox(
                label = "Start proxy on app launch",
                checked = workspaceState.proxyStartOnLaunch,
                onCheckedChange = { checked ->
                    onUpdateWorkspace { it.copy(proxyStartOnLaunch = checked) }
                },
            )
            Spacer(Modifier.width(16.dp))
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Proxy Status:", color = TextSecondary, fontSize = 12.sp)
                GlowingDot(proxyRunning)
                Text(
                    proxyStatus,
                    color = if (proxyRunning) Green else Rust,
                    fontSize = 12.sp,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                )
                if (proxyRunning) {
                    OutlinedButton(
                        onClick = { scope.launch { proxy.stop() } },
                    ) {
                        Text("Stop proxy")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                proxy.ensureCertificateAuthority()
                                proxy.start(
                                    workspaceState.proxyPort,
                                    workspaceState.proxyRules,
                                    ProxyStartOptions(
                                        sslInsecure = workspaceState.proxySslInsecure,
                                        upstreamTrustedCaPath = workspaceState.proxyUpstreamTrustedCaPath,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("Start proxy")
                    }
                }
            }
        }
    }
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Corporate TLS",
            description = "If your Mac routes through a security proxy that re-signs HTTPS, point Andy at the corporate root CA or enable insecure upstream.",
        )
        SettingsCheckboxRow(
            label = "Insecure upstream (--ssl-insecure)",
            checked = workspaceState.proxySslInsecure,
            onCheckedChange = { checked ->
                onUpdateWorkspace { it.copy(proxySslInsecure = checked) }
            },
        )
        TextField(
            value = workspaceState.proxyUpstreamTrustedCaPath.orEmpty(),
            onValueChange = { value ->
                onUpdateWorkspace {
                    it.copy(proxyUpstreamTrustedCaPath = value.trim().takeIf { path -> path.isNotBlank() })
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AndyLayout.FieldHeight),
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(),
            placeholder = {
                Text("Corporate root CA path (optional)", color = TextSecondary, fontSize = 13.sp)
            },
        )
    }
}

@Composable
private fun McpServerPanel(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    portText: String,
    onPortTextChange: (String) -> Unit,
    mcpStatus: String,
    mcpRunning: Boolean,
) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Server",
            description = "Expose Andy's Android control automation as an MCP server for Claude Code, Codex, Cursor, and similar tools.",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsInlineCheckbox(
                label = "Enable MCP Server",
                checked = workspaceState.mcpServerEnabled,
                onCheckedChange = { checked ->
                    // Network Access reuses the MCP listener; clearing it with MCP
                    // prevents a later standalone andyd from binding 0.0.0.0 from stale state.
                    onUpdateWorkspace {
                        it.copy(
                            mcpServerEnabled = checked,
                            networkAccessEnabled = if (checked) it.networkAccessEnabled else false,
                        )
                    }
                },
            )
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Port:", color = TextSecondary, fontSize = 13.sp)
                TextField(
                    portText,
                    {
                        val filtered = it.filter(Char::isDigit).take(5)
                        onPortTextChange(filtered)
                        filtered.toIntOrNull()?.takeIf { value -> value in 1..65535 }?.let { newPort ->
                            onUpdateWorkspace { state -> state.copy(mcpServerPort = newPort) }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.width(96.dp).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    colors = fieldColors(),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Server Status:", color = TextSecondary, fontSize = 12.sp)
            GlowingDot(mcpRunning)
            Text(mcpStatus, color = if (mcpRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NetworkAccessPanel(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    mcpService: McpServerService,
    devices: DeviceService,
) {
    val copyText = rememberCopyText()
    val hosts = remember(
        workspaceState.networkAccessEnabled,
        workspaceState.networkAccessTailscaleOnly,
        workspaceState.mcpServerPort,
    ) {
        mcpService.suggestNetworkAccessHosts().ifEmpty { listOf(mcpService.suggestNetworkAccessHost()) }
    }
    val accessUrls = hosts.map { host -> "http://$host:${workspaceState.mcpServerPort}/" }
    val primaryAccessUrl = accessUrls.firstOrNull() ?: "http://127.0.0.1:${workspaceState.mcpServerPort}/"
    val qrUrl = if (workspaceState.networkAccessToken.isNotBlank()) {
        "$primaryAccessUrl?token=${workspaceState.networkAccessToken}"
    } else {
        primaryAccessUrl
    }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(qrUrl, workspaceState.networkAccessEnabled) {
        qrBitmap = if (workspaceState.networkAccessEnabled && workspaceState.networkAccessToken.isNotBlank()) {
            withContext(Dispatchers.Default) {
                devices.generatePairingQr(qrUrl)?.let { loadImageBitmap(it) }
            }
        } else {
            null
        }
    }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Network Access",
            description = "Opt-in remote chat from your phone/tablet. In Tailscale-only mode (default), Andy " +
                "binds to localhost only and is reachable exclusively through `tailscale serve` — no raw port " +
                "is exposed to your LAN. Requires the access token on every request, including through " +
                "Tailscale Serve / localhost proxies. Embedded GUI mode only binds while the window is open.",
        )
        if (!workspaceState.mcpServerEnabled) {
            Text(
                "Requires the MCP server to be running. Enable it above first.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
        SettingsInlineCheckbox(
            label = "Allow access from other devices on my network",
            checked = workspaceState.networkAccessEnabled,
            enabled = workspaceState.mcpServerEnabled,
            onCheckedChange = { checked ->
                if (!checked) {
                    onUpdateWorkspace { it.copy(networkAccessEnabled = false) }
                    return@SettingsInlineCheckbox
                }
                val token = workspaceState.networkAccessToken.ifBlank {
                    mcpService.generateNetworkAccessToken()
                }
                onUpdateWorkspace {
                    it.copy(
                        mcpServerEnabled = true,
                        networkAccessEnabled = true,
                        networkAccessToken = token,
                    )
                }
            },
        )
        if (workspaceState.networkAccessEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Anyone who has the access token can control Andy, including device and file tools — not just chat.",
                color = Rust,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            SettingsInlineCheckbox(
                label = "Tailscale only (bind to localhost, no LAN exposure)",
                checked = workspaceState.networkAccessTailscaleOnly,
                onCheckedChange = { checked ->
                    onUpdateWorkspace { it.copy(networkAccessTailscaleOnly = checked) }
                },
            )
            Text(
                if (workspaceState.networkAccessTailscaleOnly) {
                    "Andy only binds to localhost — nothing is reachable on your LAN or Tailscale IP directly. " +
                        "Run the command below once per boot so `tailscale serve` forwards your tailnet to it " +
                        "(also gets you HTTPS for free, needed for Web Push / iOS install). " +
                        "Turn off if you need plain LAN access or a non-Tailscale VPN instead."
                } else {
                    "Any device that can reach this Mac’s IP may attempt access (still needs the token)."
                },
                color = TextSecondary,
                fontSize = 12.sp,
            )
            if (workspaceState.networkAccessTailscaleOnly) {
                Spacer(Modifier.height(8.dp))
                Text("Run once (per boot)", color = TextSecondary, fontSize = 12.sp)
                SelectionContainer {
                    Text(
                        "tailscale serve --bg ${workspaceState.mcpServerPort}",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(
                    onClick = { copyText("tailscale serve --bg ${workspaceState.mcpServerPort}") },
                ) {
                    Text("Copy command")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Access token", color = TextSecondary, fontSize = 12.sp)
            SelectionContainer {
                Text(
                    workspaceState.networkAccessToken.ifBlank { "(generating…)" },
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { copyText(workspaceState.networkAccessToken) },
                    enabled = workspaceState.networkAccessToken.isNotBlank(),
                ) {
                    Text("Copy token")
                }
                OutlinedButton(
                    onClick = {
                        val next = mcpService.generateNetworkAccessToken()
                        onUpdateWorkspace { it.copy(networkAccessToken = next) }
                    },
                ) {
                    Text("Regenerate")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (workspaceState.networkAccessTailscaleOnly) "Open on this Mac" else "Open on another device",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            accessUrls.forEach { url ->
                SelectionContainer {
                    Text(url, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Text(
                if (workspaceState.networkAccessTailscaleOnly) {
                    "This localhost URL only works on this Mac — it's for testing the token/QR flow. From " +
                        "another Tailscale device, run the `tailscale serve` command above, then open the " +
                        "https://…ts.net URL it prints (token still required)."
                } else {
                    "LAN addresses are listed first when available; Tailscale/WireGuard addresses appear when " +
                        "present. These stay http://. For HTTPS / Web Push, run " +
                        "`tailscale serve --bg ${workspaceState.mcpServerPort}` (token still required)."
                },
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { copyText(primaryAccessUrl) }) {
                    Text("Copy URL")
                }
                OutlinedButton(
                    onClick = { copyText(qrUrl) },
                    enabled = workspaceState.networkAccessToken.isNotBlank(),
                ) {
                    Text("Copy URL + token")
                }
            }
            qrBitmap?.let { bitmap ->
                Spacer(Modifier.height(10.dp))
                Text("Scan to open and sign in", color = TextSecondary, fontSize = 12.sp)
                Image(
                    bitmap = bitmap,
                    contentDescription = "Network access QR code",
                    modifier = Modifier
                        .size(160.dp)
                        .background(Color.White, AndyShape.Interactive)
                        .padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpToolsPanel(toolNames: List<String>) {
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Available tools",
            description = "${toolNames.size} MCP tool calls exposed by Andy",
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            toolNames.sorted().forEach { tool ->
                Box(
                    Modifier
                        .background(AndyColors.Neutral850, AndyShape.Interactive)
                        .border(1.dp, Border, AndyShape.Interactive)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(tool, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun McpClientsPanel(
    mcpService: McpServerService,
    mcpServerPort: Int,
) {
    val clientOptions = remember { mcpService.getClients() }
    var selectedClientLabel by remember { mutableStateOf(clientOptions.firstOrNull() ?: "Claude Code") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    val copyText = rememberCopyText()
    PanelCard(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = "Client configurations",
            description = "Configure your local AI coding tool to connect to Andy's MCP endpoint.",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Client:", color = TextSecondary, fontSize = 13.sp)
            Box {
                SettingsChoicePill(
                    label = selectedClientLabel,
                    selected = true,
                    contentDescription = "MCP client",
                    onClick = { dropdownExpanded = true },
                )
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    containerColor = AndyColors.Neutral750,
                ) {
                    clientOptions.forEach { client ->
                        DropdownMenuItem(
                            text = { Text(client, color = TextPrimary) },
                            onClick = {
                                selectedClientLabel = client
                                dropdownExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val success = mcpService.writeConfig(selectedClientLabel, mcpServerPort)
                    operationStatus = if (success) {
                        "Successfully updated configuration for $selectedClientLabel (backed up original)."
                    } else {
                        "Failed to write configuration file."
                    }
                },
                enabled = mcpService.isAutoWriteSupported(selectedClientLabel),
            ) {
                Text("Add to config")
            }
            OutlinedButton(
                onClick = {
                    copyText(mcpService.getSnippet(selectedClientLabel, mcpServerPort))
                    operationStatus = "Snippet copied to clipboard"
                },
            ) {
                Text("Copy snippet")
            }
        }
        operationStatus?.let { status ->
            Text(status, color = Rust, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        PanelCard(
            modifier = Modifier.fillMaxWidth(),
            background = AndyColors.Neutral850,
            borderColor = AndyColors.OrangeBorder.copy(alpha = 0.45f),
            contentPadding = PaddingValues(AndySpace.Space4),
            verticalArrangement = Arrangement.Top,
        ) {
            Text("Configuration snippet ($selectedClientLabel)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            val snippet = mcpService.getSnippet(selectedClientLabel, mcpServerPort)
            SelectionContainer {
                Text(
                    snippet,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun WebSettingsScreen(
    web: WebServices,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    destinations: List<AndyDestination>,
) {
    val scope = rememberCoroutineScope()
    val connection by web.connection.state.collectAsState()
    val storage by web.storage.state.collectAsState()
    var category by remember { mutableStateOf(WebSettingsCategory.Appearance) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmForgetUsb by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { web.storage.refresh() }

    SettingsShell(
        title = "Settings",
        subtitle = category.subtitle,
        categories = WebSettingsCategory.entries.map { it.label to it.subtitle },
        selectedIndex = category.ordinal,
        onSelect = { category = WebSettingsCategory.entries[it] },
    ) {
        when (category) {
            WebSettingsCategory.Appearance -> AppearancePanel(workspaceState, onUpdateWorkspace)
            WebSettingsCategory.Navigation -> NavigationPanel(
                workspace = workspaceState,
                update = onUpdateWorkspace,
                destinations = destinations,
            )
            WebSettingsCategory.Connection -> {
                PanelCard(Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Connection",
                        description = "Connect through Andy tracebox on this computer, or directly to one USB device. The browser never starts either tool for you.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { scope.launch { operationStatus = web.connection.connectWebSocket().webMessage() } },
                            enabled = !connection.connecting,
                        ) { Text("Use ADB + WebSocket") }
                        Button(
                            onClick = { scope.launch { operationStatus = web.connection.requestWebUsb().webMessage() } },
                            enabled = !connection.connecting,
                        ) { Text("Use WebUSB") }
                        OutlinedButton(
                            onClick = { scope.launch { operationStatus = web.connection.retry().webMessage() } },
                            enabled = !connection.connecting,
                        ) { Text("Retry now") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlowingDot(connection.connected)
                        Text(connection.status, color = if (connection.connected) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    connection.error?.let { error ->
                        PanelCard(
                            modifier = Modifier.fillMaxWidth(),
                            background = AndyColors.Neutral850,
                            contentPadding = PaddingValues(AndySpace.Space4),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            SelectionContainer {
                                Text(
                                    error,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
                operationStatus?.let { Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp) }
            }
            WebSettingsCategory.Data -> {
                PanelCard(Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Storage",
                        description = "Settings and authorization keys use IndexedDB. Bug recordings and large captures use origin-private storage (OPFS).",
                    )
                    Text(
                        "${webFormatBytes(storage.usageBytes)} used of ${webFormatBytes(storage.quotaBytes)} · ${if (storage.persisted) "persistent" else "best effort"}",
                        color = TextPrimary,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Loaded origins: ${storage.resourceOrigins.ifEmpty { listOf("http://localhost:10000") }.joinToString()}",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { operationStatus = if (web.storage.requestPersistence()) "Persistent storage granted" else "Persistent storage was not granted" } },
                        ) { Text("Keep data") }
                        OutlinedButton(
                            onClick = { confirmClear = true },
                        ) { Text("Clear site data") }
                    }
                    Text("Clearing site data permanently removes settings, captures, bug reports, and the saved WebUSB ADB key.", color = Rust, fontSize = 11.sp)
                }
                PanelCard(Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Authorization",
                        description = "The WebUSB ADB private key is non-exportable and stored only for this browser origin.",
                    )
                    OutlinedButton(
                        onClick = { confirmForgetUsb = true },
                    ) { Text("Forget WebUSB authorization") }
                }
                operationStatus?.let { Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp) }
            }
            WebSettingsCategory.About -> {
                PanelCard(Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "About Andy for web",
                        description = "Supported origins and runtime requirements.",
                    )
                    Text("Supported origins: http://localhost:10000 · https://andy.joetr.com", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
                    Text("Desktop Chrome or Edge · Android 11 / API 30 or newer", color = TextSecondary, fontSize = 12.sp)
                    Text("Device traffic stays on this computer. No telemetry or hosted device API.", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    if (confirmClear) {
        WebDestructiveConfirmation(
            title = "Clear all Andy browser data?",
            message = "This permanently deletes settings, authorization, captures, and bug reports for http://localhost:10000.",
            confirmLabel = "Clear all data",
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                scope.launch { operationStatus = web.storage.clearAll().webMessage() }
            },
        )
    }
    if (confirmForgetUsb) {
        WebDestructiveConfirmation(
            title = "Forget WebUSB authorization?",
            message = "The next direct USB connection will require browser and Android authorization again.",
            confirmLabel = "Forget authorization",
            onDismiss = { confirmForgetUsb = false },
            onConfirm = {
                confirmForgetUsb = false
                scope.launch { operationStatus = web.connection.forgetWebUsbAuthorization().webMessage() }
            },
        )
    }
}

@Composable
private fun WebDestructiveConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Rust) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = PanelSoft,
    )
}

private fun webFormatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "${bytes / (1024L * 1024L)} MB"
}

private fun app.andy.service.CommandResult.webMessage(): String =
    if (isSuccess) stdout.ifBlank { "Done" } else stderr.ifBlank { stdout.ifBlank { "Operation failed" } }
