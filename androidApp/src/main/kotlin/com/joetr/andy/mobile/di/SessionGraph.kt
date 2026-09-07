package com.joetr.andy.mobile.di

import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

@GraphExtension(SessionScope::class)
interface SessionGraph {
    val networkAccessClient: NetworkAccessClient

    @GraphExtension.Factory
    interface Factory {
        fun create(@Provides client: NetworkAccessClient): SessionGraph
    }
}

/**
 * Owns the active Network Access [SessionGraph]. Recreated on login; cleared on sign-out /
 * host switch. Callers must pop session-dependent Nav3 routes when [clearSession] returns true.
 */
@SingleIn(MobileScope::class)
@Inject
class SessionManager(
    private val sessionGraphFactory: SessionGraph.Factory,
    private val hostRepository: HostRepository,
    @InteractiveOkHttp private val interactiveOkHttp: OkHttpClient,
) {
    private val _session = MutableStateFlow<SessionGraph?>(null)
    val session: StateFlow<SessionGraph?> = _session.asStateFlow()

    val client: NetworkAccessClient?
        get() = _session.value?.networkAccessClient

    fun openSession(baseUrl: String, sessionToken: String): SessionGraph {
        clearSession()
        val networkClient = NetworkAccessClient(
            baseUrl = baseUrl,
            okHttpClient = interactiveOkHttp,
            longLived = false,
        ).also { it.sessionToken = sessionToken }
        return adoptClient(networkClient)
    }

    fun adoptClient(client: NetworkAccessClient): SessionGraph {
        // Don't close the incoming client — it becomes the session client.
        val existing = _session.value
        if (existing != null && existing.networkAccessClient !== client) {
            runCatching { existing.networkAccessClient.close() }
        }
        val graph = sessionGraphFactory.create(client)
        _session.value = graph
        return graph
    }

    /**
     * Rebuilds a session from the selected host's stored token.
     * @return true if a session is active afterwards.
     */
    fun restoreFromStoredSession(): Boolean {
        val host = hostRepository.selectedHost ?: return false
        val token = hostRepository.networkAccessSession(host.id) ?: return false
        return runCatching {
            openSession(host.resolvedNetworkAccessBaseUrl(), token)
            true
        }.getOrDefault(false)
    }

    /** @return true if a session was closed. */
    fun clearSession(): Boolean {
        val existing = _session.value ?: return false
        runCatching { existing.networkAccessClient.close() }
        _session.value = null
        return true
    }
}
