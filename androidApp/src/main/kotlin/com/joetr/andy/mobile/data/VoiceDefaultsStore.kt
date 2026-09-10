package com.joetr.andy.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.joetr.andy.mobile.di.MobileScope

private val Context.voiceDefaultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "andy_mobile_voice_defaults",
)

/**
 * Phone-local defaults for voice-started threads. Independent of desktop WorkspaceState —
 * nothing syncs over the network.
 */
@SingleIn(MobileScope::class)
@Inject
class VoiceDefaultsStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.voiceDefaultsDataStore

    private val _defaults = MutableStateFlow(VoiceDefaults())
    val defaults: StateFlow<VoiceDefaults> = _defaults.asStateFlow()

    init {
        runBlocking(Dispatchers.IO) {
            _defaults.value = load()
        }
    }

    suspend fun update(transform: (VoiceDefaults) -> VoiceDefaults) = withContext(Dispatchers.IO) {
        _defaults.update { current ->
            transform(current).also { persist(it) }
        }
    }

    private suspend fun load(): VoiceDefaults {
        val prefs = store.data.first()
        return VoiceDefaults(
            agent = prefs[KEY_AGENT],
            model = prefs[KEY_MODEL],
            autonomy = prefs[KEY_AUTONOMY] ?: "Standard",
            projectId = prefs[KEY_PROJECT],
        )
    }

    private suspend fun persist(defaults: VoiceDefaults) {
        store.edit { prefs ->
            if (defaults.agent == null) prefs.remove(KEY_AGENT) else prefs[KEY_AGENT] = defaults.agent
            if (defaults.model == null) prefs.remove(KEY_MODEL) else prefs[KEY_MODEL] = defaults.model
            prefs[KEY_AUTONOMY] = defaults.autonomy
            if (defaults.projectId == null) prefs.remove(KEY_PROJECT) else prefs[KEY_PROJECT] = defaults.projectId
        }
    }

    companion object {
        private val KEY_AGENT = stringPreferencesKey("voice_default_agent")
        private val KEY_MODEL = stringPreferencesKey("voice_default_model")
        private val KEY_AUTONOMY = stringPreferencesKey("voice_default_autonomy")
        private val KEY_PROJECT = stringPreferencesKey("voice_default_project")
    }
}

data class VoiceDefaults(
    val agent: String? = null,
    val model: String? = null,
    val autonomy: String = "Standard",
    val projectId: String? = null,
)
