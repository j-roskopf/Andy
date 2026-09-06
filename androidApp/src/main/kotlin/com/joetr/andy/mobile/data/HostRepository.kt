package com.joetr.andy.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.joetr.andy.mobile.data.vnc.VncStreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists non-secret host metadata in encrypted prefs and keeps Network Access /
 * VNC secrets in separate encrypted keys — never logged.
 */
class HostRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "andy_mobile_hosts",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _hosts = MutableStateFlow(loadHosts())
    val hosts: StateFlow<List<SavedHost>> = _hosts.asStateFlow()

    private val _selectedHostId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedHostId: StateFlow<String?> = _selectedHostId.asStateFlow()

    val selectedHost: SavedHost?
        get() {
            val id = _selectedHostId.value
            return _hosts.value.firstOrNull { it.id == id } ?: _hosts.value.firstOrNull()
        }

    fun selectHost(id: String?) {
        _selectedHostId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    suspend fun upsert(host: SavedHost) = withContext(Dispatchers.IO) {
        _hosts.update { current ->
            val without = current.filterNot { it.id == host.id }
            (without + host).sortedBy { it.displayName.lowercase() }.also { persistHosts(it) }
        }
        if (_selectedHostId.value == null) selectHost(host.id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        clearSecrets(id)
        _hosts.update { current ->
            current.filterNot { it.id == id }.also { persistHosts(it) }
        }
        if (_selectedHostId.value == id) {
            selectHost(_hosts.value.firstOrNull()?.id)
        }
    }

    fun networkAccessToken(hostId: String): String? =
        prefs.getString(tokenKey(hostId), null)?.takeIf { it.isNotBlank() }

    fun vncPassword(hostId: String): String? =
        prefs.getString(vncKey(hostId), null)?.takeIf { it.isNotBlank() }

    fun vncStreamQuality(): VncStreamQuality =
        VncStreamQuality.fromId(prefs.getString(KEY_VNC_QUALITY, null))

    fun saveVncStreamQuality(quality: VncStreamQuality) {
        prefs.edit().putString(KEY_VNC_QUALITY, quality.name).apply()
    }

    fun vncKeyboardBufferMode(): Boolean =
        prefs.getBoolean(KEY_VNC_BUFFER_MODE, false)

    fun saveVncKeyboardBufferMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VNC_BUFFER_MODE, enabled).apply()
    }

    fun saveNetworkAccessToken(hostId: String, token: String?) {
        prefs.edit().apply {
            val value = token?.trim().orEmpty()
            if (value.isEmpty()) remove(tokenKey(hostId)) else putString(tokenKey(hostId), value)
        }.apply()
    }

    fun saveVncPassword(hostId: String, password: String?) {
        prefs.edit().apply {
            val value = password.orEmpty()
            if (value.isEmpty()) remove(vncKey(hostId)) else putString(vncKey(hostId), value)
        }.apply()
    }

    fun clearSecrets(hostId: String) {
        prefs.edit()
            .remove(tokenKey(hostId))
            .remove(vncKey(hostId))
            .apply()
    }

    private fun loadHosts(): List<SavedHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedHost.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun persistHosts(hosts: List<SavedHost>) {
        prefs.edit()
            .putString(KEY_HOSTS, json.encodeToString(ListSerializer(SavedHost.serializer()), hosts))
            .apply()
    }

    private fun tokenKey(hostId: String) = "na_token_$hostId"
    private fun vncKey(hostId: String) = "vnc_pass_$hostId"

    companion object {
        private const val KEY_HOSTS = "hosts_json"
        private const val KEY_SELECTED = "selected_host_id"
        private const val KEY_VNC_QUALITY = "vnc_stream_quality"
        private const val KEY_VNC_BUFFER_MODE = "vnc_keyboard_buffer_mode"
    }
}
