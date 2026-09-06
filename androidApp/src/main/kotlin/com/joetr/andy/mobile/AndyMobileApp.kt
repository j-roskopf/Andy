package com.joetr.andy.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTheme
import app.andy.ui.theme.AndyTint
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.attention.AndroidChatNotificationService
import com.joetr.andy.mobile.data.attention.AttentionPushService
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.ui.ChatScreen
import com.joetr.andy.mobile.ui.HostEditorScreen
import com.joetr.andy.mobile.ui.HostsScreen
import com.joetr.andy.mobile.ui.NewChatScreen
import com.joetr.andy.mobile.ui.ProjectsScreen
import com.joetr.andy.mobile.ui.ScreenViewerScreen

enum class MobileTab(val label: String, val icon: ImageVector) {
    Hosts("Hosts", Icons.Outlined.Computer),
    Screen("Screen", Icons.Outlined.DesktopWindows),
    Projects("Projects", Icons.Outlined.ChatBubbleOutline),
}

sealed interface MobileRoute {
    data object Tabs : MobileRoute
    data class EditHost(val hostId: String?) : MobileRoute
    data class Chat(val chatId: String) : MobileRoute
    data object NewChat : MobileRoute
}

/**
 * Andy Android companion — remote hosts, VNC screen, Network Access projects.
 *
 * Design Read: native Android remote-control companion for developers who already use
 * Andy Desktop, with a calm premium-tool language (Linear / Apple Continuity vibe),
 * leaning toward Andy’s existing token language + mobile-first composition —
 * NOT Material-default purple, NOT the desktop sidebar shell, NOT a web-chat clone.
 *
 * Dials: DESIGN_VARIANCE 6 · MOTION_INTENSITY 5 · VISUAL_DENSITY 4
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AndyMobileApp(
    pendingOpenChatId: String? = null,
    onPendingOpenChatConsumed: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { HostRepository(context) }
    var route by remember { mutableStateOf<MobileRoute>(MobileRoute.Tabs) }
    var tab by remember { mutableStateOf(MobileTab.Hosts) }
    var networkClient by remember { mutableStateOf<NetworkAccessClient?>(null) }
    // The remote screen is worth every pixel, so opening it hides the tab bar until the
    // viewer's own full-screen toggle brings it back.
    var screenFullScreen by remember { mutableStateOf(true) }
    var screenKeyboardOpen by remember { mutableStateOf(false) }
    val viewingChatId = (route as? MobileRoute.Chat)?.chatId
    val notifications = remember { AndroidChatNotificationService(context) }
    // Only suppress alerts while the chat is actually on-screen in the foreground.
    // Backgrounding with the chat route still open must still notify (desktop does).
    LifecycleResumeEffect(viewingChatId) {
        AttentionPushService.viewingChatId = viewingChatId
        viewingChatId?.let { notifications.cancel(it) }
        onPauseOrDispose {
            if (AttentionPushService.viewingChatId == viewingChatId) {
                AttentionPushService.viewingChatId = null
            }
        }
    }

    LaunchedEffect(pendingOpenChatId) {
        if (pendingOpenChatId != null) {
            tab = MobileTab.Projects
        }
    }

    LaunchedEffect(pendingOpenChatId, networkClient?.sessionToken) {
        val chatId = pendingOpenChatId ?: return@LaunchedEffect
        if (networkClient?.sessionToken.isNullOrBlank()) return@LaunchedEffect
        tab = MobileTab.Projects
        route = MobileRoute.Chat(chatId)
        onPendingOpenChatConsumed()
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leave AttentionPushService running — it outlives the activity for background alerts.
            networkClient?.close()
        }
    }

    AndyTheme(
        tintId = AndyTint.Default.id,
        surfaceModeId = AndySurfaceMode.PitchBlack.id,
    ) {
        val tokens = andyTokens()
        val hosts by repository.hosts.collectAsStateWithLifecycle()
        val selectedId by repository.selectedHostId.collectAsStateWithLifecycle()
        val selectedHost = hosts.firstOrNull { it.id == selectedId } ?: hosts.firstOrNull()

        AnimatedContent(
            targetState = route,
            transitionSpec = {
                (
                    fadeIn(AndyMotion.standardTween()) +
                        slideInVertically(animationSpec = AndyMotion.standardTween()) { it / 24 }
                    ) togetherWith (
                    fadeOut(AndyMotion.standardTween()) +
                        slideOutVertically(animationSpec = AndyMotion.standardTween()) { -it / 32 }
                    )
            },
            label = "mobile-route",
        ) { current ->
            when (current) {
                MobileRoute.Tabs -> {
                    val hideTabBar = (tab == MobileTab.Screen && (screenFullScreen || screenKeyboardOpen)) ||
                        WindowInsets.isImeVisible
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                        ),
                        containerColor = tokens.palette.windowBg,
                        bottomBar = {
                            AnimatedVisibility(
                                visible = !hideTabBar,
                                enter = slideInVertically { it } + fadeIn(),
                                exit = slideOutVertically { it } + fadeOut(),
                            ) {
                                Column {
                                    AndyHorizontalDivider(color = tokens.palette.border)
                                    NavigationBar(
                                        containerColor = tokens.palette.sidebarBg,
                                        tonalElevation = 0.dp,
                                        windowInsets = NavigationBarDefaults.windowInsets,
                                    ) {
                                        MobileTab.entries.forEach { item ->
                                            NavigationBarItem(
                                                selected = tab == item,
                                                onClick = {
                                                    tab = item
                                                    if (item == MobileTab.Screen) {
                                                        screenFullScreen = true
                                                    } else {
                                                        screenKeyboardOpen = false
                                                    }
                                                },
                                                icon = {
                                                    Icon(item.icon, contentDescription = item.label)
                                                },
                                                label = {
                                                    Text(
                                                        item.label,
                                                        fontFamily = DisplayFont,
                                                        fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Medium,
                                                    )
                                                },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = tokens.accent,
                                                    selectedTextColor = tokens.palette.textPrimary,
                                                    indicatorColor = tokens.accentSubtle,
                                                    unselectedIconColor = tokens.palette.textTertiary,
                                                    unselectedTextColor = tokens.palette.textTertiary,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        val contentModifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                        when (tab) {
                            MobileTab.Hosts -> HostsScreen(
                                modifier = contentModifier,
                                repository = repository,
                                hosts = hosts,
                                selectedHostId = selectedId,
                                onAddHost = { route = MobileRoute.EditHost(null) },
                                onEditHost = { route = MobileRoute.EditHost(it) },
                                onOpenScreen = {
                                    repository.selectHost(it)
                                    tab = MobileTab.Screen
                                    screenFullScreen = true
                                },
                                onOpenProjects = {
                                    repository.selectHost(it)
                                    tab = MobileTab.Projects
                                },
                            )
                            MobileTab.Screen -> ScreenViewerScreen(
                                modifier = contentModifier,
                                host = selectedHost,
                                repository = repository,
                                onNeedHost = { tab = MobileTab.Hosts },
                                fullScreen = screenFullScreen,
                                onFullScreenChange = { screenFullScreen = it },
                                keyboardOpen = screenKeyboardOpen,
                                onKeyboardOpenChange = { screenKeyboardOpen = it },
                            )
                            MobileTab.Projects -> ProjectsScreen(
                                modifier = contentModifier,
                                host = selectedHost,
                                repository = repository,
                                networkClient = networkClient,
                                onClientReady = { client ->
                                    networkClient?.close()
                                    networkClient = client
                                    val token = client.sessionToken
                                    val host = selectedHost
                                    if (host != null && !token.isNullOrBlank()) {
                                        AttentionPushService.start(
                                            context = context,
                                            baseUrl = host.resolvedNetworkAccessBaseUrl(),
                                            sessionToken = token,
                                            hostName = host.displayName,
                                        )
                                    }
                                    onRequestNotificationPermission()
                                },
                                onSignedOut = { AttentionPushService.stop(context) },
                                onOpenChat = { route = MobileRoute.Chat(it) },
                                onNewChat = { route = MobileRoute.NewChat },
                                onNeedHost = { tab = MobileTab.Hosts },
                            )
                        }
                    }
                }
                is MobileRoute.EditHost -> HostEditorScreen(
                    hostId = current.hostId,
                    repository = repository,
                    onDone = { route = MobileRoute.Tabs },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                )
                is MobileRoute.Chat -> {
                    val client = networkClient
                    if (client == null || selectedHost == null) {
                        route = MobileRoute.Tabs
                        tab = MobileTab.Projects
                    } else {
                        ChatScreen(
                            chatId = current.chatId,
                            host = selectedHost,
                            client = client,
                            onBack = { route = MobileRoute.Tabs },
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                    }
                }
                MobileRoute.NewChat -> {
                    val client = networkClient
                    if (client == null || selectedHost == null) {
                        route = MobileRoute.Tabs
                        tab = MobileTab.Projects
                    } else {
                        NewChatScreen(
                            client = client,
                            onBack = { route = MobileRoute.Tabs },
                            onStarted = { id -> route = MobileRoute.Chat(id) },
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                    }
                }
            }
        }
    }
}
