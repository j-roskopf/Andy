package com.joetr.andy.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.updates.AndroidAppUpdateService
import com.joetr.andy.mobile.data.vnc.RfbClient
import com.joetr.andy.mobile.di.SessionManager
import com.joetr.andy.mobile.navigation.MobileTab
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Inject
class HostsViewModel(
    val repository: HostRepository,
) : ViewModel() {
    val hosts: StateFlow<List<SavedHost>> = repository.hosts
    val selectedHostId: StateFlow<String?> = repository.selectedHostId

    fun selectHost(id: String?) = repository.selectHost(id)

    fun deleteHost(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}

@Inject
class ProjectsViewModel(
    val repository: HostRepository,
    val sessionManager: SessionManager,
) : ViewModel() {
    val selectedHost: SavedHost?
        get() = repository.selectedHost

    val client
        get() = sessionManager.client
}

@Inject
class SettingsViewModel(
    val updates: AndroidAppUpdateService,
) : ViewModel()

@Inject
class ScreenViewModel(
    val repository: HostRepository,
) : ViewModel() {
    val client = RfbClient()
    val presenter = RemoteFramePresenter()

    val selectedHost: SavedHost?
        get() = repository.selectedHost

    override fun onCleared() {
        runCatching { client.close() }
        super.onCleared()
    }
}

@Inject
class HostEditorViewModel(
    val repository: HostRepository,
) : ViewModel()

@Inject
class NewChatViewModel(
    val sessionManager: SessionManager,
) : ViewModel() {
    val client
        get() = sessionManager.client
}

@Inject
class ChatViewModel(
    val sessionManager: SessionManager,
    val repository: HostRepository,
) : ViewModel() {
    val client
        get() = sessionManager.client

    val host: SavedHost?
        get() = repository.selectedHost
}
