package app.andy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.EditorSyntaxThemePreview
import app.andy.model.WorkspaceState
import app.andy.model.AgentNotificationSound
import app.andy.model.AgentNotificationTiming
import app.andy.model.EditorSyntaxTheme
import app.andy.model.TerminalColorPaletteKind
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset
import app.andy.model.normalizeTerminalHex
import app.andy.model.parseTerminalHex
import app.andy.model.terminalHexArgb
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.service.McpServerService
import app.andy.service.WebServices
import app.andy.ui.components.Button
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyColors
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
import kotlinx.coroutines.launch

private enum class DesktopSettingsCategory(
    val label: String,
    val subtitle: String,
) {
    Appearance("Appearance", "Tint, background, editor, and terminal"),
    Agents("Agents", "Transcript and notification preferences"),
    Proxy("Proxy", "HTTP debug capture proxy"),
    Mcp("MCP", "Server, tools, and client setup"),
    Onboarding("Onboarding", "Replay guided introductions"),
}

private enum class WebSettingsCategory(
    val label: String,
    val subtitle: String,
) {
    Appearance("Appearance", "Tint, background, editor, and terminal"),
    Agents("Agents", "How tool activity appears in chats"),
    Connection("Connection", "ADB WebSocket and WebUSB"),
    Data("Data", "Browser storage and authorization"),
    About("About", "Origins and platform support"),
}

@Composable
internal fun SettingsScreen(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices
) {
    services.web?.let { web ->
        WebSettingsScreen(web, workspaceState, onUpdateWorkspace)
        return
    }
    var category by remember { mutableStateOf(DesktopSettingsCategory.Appearance) }
    var portText by remember(workspaceState.mcpServerPort) { mutableStateOf(workspaceState.mcpServerPort.toString()) }
    val toolNames = remember { services.mcp.getToolNames() }

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
            DesktopSettingsCategory.Agents -> {
                AgentTranscriptPanel(workspaceState, onUpdateWorkspace)
                AgentNotificationsPanel(workspaceState, onUpdateWorkspace, services)
            }
            DesktopSettingsCategory.Proxy -> ProxyPanel(
                workspaceState = workspaceState,
                onUpdateWorkspace = onUpdateWorkspace,
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
                McpToolsPanel(toolNames)
                McpClientsPanel(
                    mcpService = services.mcp,
                    mcpServerPort = workspaceState.mcpServerPort,
                )
            }
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
        verticalArrangement = Arrangement.spacedBy(AndySpace.S3),
    ) {
        Toolbar(title, subtitle)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 820.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.S4),
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
                    verticalArrangement = Arrangement.spacedBy(AndySpace.S3),
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
    Column(
        modifier
            .background(AndyColors.Neutral800.copy(alpha = 0.82f), RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(AndySpace.S2),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    .clip(RoundedCornerShape(AndyRadius.R2))
                    .background(if (selected) AndyColors.OrangeSubtle else AndyColors.Neutral800.copy(alpha = 0f))
                    .border(
                        1.dp,
                        if (selected) AndyColors.OrangeBorder else AndyColors.Neutral800.copy(alpha = 0f),
                        RoundedCornerShape(AndyRadius.R2),
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
                        RoundedCornerShape(AndyRadius.R3),
                    )
                    .border(
                        1.dp,
                        if (selected) AndyColors.OrangeBorder else Border,
                        RoundedCornerShape(AndyRadius.R3),
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
            verticalArrangement = Arrangement.spacedBy(AndySpace.S3),
        ) {
            content()
            Spacer(Modifier.height(AndySpace.S4))
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
    PanelCard {
        SettingsSectionHeader(
            title = "Accent tint",
            description = "Used for selection, controls, and emphasis. Andy blue is the default.",
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
    PanelCard {
        SettingsSectionHeader(
            title = "Background",
            description = "Tinted keeps a subtle hue wash from the accent. Dark uses true black surfaces. Light flips the shell to a bright workspace.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AndySurfaceMode.entries.forEach { mode ->
                val selected = mode == selectedSurface
                TextButton(
                    onClick = { update { it.copy(surfaceModeId = mode.id) } },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) AndyColors.Neutral100 else TextSecondary,
                    ),
                    modifier = Modifier
                        .background(
                            if (selected) AndyColors.OrangeSubtle else PanelSoft,
                            RoundedCornerShape(AndyRadius.R3),
                        )
                        .border(
                            1.dp,
                            if (selected) AndyColors.OrangeBorder else Border,
                            RoundedCornerShape(AndyRadius.R3),
                        )
                        .semantics { contentDescription = "${mode.label} background" },
                ) {
                    Text(mode.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
    PanelCard {
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
                val selected = theme == selectedTheme
                TextButton(
                    onClick = { update { it.copy(editorSyntaxThemeId = theme.id) } },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) AndyColors.Neutral100 else TextSecondary,
                    ),
                    modifier = Modifier
                        .background(
                            if (selected) AndyColors.OrangeSubtle else PanelSoft,
                            RoundedCornerShape(AndyRadius.R3),
                        )
                        .border(
                            1.dp,
                            if (selected) AndyColors.OrangeBorder else Border,
                            RoundedCornerShape(AndyRadius.R3),
                        )
                        .semantics { contentDescription = "${theme.label} editor theme" },
                ) {
                    Text(theme.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
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
    val selectedPresetId = workspace.terminalThemeId
    val selectedPalette = TerminalColorPaletteKind.fromId(workspace.terminalColorPaletteId)
    val selectedFont = TerminalFontFamily.fromId(workspace.terminalFontFamilyId)
    val selectedSize = TerminalThemePreset.coerceFontSize(workspace.terminalFontSize)

    PanelCard {
        SettingsSectionHeader(
            title = "Terminal",
            description = "Colors and font for agent and project terminals. Changes apply to new sessions.",
        )

        Text("Preset", color = TextSecondary, fontSize = 12.sp)
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

        Spacer(Modifier.height(8.dp))
        TerminalColorPreviewStrip(workspace)

        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalColorHexRow(
                label = "Foreground",
                hex = workspace.terminalForegroundHex,
                fallback = TerminalThemePreset.Andy.foregroundHex,
                onCommit = { hex -> update { it.copy(terminalForegroundHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Background",
                hex = workspace.terminalBackgroundHex,
                fallback = TerminalThemePreset.Andy.backgroundHex,
                onCommit = { hex -> update { it.copy(terminalBackgroundHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Selection fg",
                hex = workspace.terminalSelectionFgHex,
                fallback = TerminalThemePreset.Andy.selectionFgHex,
                onCommit = { hex -> update { it.copy(terminalSelectionFgHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Selection bg",
                hex = workspace.terminalSelectionBgHex,
                fallback = TerminalThemePreset.Andy.selectionBgHex,
                onCommit = { hex -> update { it.copy(terminalSelectionBgHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Find fg",
                hex = workspace.terminalFoundFgHex,
                fallback = TerminalThemePreset.Andy.foundFgHex,
                onCommit = { hex -> update { it.copy(terminalFoundFgHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Find bg",
                hex = workspace.terminalFoundBgHex,
                fallback = TerminalThemePreset.Andy.foundBgHex,
                onCommit = { hex -> update { it.copy(terminalFoundBgHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Hyperlink fg",
                hex = workspace.terminalHyperlinkFgHex,
                fallback = TerminalThemePreset.Andy.hyperlinkFgHex,
                onCommit = { hex -> update { it.copy(terminalHyperlinkFgHex = hex, terminalThemeId = "custom") } },
            )
            TerminalColorHexRow(
                label = "Hyperlink bg",
                hex = workspace.terminalHyperlinkBgHex,
                fallback = TerminalThemePreset.Andy.hyperlinkBgHex,
                onCommit = { hex -> update { it.copy(terminalHyperlinkBgHex = hex, terminalThemeId = "custom") } },
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = workspace.terminalUseInverseSelection,
                onCheckedChange = { checked ->
                    update { it.copy(terminalUseInverseSelection = checked, terminalThemeId = "custom") }
                },
            )
            Text("Inverse selection colors", color = TextPrimary, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text("ANSI palette", color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TerminalColorPaletteKind.entries.forEach { palette ->
                SettingsChoicePill(
                    label = palette.label,
                    selected = palette == selectedPalette,
                    contentDescription = "${palette.label} ANSI palette",
                    onClick = {
                        update {
                            it.copy(
                                terminalColorPaletteId = palette.id,
                                terminalThemeId = "custom",
                            )
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(8.dp))
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
    }
}

@Composable
private fun SettingsChoicePill(
    label: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) AndyColors.Neutral100 else TextSecondary,
        ),
        modifier = Modifier
            .background(
                if (selected) AndyColors.OrangeSubtle else PanelSoft,
                RoundedCornerShape(AndyRadius.R3),
            )
            .border(
                1.dp,
                if (selected) AndyColors.OrangeBorder else Border,
                RoundedCornerShape(AndyRadius.R3),
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun TerminalColorPreviewStrip(workspace: WorkspaceState) {
    val fg = Color(terminalHexArgb(workspace.terminalForegroundHex))
    val bg = Color(terminalHexArgb(workspace.terminalBackgroundHex))
    val sel = Color(terminalHexArgb(workspace.terminalSelectionBgHex))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(AndyRadius.R3))
            .background(bg)
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Aa", color = fg, fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .background(sel, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("selection", color = Color(terminalHexArgb(workspace.terminalSelectionFgHex)), fontFamily = MonoFont, fontSize = 11.sp)
        }
        Text(
            "link",
            color = Color(terminalHexArgb(workspace.terminalHyperlinkFgHex)),
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TerminalColorHexRow(
    label: String,
    hex: String,
    fallback: String,
    onCommit: (String) -> Unit,
) {
    val normalized = normalizeTerminalHex(hex, fallback)
    var draft by remember(normalized) { mutableStateOf(normalized) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(96.dp),
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(terminalHexArgb(parseTerminalHex(draft) ?: normalized)))
                .border(1.dp, Border, RoundedCornerShape(4.dp)),
        )
        TextField(
            value = draft,
            onValueChange = { value ->
                draft = value
                parseTerminalHex(value)?.let(onCommit)
            },
            modifier = Modifier.width(120.dp),
            textStyle = LocalTextStyle.current.copy(
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = MonoFont,
            ),
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
    PanelCard {
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
    PanelCard {
        SettingsSectionHeader(
            title = "Transcript",
            description = "Control how tool activity appears in agent chats.",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = workspace.compactToolCalls,
                onCheckedChange = { checked -> update { it.copy(compactToolCalls = checked) } },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Compact tool calls", color = TextPrimary, fontSize = 13.sp)
                Text(
                    "Collapse consecutive tools into one expandable line. Uncheck to show each tool call on its own row.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
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
    PanelCard {
        SettingsSectionHeader(
            title = "Notifications",
            description = "How Andy calls attention to completed work and input requests.",
        )
        listOf(
            "OS notifications" to workspace.agentOsNotificationsEnabled,
            "Notification sound" to workspace.agentNotificationSoundEnabled,
            "Dock icon badge" to workspace.agentIconBadgeEnabled,
        ).forEach { (label, checked) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { value -> update { state -> when (label) {
                    "OS notifications" -> state.copy(agentOsNotificationsEnabled = value)
                    "Notification sound" -> state.copy(agentNotificationSoundEnabled = value)
                    else -> state.copy(agentIconBadgeEnabled = value)
                } } })
                Text(label, color = TextPrimary, fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("When to notify", color = TextSecondary, fontSize = 13.sp)
            Box {
                Button(onClick = { timingExpanded = true }) { Text(if (workspace.agentNotificationTiming == AgentNotificationTiming.Always) "Always" else "Background only") }
                DropdownMenu(timingExpanded, { timingExpanded = false }) {
                    AgentNotificationTiming.entries.forEach { timing -> DropdownMenuItem({ Text(if (timing == AgentNotificationTiming.Always) "Always" else "Background only") }, { update { it.copy(agentNotificationTiming = timing) }; timingExpanded = false }) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sound", color = if (workspace.agentNotificationSoundEnabled) TextSecondary else TextSecondary.copy(alpha = .5f), fontSize = 13.sp)
            Box {
                Button(onClick = { soundExpanded = true }, enabled = workspace.agentNotificationSoundEnabled) { Text(sound.label) }
                DropdownMenu(soundExpanded, { soundExpanded = false }) {
                    AgentNotificationSound.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { update { it.copy(agentNotificationSoundId = option.id) }; soundExpanded = false }) }
                }
            }
            Button(onClick = { services.notificationSounds.play(sound.id) }) { Text("Preview") }
        }
    }
}

@Composable
private fun ProxyPanel(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    proxyStatus: String,
    proxyRunning: Boolean,
) {
    PanelCard {
        SettingsSectionHeader(
            title = "HTTP debug proxy",
            description = "Start Andy's mitmdump capture proxy automatically when the app opens.",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = workspaceState.proxyStartOnLaunch,
                    onCheckedChange = { checked ->
                        onUpdateWorkspace { it.copy(proxyStartOnLaunch = checked) }
                    },
                )
                Text("Start proxy on app launch", color = TextPrimary, fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Proxy Status:", color = TextSecondary, fontSize = 12.sp)
                GlowingDot(proxyRunning)
                Text(proxyStatus, color = if (proxyRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            }
        }
    }
    PanelCard {
        SettingsSectionHeader(
            title = "Corporate TLS",
            description = "If your Mac routes through a security proxy that re-signs HTTPS, point Andy at the corporate root CA or enable insecure upstream.",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = workspaceState.proxySslInsecure,
                onCheckedChange = { checked ->
                    onUpdateWorkspace { it.copy(proxySslInsecure = checked) }
                },
            )
            Text("Insecure upstream (--ssl-insecure)", color = TextPrimary, fontSize = 13.sp)
        }
        TextField(
            value = workspaceState.proxyUpstreamTrustedCaPath.orEmpty(),
            onValueChange = { value ->
                onUpdateWorkspace {
                    it.copy(proxyUpstreamTrustedCaPath = value.trim().takeIf { path -> path.isNotBlank() })
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(48.dp),
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
    PanelCard {
        SettingsSectionHeader(
            title = "Server",
            description = "Expose Andy's Android control automation as an MCP server for Claude Code, Codex, Cursor, and similar tools.",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    workspaceState.mcpServerEnabled,
                    { checked ->
                        onUpdateWorkspace { it.copy(mcpServerEnabled = checked) }
                    },
                )
                Text("Enable MCP Server", color = TextPrimary, fontSize = 13.sp)
            }
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
                    modifier = Modifier.width(96.dp).height(50.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpToolsPanel(toolNames: List<String>) {
    PanelCard {
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
                        .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.Pill))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.Pill))
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
    PanelCard {
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
                Button(
                    onClick = { dropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750),
                ) {
                    Text(selectedClientLabel, color = TextPrimary)
                }
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
            Button(
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
        Column(
            Modifier
                .fillMaxWidth()
                .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R3))
                .padding(12.dp),
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
            WebSettingsCategory.Agents -> AgentTranscriptPanel(workspaceState, onUpdateWorkspace)
            WebSettingsCategory.Connection -> {
                PanelCard {
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
                        Button(
                            onClick = { scope.launch { operationStatus = web.connection.retry().webMessage() } },
                            enabled = !connection.connecting,
                            colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750),
                        ) { Text("Retry now") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlowingDot(connection.connected)
                        Text(connection.status, color = if (connection.connected) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    connection.error?.let { error ->
                        SelectionContainer {
                            Text(
                                error,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                                    .padding(12.dp),
                            )
                        }
                    }
                }
                operationStatus?.let { Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp) }
            }
            WebSettingsCategory.Data -> {
                PanelCard {
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
                        Button(
                            onClick = { confirmClear = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750),
                        ) { Text("Clear site data") }
                    }
                    Text("Clearing site data permanently removes settings, captures, bug reports, and the saved WebUSB ADB key.", color = Rust, fontSize = 11.sp)
                }
                PanelCard {
                    SettingsSectionHeader(
                        title = "Authorization",
                        description = "The WebUSB ADB private key is non-exportable and stored only for this browser origin.",
                    )
                    Button(
                        onClick = { confirmForgetUsb = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750),
                    ) { Text("Forget WebUSB authorization") }
                }
                operationStatus?.let { Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp) }
            }
            WebSettingsCategory.About -> {
                PanelCard {
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
