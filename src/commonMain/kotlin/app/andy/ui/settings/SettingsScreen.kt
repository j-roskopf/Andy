package app.andy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.service.AvailableUpdate
import app.andy.ui.components.Button
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Install Andy ${update.versionName}?",
                color = AndyColors.Neutral100,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The update has been downloaded and verified.",
                    color = AndyColors.Neutral200,
                    fontSize = 14.sp
                )
                Text(
                    "Andy will close and open the installer. After the installation is complete, you can relaunch the application.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Rust)
            ) {
                Text("Close and install", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Later")
            }
        },
        containerColor = PanelSoft,
        titleContentColor = AndyColors.Neutral100,
        textContentColor = AndyColors.Neutral300
    )
}

@Composable
internal fun SettingsScreen(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices
) {
    var portText by remember(workspaceState.mcpServerPort) { mutableStateOf(workspaceState.mcpServerPort.toString()) }
    val clientOptions = remember { services.mcp.getClients() }
    val toolNames = remember { services.mcp.getToolNames() }
    var selectedClientLabel by remember { mutableStateOf(clientOptions.firstOrNull() ?: "Claude Code") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    val mcpStatus by services.mcp.status.collectAsState("stopped")
    val mcpRunning by services.mcp.running.collectAsState(false)
    val proxyStatus by services.proxy.status.collectAsState("Proxy stopped")
    val proxyRunning = proxyStatus.contains("listening on")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = MonoFont)

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
                        clipboardManager.setText(AnnotatedString(snippet))
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
