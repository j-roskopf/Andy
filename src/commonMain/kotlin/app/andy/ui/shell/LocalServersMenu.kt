package app.andy.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.local_servers_globe
import app.andy.andy.generated.resources.local_servers_refresh
import app.andy.service.AndyServices
import app.andy.service.LocalServerProcess
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Toolbar trigger for host localhost development servers. Panel content is hosted in the
 * top-chrome [ChromeFlyout] via [LocalServersFlyout] so it reflows layout instead of using Popup.
 */
@Composable
internal fun LocalServersMenu(
    services: AndyServices,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by services.localServers.servers.collectAsState()

    // Only poll while this chrome is composed (Actions destination). App-wide init polling
    // forked lsof/ps from launch and burned CPU before Projects was ever opened.
    DisposableEffect(services.localServers) {
        services.localServers.startWatching()
        onDispose { services.localServers.stopWatching() }
    }

    LocalServersTrigger(
        count = servers.size,
        selected = expanded,
        onClick = { onExpandedChange(!expanded) },
        modifier = modifier,
    )
}

@Composable
internal fun LocalServersFlyout(
    services: AndyServices,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val servers by services.localServers.servers.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var stoppingPid by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        refreshing = true
        services.localServers.refresh()
        refreshing = false
    }

    LocalServersPanel(
        servers = servers,
        refreshing = refreshing,
        stoppingPid = stoppingPid,
        onRefresh = {
            scope.launch {
                refreshing = true
                services.localServers.refresh()
                refreshing = false
            }
        },
        onStop = { server ->
            val port = server.ports.firstOrNull() ?: return@LocalServersPanel
            scope.launch {
                stoppingPid = server.pid
                services.localServers.stop(server.pid, port)
                stoppingPid = null
            }
        },
        onOpenInBrowser = { url ->
            onDismiss()
            onOpenInBrowser(url)
        },
    )
}

@Composable
private fun LocalServersTrigger(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(AndyShape.Interactive)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        LocalServersGlobeIcon(
            color = if (count > 0) Green else TextSecondary,
            modifier = Modifier.size(AndyLayout.IconMd),
        )
        Text(
            "Local Servers",
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            count.toString(),
            color = if (count > 0) Green else TextSecondary.copy(alpha = 0.55f),
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LocalServersPanel(
    servers: List<LocalServerProcess>,
    refreshing: Boolean,
    stoppingPid: Int?,
    onRefresh: () -> Unit,
    onStop: (LocalServerProcess) -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    refreshing && servers.isEmpty() -> "Scanning ports…"
                    servers.isEmpty() -> "No servers running"
                    servers.size == 1 -> "1 server running"
                    else -> "${servers.size} servers running"
                },
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(AndyRadius.Control))
                    .clickable(enabled = !refreshing, onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                LocalServersRefreshIcon(
                    color = if (refreshing) TextSecondary.copy(alpha = 0.4f) else TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        when {
            refreshing && servers.isEmpty() -> {
                LocalServersPlaceholder(
                    title = "Scanning…",
                    subtitle = null,
                )
            }
            servers.isEmpty() -> {
                LocalServersPlaceholder(
                    title = "No servers running",
                    subtitle = "Local dev servers will appear here.",
                )
            }
            else -> {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    servers.forEach { server ->
                        LocalServerRow(
                            server = server,
                            stopping = stoppingPid == server.pid,
                            onStop = { onStop(server) },
                            onOpenInBrowser = server.browserUrl?.let { url ->
                                { onOpenInBrowser(url) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalServerRow(
    server: LocalServerProcess,
    stopping: Boolean,
    onStop: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        GlowingDot(isGreen = true, modifier = Modifier.size(AndyLayout.IconMd))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                server.displayName,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.addressLabel,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                server.folderLabel?.let { folder ->
                    Text("·", color = TextSecondary.copy(alpha = 0.4f), fontSize = 10.sp)
                    Text(
                        folder,
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontFamily = DisplayFont,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            server.ownerLabel?.let { label ->
                Text(
                    label,
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onOpenInBrowser != null) {
                Text(
                    "Open in browser tab",
                    color = Cyan,
                    fontFamily = DisplayFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AndyRadius.Control))
                        .semantics {
                            contentDescription = "Open ${server.addressLabel} in browser tab"
                            role = Role.Button
                        }
                        .clickable(onClick = onOpenInBrowser),
                )
            }
        }
        val stoppable = server.isStoppable && !stopping
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(AndyRadius.Control))
                .clickable(enabled = stoppable, onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            if (stopping) {
                Text("…", color = TextSecondary, fontSize = 12.sp)
            } else {
                LocalServersStopIcon(
                    color = if (server.isStoppable) Red.copy(alpha = 0.75f) else TextSecondary.copy(alpha = 0.3f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LocalServersPlaceholder(
    title: String,
    subtitle: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AndySpace.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        LocalServersGlobeIcon(
            color = TextSecondary.copy(alpha = 0.35f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(AndySpace.Space1))
        Text(title, color = TextPrimary, fontFamily = DisplayFont, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Text(subtitle, color = TextSecondary, fontFamily = DisplayFont, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LocalServersGlobeIcon(color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.local_servers_globe),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
private fun LocalServersRefreshIcon(color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.local_servers_refresh),
        contentDescription = "Refresh",
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
private fun LocalServersStopIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), stroke.width, StrokeCap.Round)
    }
}
