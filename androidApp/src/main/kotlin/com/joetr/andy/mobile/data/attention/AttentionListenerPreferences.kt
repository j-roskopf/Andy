package com.joetr.andy.mobile.data.attention

import android.content.Context
import androidx.core.content.edit

/**
 * Local (non-secret) preferences for the attention listener.
 *
 * Deliberately plain [android.content.SharedPreferences] rather than the encrypted
 * [com.joetr.andy.mobile.data.secrets.KeystoreSecretStore] used for session tokens — nothing here
 * is sensitive, and the service reads it on every poll.
 */
class AttentionListenerPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Keep [AttentionPushService] alive for the whole signed-in session instead of shutting down
     * once no chat is Working.
     *
     * Off by default: a foreground service must post an ongoing notification for as long as it
     * runs, so staying up means the "Listening for Andy chats" row sits in the shade permanently.
     * Turning this on trades that back for catching runs started from Andy Desktop while the phone
     * app is closed.
     */
    var alwaysListen: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_LISTEN, false)
        set(value) = prefs.edit { putBoolean(KEY_ALWAYS_LISTEN, value) }

    companion object {
        private const val PREFS = "andy_attention_listener"
        private const val KEY_ALWAYS_LISTEN = "always_listen"
    }
}
