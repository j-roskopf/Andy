package com.joetr.andy.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.andy.service.RemoteHostCapabilities
import app.andy.ui.components.Button
import app.andy.ui.components.IconButton
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.OutlinedButton
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostEditorScreen(
    hostId: String?,
    repository: HostRepository,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val existing = hostId?.let { id -> repository.hosts.value.firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()

    var displayName by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var vncPort by remember {
        mutableStateOf((existing?.vncPort ?: RemoteHostCapabilities.DefaultVncPort).toString())
    }
    var networkUrl by remember { mutableStateOf(existing?.networkAccessBaseUrl.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var vncUsername by remember { mutableStateOf(existing?.vncUsername.orEmpty()) }
    var vncPassword by remember {
        mutableStateOf(existing?.id?.let { repository.vncPassword(it) }.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.palette.windowBg)
            .imePadding()
            .padding(horizontal = AndySpace.Space4),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AndySpace.Space3, bottom = AndySpace.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            IconButton(
                onClick = onDone,
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
                if (existing == null) "Add host" else "Edit host",
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                color = tokens.palette.textPrimary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            MobileField(
                label = "Display name",
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = "Studio MacBook",
            )
            MobileField(
                label = "Address",
                value = address,
                onValueChange = { address = it },
                placeholder = "laptop.tailnet.ts.net or 100.x.y.z",
            )
            MobileField(
                label = "VNC port",
                value = vncPort,
                onValueChange = { vncPort = it.filter { ch -> ch.isDigit() }.take(5) },
                placeholder = "5900",
                keyboardType = KeyboardType.Number,
            )
            MobileField(
                label = "Network Access URL (optional)",
                value = networkUrl,
                onValueChange = { networkUrl = it },
                placeholder = "https://laptop.tailnet.ts.net or http://100.x.y.z:8565",
            )
            Text(
                "Sign in from Projects with your master password, access token, or login code — credentials are not stored on the host.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.palette.textSecondary,
            )
            MobileField(
                label = "macOS username (optional)",
                value = vncUsername,
                onValueChange = { vncUsername = it },
                placeholder = "Leave blank for VNC password-only",
            )
            MobileField(
                label = "VNC / Mac password",
                value = vncPassword,
                onValueChange = { vncPassword = it },
                placeholder = "Required for Screen Sharing",
                password = true,
            )
            MobileField(
                label = "Notes",
                value = notes,
                onValueChange = { notes = it },
                placeholder = "e.g. enable Screen Sharing; Tailscale Serve on 8565",
                singleLine = false,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AndyShape.Interactive)
                    .background(tokens.palette.sidebarBg)
                    .border(1.dp, tokens.palette.borderMedium, AndyShape.Interactive)
                    .padding(AndySpace.Space3),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                LucideIcon(
                    Lucide.Info,
                    contentDescription = null,
                    tint = tokens.palette.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "macOS: System Settings → General → Sharing → Screen Sharing, and allow " +
                        "“VNC viewers may control screen with password” (or use your Mac username + password). " +
                        "Andy never enables this for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.palette.textSecondary,
                )
            }
            error?.let {
                Text(it, color = tokens.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(AndySpace.Space6))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = AndySpace.Space3),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                shape = AndyShape.Interactive,
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val name = displayName.trim().ifBlank { address.trim() }
                    val addr = address.trim()
                    val port = vncPort.toIntOrNull() ?: RemoteHostCapabilities.DefaultVncPort
                    if (addr.isBlank()) {
                        error = "Address is required"
                        return@Button
                    }
                    val host = (existing ?: SavedHost(displayName = name, address = addr)).copy(
                        displayName = name,
                        address = addr,
                        vncPort = port,
                        networkAccessBaseUrl = networkUrl.trim(),
                        notes = notes.trim(),
                        vncUsername = vncUsername.trim(),
                    )
                    scope.launch {
                        repository.upsert(host)
                        repository.saveVncPassword(host.id, vncPassword)
                        repository.selectHost(host.id)
                        onDone()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = AndyShape.Interactive,
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusEvent { state ->
                if (state.isFocused) {
                    scope.launch { bringIntoView.bringIntoView() }
                }
            },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = AndyShape.Interactive,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.accent,
            unfocusedBorderColor = tokens.palette.borderMedium,
            focusedLabelColor = tokens.accent,
            cursorColor = tokens.accent,
            focusedTextColor = tokens.palette.textPrimary,
            unfocusedTextColor = tokens.palette.textPrimary,
            focusedContainerColor = tokens.palette.surfaceRaised,
            unfocusedContainerColor = tokens.palette.surfaceRaised,
            focusedPlaceholderColor = tokens.palette.textTertiary,
            unfocusedPlaceholderColor = tokens.palette.textTertiary,
        ),
    )
}

