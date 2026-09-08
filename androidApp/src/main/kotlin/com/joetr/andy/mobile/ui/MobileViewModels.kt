package com.joetr.andy.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessException
import com.joetr.andy.mobile.data.networkaccess.ProjectGroup
import com.joetr.andy.mobile.data.networkaccess.groupChatsByProject
import com.joetr.andy.mobile.data.updates.AndroidAppUpdateService
import com.joetr.andy.mobile.data.vnc.RfbClient
import com.joetr.andy.mobile.di.SessionManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _expandedProjectIds = MutableStateFlow(emptySet<String>())
    val expandedProjectIds: StateFlow<Set<String>> = _expandedProjectIds.asStateFlow()

    private val _groups = MutableStateFlow<List<ProjectGroup>>(emptyList())
    val groups: StateFlow<List<ProjectGroup>> = _groups.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Host id whose project list is currently cached in [groups]. */
    private val _loadedHostId = MutableStateFlow<String?>(null)
    val loadedHostId: StateFlow<String?> = _loadedHostId.asStateFlow()

    init {
        viewModelScope.launch {
            var previousHostId: String? = null
            repository.selectedHostId.collect { hostId ->
                if (previousHostId != null && previousHostId != hostId) {
                    _expandedProjectIds.value = emptySet()
                    _groups.value = emptyList()
                    _signedIn.value = false
                    _loading.value = false
                    _error.value = null
                    _loadedHostId.value = null
                }
                previousHostId = hostId
            }
        }
    }

    fun toggleProjectExpanded(projectId: String) {
        _expandedProjectIds.update { current ->
            if (projectId in current) current - projectId else current + projectId
        }
    }

    fun hasCachedProjects(hostId: String): Boolean =
        _loadedHostId.value == hostId && _signedIn.value

    suspend fun refresh(client: NetworkAccessClient, hostId: String, showLoading: Boolean = true) {
        if (showLoading) _loading.value = true
        _error.value = null
        try {
            val projects = client.listProjects()
            val chats = client.listChats()
            _groups.value = groupChatsByProject(projects, chats)
            _signedIn.value = true
            _loadedHostId.value = hostId
        } catch (e: NetworkAccessException) {
            _error.value = e.message
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to load chats"
        } finally {
            _loading.value = false
        }
    }

    fun markSignedIn() {
        _signedIn.value = true
    }

    fun markSignedOut() {
        _signedIn.value = false
        _groups.value = emptyList()
        _loadedHostId.value = null
        _expandedProjectIds.value = emptySet()
    }

    fun clearError() {
        _error.value = null
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun setLoading(value: Boolean) {
        _loading.value = value
    }
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
