package app.andy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.service.WebServices
import app.andy.ui.components.Button
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
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
    var portText by remember(workspaceState.mcpServerPort) { mutableStateOf(workspaceState.mcpServerPort.toString()) }
    val clientOptions = remember { services.mcp.getClients() }
    val toolNames = remember { services.mcp.getToolNames() }
    var selectedClientLabel by remember { mutableStateOf(clientOptions.firstOrNull() ?: "Claude Code") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    val copyText = rememberCopyText()

    val mcpStatus by services.mcp.status.collectAsState("stopped")
    val mcpRunning by services.mcp.running.collectAsState(false)
    val proxyStatus by services.proxy.status.collectAsState("Proxy stopped")
    val proxyRunning = proxyStatus.contains("listening on")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = MonoFont)

        AppearancePanel(workspaceState, onUpdateWorkspace)

        AgentTranscriptPanel(workspaceState, onUpdateWorkspace)

        AgentNotificationsPanel(workspaceState, onUpdateWorkspace, services)

        PanelCard {
            Text("HTTP debug proxy", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Start Andy's mitmdump capture proxy automatically when the app opens.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = workspaceState.proxyStartOnLaunch,
                        onCheckedChange = { checked ->
                            onUpdateWorkspace { it.copy(proxyStartOnLaunch = checked) }
                        }
                    )
                    Text("Start proxy on app launch", color = TextPrimary, fontSize = 13.sp)
                }

                Spacer(Modifier.width(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Proxy Status:", color = TextSecondary, fontSize = 12.sp)
                    GlowingDot(proxyRunning)
                    Text(proxyStatus, color = if (proxyRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Corporate TLS inspection: if your Mac routes through a security proxy that re-signs HTTPS, point Andy at the corporate root CA or enable insecure upstream.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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

        PanelCard {
            Text("Model Context Protocol (MCP) Server", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Expose Andy's Android control automation capabilities as an MCP server. This allows external AI coding assistants (e.g. Claude Code, Codex, Cursor) to interact with connected emulators and devices.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        workspaceState.mcpServerEnabled,
                        { checked ->
                            onUpdateWorkspace { it.copy(mcpServerEnabled = checked) }
                        }
                    )
                    Text("Enable MCP Server", color = TextPrimary, fontSize = 13.sp)
                }

                Spacer(Modifier.width(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Port:", color = TextSecondary, fontSize = 13.sp)
                    TextField(
                        portText,
                        {
                            val filtered = it.filter(Char::isDigit).take(5)
                            portText = filtered
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

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Server Status:", color = TextSecondary, fontSize = 12.sp)
                GlowingDot(mcpRunning)
                Text(mcpStatus, color = if (mcpRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            }
        }

        PanelCard {
            Text("Available Tools", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text("${toolNames.size} MCP tool calls exposed by Andy", color = TextSecondary, fontSize = 12.sp)
            @OptIn(ExperimentalLayoutApi::class)
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

        PanelCard {
            Text("Client Configurations", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Configure your local AI coding tool to connect to Andy's MCP endpoint.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Client:", color = TextSecondary, fontSize = 13.sp)
                Box {
                    Button(
                        onClick = { dropdownExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750)
                    ) {
                        Text(selectedClientLabel, color = TextPrimary)
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        containerColor = AndyColors.Neutral750
                    ) {
                        clientOptions.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client, color = TextPrimary) },
                                onClick = {
                                    selectedClientLabel = client
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAutoWriteSupported = services.mcp.isAutoWriteSupported(selectedClientLabel)

                Button(
                    onClick = {
                        val success = services.mcp.writeConfig(selectedClientLabel, workspaceState.mcpServerPort)
                        operationStatus = if (success) {
                            "Successfully updated configuration for $selectedClientLabel (backed up original)."
                        } else {
                            "Failed to write configuration file."
                        }
                    },
                    enabled = isAutoWriteSupported
                ) {
                    Text("Add to config")
                }

                Button(
                    onClick = {
                        val snippet = services.mcp.getSnippet(selectedClientLabel, workspaceState.mcpServerPort)
                        copyText(snippet)
                        operationStatus = "Snippet copied to clipboard!"
                    }
                ) {
                    Text("Copy snippet")
                }
            }

            operationStatus?.let { status ->
                Text(status, color = Rust, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(Modifier.height(4.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R3))
                    .padding(12.dp)
            ) {
                Text("Configuration Snippet ($selectedClientLabel)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val snippet = services.mcp.getSnippet(selectedClientLabel, workspaceState.mcpServerPort)
                SelectionContainer {
                    Text(
                        snippet,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
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
        Text("Appearance", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            "Choose the accent tint used for selection, controls, and emphasis. Andy blue is the default.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
        Spacer(Modifier.height(12.dp))
        Text("Background", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "Tinted keeps a subtle hue wash from the accent. Dark uses true black surfaces. Light flips the shell to a bright workspace.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
        Spacer(Modifier.height(12.dp))
        Text("Code editor theme", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "Syntax highlighting colors for Computer Files. Andy is the built-in scheme; the rest are RSyntaxTextArea presets.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
}

@Composable
private fun AgentTranscriptPanel(
    workspace: WorkspaceState,
    update: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    PanelCard {
        Text("Agent transcript", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            "Control how tool activity appears in agent chats.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
        Text("Agent notifications", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text("Choose how Andy calls attention to completed work and input requests.", color = TextSecondary, fontSize = 12.sp)
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
private fun WebSettingsScreen(
    web: WebServices,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val connection by web.connection.state.collectAsState()
    val storage by web.storage.state.collectAsState()
    var operationStatus by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmForgetUsb by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { web.storage.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = MonoFont)

        AppearancePanel(workspaceState, onUpdateWorkspace)

        AgentTranscriptPanel(workspaceState, onUpdateWorkspace)

        PanelCard {
            Text("Connection", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Connect through Andy tracebox on this computer, or directly to one USB device. The browser never starts either tool for you.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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

        PanelCard {
            Text("Storage", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Settings and authorization keys use IndexedDB. Bug recordings and large captures use origin-private storage (OPFS).",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
            Text("Authorization", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text("The WebUSB ADB private key is non-exportable and stored only for this browser origin.", color = TextSecondary, fontSize = 12.sp)
            Button(
                onClick = { confirmForgetUsb = true },
                colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750),
            ) { Text("Forget WebUSB authorization") }
        }

        PanelCard {
            Text("About Andy for web", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text("Supported origins: http://localhost:10000 · https://andy.joetr.com", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
            Text("Desktop Chrome or Edge · Android 11 / API 30 or newer", color = TextSecondary, fontSize = 12.sp)
            Text("Device traffic stays on this computer. No telemetry or hosted device API.", color = TextSecondary, fontSize = 12.sp)
        }

        operationStatus?.let { Text(it, color = Rust, fontFamily = MonoFont, fontSize = 12.sp) }
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
