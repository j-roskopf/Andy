package com.joetr.andy.mobile.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

enum class MobileTab(val label: String, val icon: ImageVector) {
    Hosts("Hosts", Icons.Outlined.Computer),
    Screen("Screen", Icons.Outlined.DesktopWindows),
    Projects("Projects", Icons.Outlined.ChatBubbleOutline),
    Settings("Settings", Icons.Outlined.Settings),
}

@Serializable
sealed interface MobileNavKey : NavKey {
    @Serializable
    data class Tabs(val tab: MobileTab = MobileTab.Hosts) : MobileNavKey

    @Serializable
    data class EditHost(val hostId: String? = null) : MobileNavKey

    @Serializable
    data object NewChat : MobileNavKey

    @Serializable
    data class Chat(val chatId: String) : MobileNavKey
}

fun MobileNavKey.isSessionDependent(): Boolean =
    this is MobileNavKey.NewChat || this is MobileNavKey.Chat
