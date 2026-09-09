package com.joetr.andy.mobile.navigation

import androidx.navigation3.runtime.NavKey
import app.andy.ui.components.Lucide
import kotlinx.serialization.Serializable

enum class MobileTab(val label: String, val icon: String) {
    Hosts("Hosts", Lucide.Monitor),
    Screen("Screen", Lucide.ScreenShare),
    Projects("Projects", Lucide.MessageSquare),
    Settings("Settings", Lucide.Settings),
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
