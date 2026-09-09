package com.joetr.andy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.andy.ui.components.StatusTag
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostReachability
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.HostStatus
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.VncReachability
import kotlinx.coroutines.launch

@Composable
fun HostsScreen(
    repository: HostRepository,
    hosts: List<SavedHost>,
    selectedHostId: String?,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenScreen: (String) -> Unit,
    onOpenProjects: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val scope = rememberCoroutineScope()
    val statuses = remember { mutableStateMapOf<String, HostStatus>() }
    val selectedHost = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()

    LaunchedEffect(hosts) {
        hosts.forEach { host ->
            statuses[host.id] = HostStatus(HostReachability.Checking)
            val status = VncReachability.probe(host.vncHost(), host.vncPort)
            statuses[host.id] = status
        }
    }

    Box(modifier.background(tokens.palette.windowBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (hosts.isNotEmpty()) {
                MobileHeader(
                    title = "Hosts",
                    subtitle = selectedHost?.let { "${it.displayName} · ${it.vncHost()}:${it.vncPort}" }
                        ?: "Select a laptop to remote into",
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = AndySpace.Space4,
                        end = AndySpace.Space4,
                        top = AndySpace.Space2,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                ) {
                    items(hosts, key = { it.id }, contentType = { "host" }) { host ->
                        HostCard(
                            host = host,
                            selected = host.id == selectedHostId,
                            status = statuses[host.id] ?: HostStatus(),
                            onSelect = { repository.selectHost(host.id) },
                            onEdit = { onEditHost(host.id) },
                            onDelete = { scope.launch { repository.delete(host.id) } },
                            onProbe = {
                                scope.launch {
                                    statuses[host.id] = HostStatus(HostReachability.Checking)
                                    statuses[host.id] =
                                        VncReachability.probe(host.vncHost(), host.vncPort)
                                }
                            },
                            onOpenScreen = { onOpenScreen(host.id) },
                            onOpenProjects = { onOpenProjects(host.id) },
                        )
                    }
                }
            } else {
                EmptyHosts(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    onAddHost = onAddHost,
                )
            }
        }
        if (hosts.isNotEmpty()) {
            FloatingActionButton(
                onClick = onAddHost,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AndySpace.Space4),
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                shape = AndyShape.Sheet,
            ) {
                LucideIcon(
                    Lucide.Plus,
                    contentDescription = "Add host",
                    modifier = Modifier.size(24.dp),
                    tint = tokens.onAccent,
                )
            }
        }
    }
}

@Composable
private fun EmptyHosts(modifier: Modifier = Modifier, onAddHost: () -> Unit) {
    val tokens = andyTokens()
    Box(
        modifier = modifier.padding(AndySpace.Space6),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = "No hosts yet",
            description = "Add a Mac or Linux laptop by Tailscale MagicDNS or 100.x address. " +
                "Then open its screen over VNC and drive projects through Network Access.",
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
                Button(
                    onClick = onAddHost,
                    shape = AndyShape.Interactive,
                ) {
                    LucideIcon(
                        Lucide.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(AndySpace.Space2))
                    Text("Add host")
                }
            },
        )
    }
}

@Composable
private fun HostCard(
    host: SavedHost,
    selected: Boolean,
    status: HostStatus,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
    onOpenScreen: () -> Unit,
    onOpenProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    var menuOpen by remember { mutableStateOf(false) }

    val (tagLabel, tagVariant) = when (status.reachability) {
        HostReachability.VncOpen -> "Online" to StatusDotVariant.Success
        HostReachability.Checking -> "Checking" to StatusDotVariant.Warning
        HostReachability.Unreachable -> "Offline" to StatusDotVariant.Error
        HostReachability.Unknown -> "Idle" to StatusDotVariant.Neutral
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .semantics {
                role = Role.Button
                contentDescription = "Host ${host.displayName}"
            },
        variant = CardVariant.Default,
        shape = AndyShape.Sheet,
        backgroundColor = if (selected) tokens.palette.surfaceSelected else tokens.palette.surfaceRaised,
        borderColor = if (selected) tokens.accent.copy(alpha = 0.55f) else tokens.palette.borderMedium,
        contentPadding = PaddingValues(AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(AndyShape.Interactive)
                    .background(if (selected) tokens.accentSubtle else tokens.palette.sidebarBg)
                    .border(
                        1.dp,
                        if (selected) tokens.accent.copy(alpha = 0.40f) else tokens.palette.borderMedium,
                        AndyShape.Interactive,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(
                    Lucide.Monitor,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (selected) tokens.accent else tokens.palette.textPrimary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${host.vncHost()}:${host.vncPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFont,
                    color = tokens.palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            StatusTag(
                label = tagLabel,
                variant = tagVariant,
            )

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(AndyLayout.ControlHeightMd),
                    contentDescription = "Host actions",
                ) {
                    LucideIcon(
                        Lucide.Ellipsis,
                        contentDescription = null,
                        tint = tokens.palette.textTertiary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = AndyShape.Menu,
                    containerColor = tokens.palette.surfacePopover,
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit host", color = tokens.palette.textPrimary) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Check VNC status", color = tokens.palette.textPrimary) },
                        onClick = { menuOpen = false; onProbe() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = tokens.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        if (status.reachability == HostReachability.Unreachable && !status.message.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AndyShape.Interactive)
                    .background(tokens.error.copy(alpha = 0.10f))
                    .border(1.dp, tokens.error.copy(alpha = 0.20f), AndyShape.Interactive)
                    .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                StatusDot(variant = StatusDotVariant.Error)
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (host.notes.isNotBlank()) {
            Text(
                text = host.notes,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Button(
                onClick = onOpenScreen,
                modifier = Modifier.weight(1f),
                shape = AndyShape.Interactive,
            ) {
                LucideIcon(
                    Lucide.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(Modifier.width(AndySpace.Space2))
                Text("Screen")
            }
            OutlinedButton(
                onClick = onOpenProjects,
                modifier = Modifier.weight(1f),
                shape = AndyShape.Interactive,
            ) {
                LucideIcon(
                    Lucide.MessageSquare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(Modifier.width(AndySpace.Space2))
                Text("Projects")
            }
        }
    }
}

@Composable
fun MobileHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = andyTokens()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AndySpace.Space4,
                end = AndySpace.Space4,
                top = AndySpace.Space5,
                bottom = AndySpace.Space3,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            if (icon != null) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = DisplayFont,
                    color = tokens.palette.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.palette.textSecondary,
                )
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

