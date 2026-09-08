package com.joetr.andy.mobile

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.andy.service.AppUpdateState
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTheme
import app.andy.ui.theme.AndyTint
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.attention.AttentionPushService
import com.joetr.andy.mobile.di.AndyMobileGraph
import com.joetr.andy.mobile.navigation.MobileNavKey
import com.joetr.andy.mobile.navigation.MobileTab
import com.joetr.andy.mobile.navigation.isSessionDependent
import com.joetr.andy.mobile.ui.ChatScreen
import com.joetr.andy.mobile.ui.ChatViewModel
import com.joetr.andy.mobile.ui.HostEditorScreen
import com.joetr.andy.mobile.ui.HostEditorViewModel
import com.joetr.andy.mobile.ui.HostsScreen
import com.joetr.andy.mobile.ui.HostsViewModel
import com.joetr.andy.mobile.ui.NewChatScreen
import com.joetr.andy.mobile.ui.NewChatViewModel
import com.joetr.andy.mobile.ui.ProjectsScreen
import com.joetr.andy.mobile.ui.ProjectsViewModel
import com.joetr.andy.mobile.ui.ScreenViewModel
import com.joetr.andy.mobile.ui.ScreenViewerScreen
import com.joetr.andy.mobile.ui.SettingsScreen
import com.joetr.andy.mobile.ui.SettingsViewModel

/**
 * Andy Android companion — remote hosts, VNC screen, Network Access projects.
 *
 * Navigation 3 owns the back stack; Metro [AndyMobileGraph] owns process-scoped deps and the
 * Network Access session child graph.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AndyMobileApp(
    graph: AndyMobileGraph,
    pendingOpenChatId: String? = null,
    onPendingOpenChatConsumed: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = graph.hostRepository
    val sessionManager = graph.sessionManager
    val updateService = graph.updateService
    val notifications = graph.notificationService
    val updateState by updateService.state.collectAsStateWithLifecycle()
    val updateAvailable = updateState is AppUpdateState.Available

    val initialTab = repository.selectedTab.value
    val backStack = rememberNavBackStack(MobileNavKey.Tabs(initialTab))

    var screenFullScreen by remember { mutableStateOf(true) }
    var screenKeyboardOpen by remember { mutableStateOf(false) }

    val topKey = backStack.lastOrNull()
    val viewingChatId = (topKey as? MobileNavKey.Chat)?.chatId
    val currentTab = (backStack.firstOrNull() as? MobileNavKey.Tabs)?.tab ?: MobileTab.Hosts

    fun popSessionRoutes() {
        while ((backStack.lastOrNull() as? MobileNavKey)?.isSessionDependent() == true) {
            backStack.removeLastOrNull()
        }
    }

    fun ensureTabs(tab: MobileTab = currentTab) {
        if (backStack.isEmpty() || backStack.first() !is MobileNavKey.Tabs) {
            backStack.clear()
            backStack.add(MobileNavKey.Tabs(tab))
        } else {
            backStack[0] = MobileNavKey.Tabs(tab)
            repository.selectTab(tab)
        }
    }

    fun navigateReplaceSession(key: MobileNavKey) {
        popSessionRoutes()
        if (backStack.lastOrNull() is MobileNavKey.EditHost) {
            backStack.removeLastOrNull()
        }
        backStack.add(key)
    }

    fun openChat(chatId: String) {
        ensureTabs(MobileTab.Projects)
        popSessionRoutes()
        backStack.add(MobileNavKey.Chat(chatId))
    }

    LifecycleResumeEffect(viewingChatId) {
        AttentionPushService.viewingChatId = viewingChatId
        viewingChatId?.let { notifications.cancel(it) }
        onPauseOrDispose {
            if (AttentionPushService.viewingChatId == viewingChatId) {
                AttentionPushService.viewingChatId = null
            }
        }
    }

    LaunchedEffect(Unit) {
        updateService.checkForUpdates()
        // Eager session rebuild when restored stack has session routes.
        val needsSession = backStack.any { (it as? MobileNavKey)?.isSessionDependent() == true }
        if (needsSession) {
            if (!sessionManager.restoreFromStoredSession()) {
                popSessionRoutes()
            }
        }
    }

    LaunchedEffect(pendingOpenChatId) {
        val chatId = pendingOpenChatId ?: return@LaunchedEffect
        if (sessionManager.client?.sessionToken.isNullOrBlank()) {
            if (!sessionManager.restoreFromStoredSession()) return@LaunchedEffect
        }
        openChat(chatId)
        onPendingOpenChatConsumed()
    }

    DisposableEffect(Unit) {
        onDispose {
            updateService.close()
        }
    }

    // Screen tab root: peel keyboard → fullscreen before finishing.
    val onTabsRoot = backStack.size <= 1 && backStack.lastOrNull() is MobileNavKey.Tabs
    BackHandler(enabled = onTabsRoot && currentTab == MobileTab.Screen && (screenKeyboardOpen || screenFullScreen)) {
        when {
            screenKeyboardOpen -> screenKeyboardOpen = false
            screenFullScreen -> screenFullScreen = false
        }
    }
    BackHandler(enabled = onTabsRoot && !(currentTab == MobileTab.Screen && (screenKeyboardOpen || screenFullScreen))) {
        activity?.finish()
    }

    AndyTheme(
        tintId = AndyTint.Default.id,
        surfaceModeId = AndySurfaceMode.PitchBlack.id,
    ) {
        val tokens = andyTokens()
        val hosts by repository.hosts.collectAsStateWithLifecycle()
        val selectedId by repository.selectedHostId.collectAsStateWithLifecycle()
        val selectedHost = hosts.firstOrNull { it.id == selectedId } ?: hosts.firstOrNull()
        val sessionClient = sessionManager.client
        // Activity-scoped so project list / expand state survives Chat pushes that
        // temporarily remove the Tabs entry from composition.
        val projectsVm: ProjectsViewModel = viewModel(factory = graph.factory<ProjectsViewModel>())

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key ->
                when (val route = key as? MobileNavKey) {
                    is MobileNavKey.Tabs -> NavEntry(key) {
                        val hideTabBar = (route.tab == MobileTab.Screen && (screenFullScreen || screenKeyboardOpen)) ||
                            WindowInsets.isImeVisible
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                            ),
                            containerColor = tokens.palette.windowBg,
                            bottomBar = {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = !hideTabBar,
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
                                                    selected = route.tab == item,
                                                    onClick = {
                                                        ensureTabs(item)
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
                                                            fontWeight = if (route.tab == item) {
                                                                FontWeight.SemiBold
                                                            } else {
                                                                FontWeight.Medium
                                                            },
                                                        )
                                                    },
                                                    colors = NavigationBarItemDefaults.colors(
                                                        selectedIconColor = tokens.accent,
                                                        selectedTextColor = tokens.palette.textPrimary,
                                                        indicatorColor = tokens.accentSubtle,
                                                        unselectedIconColor = if (
                                                            item == MobileTab.Settings && updateAvailable
                                                        ) {
                                                            tokens.accent
                                                        } else {
                                                            tokens.palette.textTertiary
                                                        },
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
                            when (route.tab) {
                                MobileTab.Hosts -> {
                                    val vm: HostsViewModel = viewModel(factory = graph.factory<HostsViewModel>())
                                    HostsScreen(
                                        modifier = contentModifier,
                                        repository = vm.repository,
                                        hosts = hosts,
                                        selectedHostId = selectedId,
                                        onAddHost = { backStack.add(MobileNavKey.EditHost(null)) },
                                        onEditHost = { backStack.add(MobileNavKey.EditHost(it)) },
                                        onOpenScreen = {
                                            vm.selectHost(it)
                                            ensureTabs(MobileTab.Screen)
                                            screenFullScreen = true
                                        },
                                        onOpenProjects = {
                                            vm.selectHost(it)
                                            ensureTabs(MobileTab.Projects)
                                        },
                                    )
                                }
                                MobileTab.Screen -> {
                                    val vm: ScreenViewModel = viewModel(factory = graph.factory<ScreenViewModel>())
                                    ScreenViewerScreen(
                                        modifier = contentModifier,
                                        host = selectedHost,
                                        repository = repository,
                                        rfbClient = vm.client,
                                        presenter = vm.presenter,
                                        onNeedHost = { ensureTabs(MobileTab.Hosts) },
                                        fullScreen = screenFullScreen,
                                        onFullScreenChange = { screenFullScreen = it },
                                        keyboardOpen = screenKeyboardOpen,
                                        onKeyboardOpenChange = { screenKeyboardOpen = it },
                                    )
                                }
                                MobileTab.Projects -> {
                                    ProjectsScreen(
                                        modifier = contentModifier,
                                        host = selectedHost,
                                        repository = repository,
                                        networkClient = sessionClient,
                                        okHttpClient = graph.interactiveOkHttp,
                                        viewModel = projectsVm,
                                        onClientReady = { client ->
                                            sessionManager.adoptClient(client)
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
                                        onSignedOut = {
                                            sessionManager.clearSession()
                                            popSessionRoutes()
                                            AttentionPushService.stop(context)
                                        },
                                        onOpenChat = { openChat(it) },
                                        onNewChat = {
                                            ensureTabs(MobileTab.Projects)
                                            navigateReplaceSession(MobileNavKey.NewChat)
                                        },
                                        onNeedHost = { ensureTabs(MobileTab.Hosts) },
                                    )
                                }
                                MobileTab.Settings -> {
                                    val vm: SettingsViewModel = viewModel(factory = graph.factory<SettingsViewModel>())
                                    SettingsScreen(
                                        modifier = contentModifier,
                                        updates = vm.updates,
                                        networkClient = sessionClient,
                                    )
                                }
                            }
                        }
                    }
                    is MobileNavKey.EditHost -> NavEntry(key) {
                        val vm: HostEditorViewModel = viewModel(factory = graph.factory<HostEditorViewModel>())
                        HostEditorScreen(
                            hostId = route.hostId,
                            repository = vm.repository,
                            onDone = { backStack.removeLastOrNull() },
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                    }
                    is MobileNavKey.Chat -> NavEntry(key) {
                        val vm: ChatViewModel = viewModel(
                            key = route.chatId,
                            factory = graph.factory<ChatViewModel>(),
                        )
                        val client = vm.client
                        val host = vm.host
                        if (client == null || host == null) {
                            LaunchedEffect(Unit) {
                                popSessionRoutes()
                                ensureTabs(MobileTab.Projects)
                            }
                        } else {
                            ChatScreen(
                                chatId = route.chatId,
                                host = host,
                                client = client,
                                onBack = { backStack.removeLastOrNull() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing),
                            )
                        }
                    }
                    MobileNavKey.NewChat -> NavEntry(key) {
                        val vm: NewChatViewModel = viewModel(factory = graph.factory<NewChatViewModel>())
                        val client = vm.client
                        if (client == null || selectedHost == null) {
                            LaunchedEffect(Unit) {
                                popSessionRoutes()
                                ensureTabs(MobileTab.Projects)
                            }
                        } else {
                            NewChatScreen(
                                client = client,
                                onBack = { backStack.removeLastOrNull() },
                                onStarted = { id ->
                                    backStack.removeLastOrNull() // pop NewChat
                                    backStack.add(MobileNavKey.Chat(id))
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing),
                            )
                        }
                    }
                    null -> NavEntry(key) { Text("Unknown route") }
                }
            },
        )
    }
}

private inline fun <reified VM : ViewModel> AndyMobileGraph.factory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val created: ViewModel = when (VM::class) {
                HostsViewModel::class -> HostsViewModel(hostRepository)
                ProjectsViewModel::class -> ProjectsViewModel(hostRepository, sessionManager)
                SettingsViewModel::class -> SettingsViewModel(updateService)
                ScreenViewModel::class -> ScreenViewModel(hostRepository)
                HostEditorViewModel::class -> HostEditorViewModel(hostRepository)
                NewChatViewModel::class -> NewChatViewModel(sessionManager)
                ChatViewModel::class -> ChatViewModel(sessionManager, hostRepository)
                else -> error("Unknown ViewModel: ${VM::class}")
            }
            return created as T
        }
    }
