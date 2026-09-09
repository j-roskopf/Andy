package com.joetr.andy.mobile.ui

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.andy.ui.components.Badge
import app.andy.ui.components.BadgeVariant
import app.andy.ui.components.Button
import app.andy.ui.components.Card
import app.andy.ui.components.CardVariant
import app.andy.ui.components.EmptyState
import app.andy.ui.components.IconButton
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusDot
import app.andy.ui.components.StatusDotVariant
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.networkaccess.ChatDto
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessException
import com.joetr.andy.mobile.data.networkaccess.ProjectGroup
import com.joetr.andy.mobile.data.networkaccess.awaitingPlanConfirmation
import com.joetr.andy.mobile.data.networkaccess.displayStatusLabel
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(
    host: SavedHost?,
    repository: HostRepository,
    networkClient: NetworkAccessClient?,
    okHttpClient: okhttp3.OkHttpClient,
    viewModel: ProjectsViewModel,
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
                    LucideIcon(
                        Lucide.Monitor,
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
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val expanded by viewModel.expandedProjectIds.collectAsStateWithLifecycle()
    val hostEndpoint = host.resolvedNetworkAccessBaseUrl()
        .removePrefix("https://")
        .removePrefix("http://")

    suspend fun handleAuthFailure(e: NetworkAccessException, clearLegacy: Boolean = false) {
        val authDead = e.unauthorized ||
            e.message.orEmpty().contains("too many failed auth", ignoreCase = true)
        if (authDead) {
            viewModel.markSignedOut()
            repository.saveNetworkAccessSession(host.id, null)
            if (clearLegacy) repository.clearLegacyNetworkAccessToken(host.id)
            onSignedOut()
        }
    }

    suspend fun refresh(client: NetworkAccessClient, showLoading: Boolean) {
        try {
            viewModel.refresh(client, host.id, showLoading = showLoading)
        } catch (e: NetworkAccessException) {
            handleAuthFailure(e)
        }
    }

    suspend fun completeLogin(client: NetworkAccessClient) {
        val session = client.sessionToken
        if (session.isNullOrBlank()) throw NetworkAccessException("Login failed")
        repository.saveNetworkAccessSession(host.id, session)
        onClientReady(client)
        refresh(client, showLoading = true)
    }

    LaunchedEffect(host.id) {
        authCredentialInput = ""
        // Returning from a chat remounts this screen — keep the cached list and only
        // refresh quietly in the background when we already loaded this host.
        if (viewModel.hasCachedProjects(host.id)) {
            val existing = networkClient
            if (existing?.sessionToken != null) {
                refresh(existing, showLoading = false)
                return@LaunchedEffect
            }
        }
        val storedSession = repository.networkAccessSession(host.id)
        val legacyToken = repository.legacyNetworkAccessToken(host.id)
        when {
            !storedSession.isNullOrBlank() -> {
                val existing = networkClient
                val client = if (existing != null && existing.sessionToken == storedSession) {
                    existing
                } else {
                    NetworkAccessClient(
                        host.resolvedNetworkAccessBaseUrl(),
                        okHttpClient = okHttpClient,
                    ).also { it.sessionToken = storedSession }
                }
                try {
                    onClientReady(client)
                    refresh(client, showLoading = !viewModel.hasCachedProjects(host.id))
                } catch (e: Exception) {
                    viewModel.markSignedOut()
                    if (e !is NetworkAccessException) {
                        viewModel.setError(e.message)
                    }
                    repository.saveNetworkAccessSession(host.id, null)
                    onSignedOut()
                    if (client !== networkClient) client.close()
                }
            }
            !legacyToken.isNullOrBlank() -> {
                val client = NetworkAccessClient(
                    host.resolvedNetworkAccessBaseUrl(),
                    okHttpClient = okHttpClient,
                )
                try {
                    client.loginWithToken(legacyToken)
                    completeLogin(client)
                } catch (e: Exception) {
                    viewModel.markSignedOut()
                    repository.clearLegacyNetworkAccessToken(host.id)
                    onSignedOut()
                    client.close()
                }
            }
            else -> {
                viewModel.markSignedOut()
                onSignedOut()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.palette.windowBg),
    ) {
        val showFullLoading = loading && (!signedIn || groups.isEmpty())
        Column(Modifier.fillMaxSize()) {
            MobileHeader(
                title = "Projects",
                subtitle = "${host.displayName} ($hostEndpoint)",
                actions = {
                    if (signedIn) {
                        Box {
                            IconButton(
                                onClick = { overflowMenuOpen = true },
                                modifier = Modifier.size(AndyLayout.ControlHeightMd),
                                contentDescription = "More actions",
                            ) {
                                LucideIcon(
                                    Lucide.Ellipsis,
                                    contentDescription = null,
                                    tint = tokens.palette.textSecondary,
                                )
                            }
                            DropdownMenu(
                                expanded = overflowMenuOpen,
                                onDismissRequest = { overflowMenuOpen = false },
                                shape = AndyShape.Menu,
                                containerColor = tokens.palette.surfacePopover,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh", color = tokens.palette.textPrimary) },
                                    onClick = {
                                        overflowMenuOpen = false
                                        scope.launch {
                                            networkClient?.let { refresh(it, showLoading = groups.isEmpty()) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out", color = tokens.palette.textPrimary) },
                                    onClick = {
                                        overflowMenuOpen = false
                                        viewModel.markSignedOut()
                                        networkClient?.sessionToken = null
                                        repository.saveNetworkAccessSession(host.id, null)
                                        onSignedOut()
                                    },
                                )
                            }
                        }
                    }
                },
            )

            if (showFullLoading) {
                ProjectsLoadingState(
                    label = if (!signedIn) "Connecting…" else "Loading projects…",
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (!signedIn) {
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
                            LucideIcon(
                                Lucide.Lock,
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
                                    viewModel.setLoading(true)
                                    viewModel.clearError()
                                    try {
                                        val client = NetworkAccessClient(
                                            host.resolvedNetworkAccessBaseUrl(),
                                            okHttpClient = okHttpClient,
                                        )
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
                                        viewModel.markSignedOut()
                                        viewModel.setError(e.message ?: "Login failed")
                                        onSignedOut()
                                    } finally {
                                        viewModel.setLoading(false)
                                    }
                                }
                            },
                            enabled = authCredentialInput.isNotBlank() && !loading,
                            shape = AndyShape.Interactive,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Connect")
                        }
                    }
                }
            } else {
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = AndySpace.Space4),
                        color = tokens.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (groups.isEmpty()) {
                    EmptyState(
                        title = "No projects found",
                        description = "Start a new chat to run tasks with autonomous agents on this host.",
                        actions = {
                            Button(onClick = onNewChat, shape = AndyShape.Interactive) {
                                LucideIcon(
                                    Lucide.Plus,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = LocalContentColor.current,
                                )
                                Spacer(Modifier.width(AndySpace.Space2))
                                Text("New chat")
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(AndySpace.Space6),
                    )
                } else {
                    // Flatten headers + chats into one LazyColumn so expanding a project with
                    // hundreds of chats only composes visible rows (no height animation of 500).
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = AndySpace.Space4,
                            end = AndySpace.Space4,
                            bottom = 96.dp,
                        ),
                    ) {
                        groups.forEachIndexed { index, group ->
                            val isExpanded = group.projectId in expanded
                            if (index > 0) {
                                item(
                                    key = "gap-${group.projectId}",
                                    contentType = "gap",
                                ) {
                                    Spacer(Modifier.height(AndySpace.Space3))
                                }
                            }
                            item(
                                key = "project-${group.projectId}",
                                contentType = "project-header",
                            ) {
                                ProjectHeader(
                                    group = group,
                                    expanded = isExpanded,
                                    onToggle = { viewModel.toggleProjectExpanded(group.projectId) },
                                )
                            }
                            if (isExpanded) {
                                items(
                                    items = group.chats,
                                    key = { chat -> "chat-${group.projectId}-${chat.id}" },
                                    contentType = { "chat" },
                                ) { chat ->
                                    ChatRow(chat = chat, onClick = { onOpenChat(chat.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (signedIn && !showFullLoading) {
            FloatingActionButton(
                onClick = onNewChat,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AndySpace.Space4),
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                shape = AndyShape.Sheet,
            ) {
                LucideIcon(
                    Lucide.Plus,
                    contentDescription = "New chat",
                    modifier = Modifier.size(24.dp),
                    tint = tokens.onAccent,
                )
            }
        }
    }
}

@Composable
private fun ProjectsLoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            ThinkingOrb(
                size = 48.dp,
                color = Cyan,
                contentDescription = label,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.palette.textSecondary,
            )
        }
    }
}

@Composable
private fun ProjectHeader(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
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
            LucideIcon(
                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = tokens.palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
            LucideIcon(
                Lucide.Folder,
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
    }
}

@Composable
private fun ChatRow(chat: ChatDto, onClick: () -> Unit) {
    val tokens = andyTokens()
    val attentionVariant = when {
        chat.userInputRequest != null ||
            chat.status.equals("Blocked", ignoreCase = true) -> StatusDotVariant.Warning
        chat.status.equals("Error", ignoreCase = true) -> StatusDotVariant.Error
        chat.awaitingPlanConfirmation() -> StatusDotVariant.Success
        else -> null
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
        if (attentionVariant != null) {
            StatusDot(variant = attentionVariant)
        } else {
            Spacer(modifier = Modifier.size(8.dp))
        }
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
                    chat.displayStatusLabel().takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.palette.textTertiary,
            )
        }
        LucideIcon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = tokens.palette.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}
