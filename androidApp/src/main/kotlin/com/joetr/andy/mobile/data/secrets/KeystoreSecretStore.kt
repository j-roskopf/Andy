package com.joetr.andy.mobile.data.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.joetr.andy.mobile.di.MobileScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "andy_mobile_secrets",
)

/**
 * Small Keystore-backed string store. Ciphertext lives in DataStore; the AES key never leaves
 * AndroidKeyStore.
 *
 * Sync helpers always hop to [Dispatchers.IO] — DataStore + runBlocking on Main deadlocks.
 */
@SingleIn(MobileScope::class)
@Inject
class KeystoreSecretStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.secretDataStore

    fun get(key: String): String? {
        val packed = runBlocking(Dispatchers.IO) {
            dataStore.data.first()[stringPreferencesKey(key)]
        } ?: return null
        return runCatching { decrypt(packed) }.getOrNull()
    }

    fun put(key: String, value: String?) {
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val prefKey = stringPreferencesKey(key)
                if (value.isNullOrEmpty()) {
                    prefs.remove(prefKey)
                } else {
                    prefs[prefKey] = encrypt(value)
                }
            }
        }
    }

    fun remove(key: String) = put(key, null)

    fun clearKeys(keys: Iterable<String>) {
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                keys.forEach { prefs.remove(stringPreferencesKey(it)) }
            }
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(packed: String): String {
        val parts = packed.split(':', limit = 2)
        require(parts.size == 2) { "Corrupt secret payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "andy_mobile_secret_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
