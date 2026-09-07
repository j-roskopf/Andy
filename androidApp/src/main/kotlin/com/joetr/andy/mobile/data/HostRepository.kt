package com.joetr.andy.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.joetr.andy.mobile.data.secrets.KeystoreSecretStore
import com.joetr.andy.mobile.data.vnc.VncStreamQuality
import com.joetr.andy.mobile.navigation.MobileTab
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.joetr.andy.mobile.di.MobileScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.hostMetaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "andy_mobile_host_meta",
)

/**
 * Host metadata in DataStore; VNC / Network Access secrets in [KeystoreSecretStore].
 * Migrates once from legacy EncryptedSharedPreferences (`andy_mobile_hosts`).
 */
@SingleIn(MobileScope::class)
@Inject
class HostRepository(
    context: Context,
    private val secrets: KeystoreSecretStore,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val meta = appContext.hostMetaDataStore

    private val _hosts = MutableStateFlow<List<SavedHost>>(emptyList())
    val hosts: StateFlow<List<SavedHost>> = _hosts.asStateFlow()

    private val _selectedHostId = MutableStateFlow<String?>(null)
    val selectedHostId: StateFlow<String?> = _selectedHostId.asStateFlow()

    private val _selectedTab = MutableStateFlow(MobileTab.Hosts)
    val selectedTab: StateFlow<MobileTab> = _selectedTab.asStateFlow()

    val selectedHost: SavedHost?
        get() {
            val id = _selectedHostId.value
            return _hosts.value.firstOrNull { it.id == id } ?: _hosts.value.firstOrNull()
        }

    init {
        // DataStore must not be touched via runBlocking on Main — it deadlocks.
        runBlocking(Dispatchers.IO) {
            migrateFromEncryptedPrefsIfNeeded()
            _hosts.value = loadHosts()
            _selectedHostId.value = meta.data.first()[KEY_SELECTED]
            _selectedTab.value = MobileTab.entries
                .firstOrNull { it.name == meta.data.first()[KEY_SELECTED_TAB] }
                ?: MobileTab.Hosts
        }
    }

    fun selectHost(id: String?) {
        _selectedHostId.value = id
        runBlocking(Dispatchers.IO) {
            meta.edit { prefs ->
                if (id == null) prefs.remove(KEY_SELECTED) else prefs[KEY_SELECTED] = id
            }
        }
    }

    fun selectTab(tab: MobileTab) {
        _selectedTab.value = tab
        runBlocking(Dispatchers.IO) {
            meta.edit { prefs -> prefs[KEY_SELECTED_TAB] = tab.name }
        }
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

    fun networkAccessSession(hostId: String): String? =
        secrets.get(sessionKey(hostId))?.takeIf { it.isNotBlank() }

    /** Legacy master token storage — migrate once via login, then clear. */
    fun legacyNetworkAccessToken(hostId: String): String? =
        secrets.get(legacyTokenKey(hostId))?.takeIf { it.isNotBlank() }

    fun vncPassword(hostId: String): String? =
        secrets.get(vncKey(hostId))?.takeIf { it.isNotBlank() }

    fun vncStreamQuality(): VncStreamQuality =
        VncStreamQuality.fromId(runBlocking(Dispatchers.IO) { meta.data.first()[KEY_VNC_QUALITY] })

    fun saveVncStreamQuality(quality: VncStreamQuality) {
        runBlocking(Dispatchers.IO) {
            meta.edit { it[KEY_VNC_QUALITY] = quality.name }
        }
    }

    fun vncKeyboardBufferMode(): Boolean =
        runBlocking(Dispatchers.IO) { meta.data.first()[KEY_VNC_BUFFER_MODE] ?: false }

    fun saveVncKeyboardBufferMode(enabled: Boolean) {
        runBlocking(Dispatchers.IO) {
            meta.edit { it[KEY_VNC_BUFFER_MODE] = enabled }
        }
    }

    fun saveNetworkAccessSession(hostId: String, sessionToken: String?) {
        secrets.put(sessionKey(hostId), sessionToken)
        // Always drop legacy master-token storage once we have a session path.
        secrets.remove(legacyTokenKey(hostId))
    }

    fun clearLegacyNetworkAccessToken(hostId: String) {
        secrets.remove(legacyTokenKey(hostId))
    }

    fun saveVncPassword(hostId: String, password: String?) {
        secrets.put(vncKey(hostId), password)
    }

    fun clearSecrets(hostId: String) {
        secrets.clearKeys(
            listOf(sessionKey(hostId), legacyTokenKey(hostId), vncKey(hostId)),
        )
    }

    private suspend fun loadHosts(): List<SavedHost> {
        val raw = meta.data.first()[KEY_HOSTS] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedHost.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun persistHosts(hosts: List<SavedHost>) {
        meta.edit {
            it[KEY_HOSTS] = json.encodeToString(ListSerializer(SavedHost.serializer()), hosts)
        }
    }

    private suspend fun migrateFromEncryptedPrefsIfNeeded() {
        val already = meta.data.first()[KEY_MIGRATED] == true
        if (already) return

        val legacy = runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                LEGACY_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()

        if (legacy != null) {
            meta.edit { prefs ->
                legacy.getString(LEGACY_HOSTS, null)?.let { prefs[KEY_HOSTS] = it }
                legacy.getString(LEGACY_SELECTED, null)?.let { prefs[KEY_SELECTED] = it }
                legacy.getString(LEGACY_VNC_QUALITY, null)?.let { prefs[KEY_VNC_QUALITY] = it }
                if (legacy.contains(LEGACY_VNC_BUFFER)) {
                    prefs[KEY_VNC_BUFFER_MODE] = legacy.getBoolean(LEGACY_VNC_BUFFER, false)
                }
            }

            val hostsJson = legacy.getString(LEGACY_HOSTS, null)
            val hostIds = if (hostsJson != null) {
                runCatching {
                    json.decodeFromString(ListSerializer(SavedHost.serializer()), hostsJson)
                        .map { it.id }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            hostIds.forEach { id ->
                legacy.getString(sessionKey(id), null)?.let { secrets.put(sessionKey(id), it) }
                legacy.getString(legacyTokenKey(id), null)?.let { secrets.put(legacyTokenKey(id), it) }
                legacy.getString(vncKey(id), null)?.let { secrets.put(vncKey(id), it) }
            }

            // Also migrate any leftover secret keys by scanning known prefixes.
            legacy.all.keys.forEach { key ->
                when {
                    key.startsWith("na_session_") ||
                        key.startsWith("na_token_") ||
                        key.startsWith("vnc_pass_") -> {
                        legacy.getString(key, null)?.let { secrets.put(key, it) }
                    }
                }
            }

            legacy.edit().clear().apply()
        }

        meta.edit { it[KEY_MIGRATED] = true }
    }

    private fun sessionKey(hostId: String) = "na_session_$hostId"
    private fun legacyTokenKey(hostId: String) = "na_token_$hostId"
    private fun vncKey(hostId: String) = "vnc_pass_$hostId"

    companion object {
        private const val LEGACY_PREFS = "andy_mobile_hosts"
        private const val LEGACY_HOSTS = "hosts_json"
        private const val LEGACY_SELECTED = "selected_host_id"
        private const val LEGACY_VNC_QUALITY = "vnc_stream_quality"
        private const val LEGACY_VNC_BUFFER = "vnc_keyboard_buffer_mode"

        private val KEY_HOSTS = stringPreferencesKey("hosts_json")
        private val KEY_SELECTED = stringPreferencesKey("selected_host_id")
        private val KEY_SELECTED_TAB = stringPreferencesKey("selected_tab")
        private val KEY_VNC_QUALITY = stringPreferencesKey("vnc_stream_quality")
        private val KEY_VNC_BUFFER_MODE = booleanPreferencesKey("vnc_keyboard_buffer_mode")
        private val KEY_MIGRATED = booleanPreferencesKey("migrated_from_encrypted_prefs")
    }
}
