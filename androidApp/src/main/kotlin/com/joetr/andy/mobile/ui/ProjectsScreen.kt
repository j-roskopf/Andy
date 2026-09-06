package com.joetr.andy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.andy.ui.components.Badge
import app.andy.ui.components.BadgeVariant
import app.andy.ui.components.Button
import app.andy.ui.components.Card
import app.andy.ui.components.CardVariant
import app.andy.ui.components.EmptyState
import app.andy.ui.components.IconButton
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusDot
import app.andy.ui.components.StatusDotVariant
import app.andy.ui.components.TextButton
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.networkaccess.ChatDto
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessException
import com.joetr.andy.mobile.data.networkaccess.ProjectGroup
import com.joetr.andy.mobile.data.networkaccess.groupChatsByProject
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(
    host: SavedHost?,
    repository: HostRepository,
    networkClient: NetworkAccessClient?,
    onClientReady: (NetworkAccessClient) -> Unit,
    onSignedOut: () -> Unit = {},
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onNeedHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    if (host == null) {
        EmptyState(
            title = "No host selected",
            description = "Pick a saved laptop on the Hosts tab to browse its Network Access chats.",
            icon = {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(AndyShape.Sheet)
                        .background(tokens.palette.surfaceRaised)
                        .border(1.dp, tokens.palette.borderMedium, AndyShape.Sheet),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = tokens.palette.textTertiary,
                    )
                }
            },
            actions = {
                Button(onClick = onNeedHost, shape = AndyShape.Interactive) {
                    Text("Go to Hosts")
                }
            },
            modifier = modifier
                .fillMaxSize()
                .background(tokens.palette.windowBg)
                .padding(AndySpace.Space6),
        )
        return
    }

    val scope = rememberCoroutineScope()
    var authModePassword by remember { mutableStateOf(true) }
    var authCredentialInput by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(networkClient?.sessionToken != null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    suspend fun refresh(client: NetworkAccessClient) {
        loading = true
        error = null
        try {
            val projects = client.listProjects()
            val chats = client.listChats()
            groups = groupChatsByProject(projects, chats)
            signedIn = true
        } catch (e: NetworkAccessException) {
            error = e.message
            if (e.unauthorized) {
                signedIn = false
                repository.saveNetworkAccessSession(host.id, null)
                onSignedOut()
            }
        } catch (e: Exception) {
            error = e.message ?: "Failed to load chats"
        } finally {
            loading = false
        }
    }

    suspend fun completeLogin(client: NetworkAccessClient) {
        val session = client.sessionToken
        if (session.isNullOrBlank()) throw NetworkAccessException("Login failed")
        repository.saveNetworkAccessSession(host.id, session)
        onClientReady(client)
        refresh(client)
    }

    LaunchedEffect(host.id) {
        authCredentialInput = ""
        val storedSession = repository.networkAccessSession(host.id)
        val legacyToken = repository.legacyNetworkAccessToken(host.id)
        when {
            !storedSession.isNullOrBlank() -> {
                val client = NetworkAccessClient(host.resolvedNetworkAccessBaseUrl())
                client.sessionToken = storedSession
                try {
                    onClientReady(client)
                    refresh(client)
                } catch (e: Exception) {
                    signedIn = false
                    error = e.message
                    repository.saveNetworkAccessSession(host.id, null)
                    onSignedOut()
                    client.close()
                }
            }
            !legacyToken.isNullOrBlank() -> {
                val client = NetworkAccessClient(host.resolvedNetworkAccessBaseUrl())
                try {
                    client.loginWithToken(legacyToken)
                    completeLogin(client)
                } catch (e: Exception) {
                    signedIn = false
                    error = e.message
                    repository.clearLegacyNetworkAccessToken(host.id)
                    onSignedOut()
                    client.close()
                }
            }
            else -> {
                signedIn = false
                groups = emptyList()
                onSignedOut()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.palette.windowBg),
    ) {
        Column(Modifier.fillMaxSize()) {
            MobileHeader(
                title = "Projects",
                subtitle = host.displayName,
                actions = {
                    if (signedIn) {
                        IconButton(
                            onClick = {
                                scope.launch { networkClient?.let { refresh(it) } }
                            },
                            modifier = Modifier.size(AndyLayout.ControlHeightMd),
                            contentDescription = "Refresh",
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = tokens.palette.textSecondary,
                            )
                        }
                    }
                },
            )
            Text(
                host.resolvedNetworkAccessBaseUrl(),
                modifier = Modifier.padding(horizontal = AndySpace.Space4),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                color = tokens.palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(AndySpace.Space3))

            if (!signedIn) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space4),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        variant = CardVariant.Default,
                        shape = AndyShape.Sheet,
                        contentPadding = PaddingValues(AndySpace.Space5),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "Sign in",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = DisplayFont,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.palette.textPrimary,
                            )
                        }
                        Text(
                            "Use your master password, or a Network Access token / one-time login code from Andy Desktop → Settings → MCP.",
                            color = tokens.palette.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { authModePassword = true },
                                shape = AndyShape.Interactive,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "Password",
                                    color = if (authModePassword) tokens.accent else tokens.palette.textSecondary,
                                )
                            }
                            OutlinedButton(
                                onClick = { authModePassword = false },
                                shape = AndyShape.Interactive,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "Token / code",
                                    color = if (!authModePassword) tokens.accent else tokens.palette.textSecondary,
                                )
                            }
                        }
                        MobileField(
                            label = if (authModePassword) "Master password" else "Access token or login code",
                            value = authCredentialInput,
                            onValueChange = { authCredentialInput = it },
                            placeholder = if (authModePassword) "Memorable password" else "Token or code",
                            password = true,
                        )
                        error?.let {
                            Text(it, color = tokens.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true
                                    error = null
                                    try {
                                        val client = NetworkAccessClient(host.resolvedNetworkAccessBaseUrl())
                                        if (authModePassword) {
                                            client.loginWithPassword(authCredentialInput)
                                        } else {
                                            val value = authCredentialInput.trim()
                                            if (value.length <= 24) {
                                                client.loginWithCode(value)
                                            } else {
                                                client.loginWithToken(value)
                                            }
                                        }
                                        authCredentialInput = ""
                                        completeLogin(client)
                                    } catch (e: Exception) {
                                        signedIn = false
                                        error = e.message ?: "Login failed"
                                        onSignedOut()
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = authCredentialInput.isNotBlank() && !loading,
                            shape = AndyShape.Interactive,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (loading) "Connecting…" else "Connect")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            signedIn = false
                            networkClient?.sessionToken = null
                            repository.saveNetworkAccessSession(host.id, null)
                            onSignedOut()
                        },
                    ) {
                        Text("Sign out", color = tokens.palette.textTertiary)
                    }
                    Spacer(Modifier.weight(1f))
                }
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = AndySpace.Space4),
                        color = tokens.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (loading && groups.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = tokens.accent)
                    }
                } else if (groups.isEmpty()) {
                    EmptyState(
                        title = "No projects found",
                        description = "Start a new chat to run tasks with autonomous agents on this host.",
                        actions = {
                            Button(onClick = onNewChat, shape = AndyShape.Interactive) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(AndySpace.Space2))
                                Text("New chat")
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(AndySpace.Space6),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = AndySpace.Space4,
                            end = AndySpace.Space4,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                    ) {
                        items(groups, key = { it.projectId }, contentType = { "project" }) { group ->
                            ProjectSection(
                                group = group,
                                expanded = group.projectId in expanded,
                                onToggle = {
                                    expanded = if (group.projectId in expanded) {
                                        expanded - group.projectId
                                    } else {
                                        expanded + group.projectId
                                    }
                                },
                                onOpenChat = onOpenChat,
                            )
                        }
                    }
                }
            }
        }
        if (signedIn) {
            FloatingActionButton(
                onClick = onNewChat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AndySpace.Space4),
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                shape = AndyShape.Sheet,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "New chat")
            }
        }
    }
}

@Composable
private fun ProjectSection(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val tokens = andyTokens()
    Card(
        modifier = Modifier.fillMaxWidth(),
        variant = CardVariant.Default,
        shape = AndyShape.Sheet,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(AndySpace.Space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = tokens.palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                group.projectName,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                color = tokens.palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Badge(
                label = "${group.chats.size}",
                variant = BadgeVariant.Neutral,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(AndyMotion.standardTween()) + expandVertically(AndyMotion.standardTween()),
            exit = fadeOut(AndyMotion.standardTween()) + shrinkVertically(AndyMotion.standardTween()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AndySpace.Space2),
            ) {
                group.chats.forEach { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.id) })
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatDto, onClick: () -> Unit) {
    val tokens = andyTokens()
    val statusVariant = when (chat.status) {
        "completed" -> StatusDotVariant.Success
        "error" -> StatusDotVariant.Error
        "running", "in_progress" -> StatusDotVariant.Warning
        else -> StatusDotVariant.Neutral
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = AndySpace.Space5,
                end = AndySpace.Space4,
                top = AndySpace.Space2,
                bottom = AndySpace.Space2,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        StatusDot(variant = statusVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chat.title.ifBlank { chat.prompt.take(48).ifBlank { chat.id } },
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    chat.agent.takeIf { it.isNotBlank() },
                    chat.status.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.palette.textTertiary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = tokens.palette.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}
