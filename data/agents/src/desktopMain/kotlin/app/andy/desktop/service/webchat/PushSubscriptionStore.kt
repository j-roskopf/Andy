package app.andy.desktop.service.webchat

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredPushSubscription(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    /** SHA-256 fingerprint of the credential that registered this subscription. */
    val ownerFingerprint: String = "",
)

/**
 * Persists browser PushSubscription records under `~/.andy/push-subscriptions.json`.
 * Subscriptions are bound to the auth fingerprint that created them.
 */
class PushSubscriptionStore(
    private val file: File = File(System.getProperty("user.home"), ".andy/push-subscriptions.json"),
    private val maxPerOwner: Int = 10,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<StoredPushSubscription> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredPushSubscription.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    fun listForOwner(ownerFingerprint: String?): List<StoredPushSubscription> {
        if (ownerFingerprint.isNullOrBlank()) return emptyList()
        return list().filter { it.ownerFingerprint == ownerFingerprint }
    }

    fun upsert(subscription: StoredPushSubscription) = synchronized(lock) {
        val owner = subscription.ownerFingerprint
        val sameOwner = list().filter { it.ownerFingerprint == owner && it.endpoint != subscription.endpoint }
        if (owner.isNotBlank() && sameOwner.size >= maxPerOwner) {
            throw IllegalArgumentException("push subscription limit reached for this device")
        }
        val next = list().filterNot { it.endpoint == subscription.endpoint } + subscription
        writeAll(next)
    }

    fun remove(endpoint: String) = synchronized(lock) {
        writeAll(list().filterNot { it.endpoint == endpoint })
    }

    fun removeAll() = synchronized(lock) { writeAll(emptyList()) }

    private fun writeAll(items: List<StoredPushSubscription>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ListSerializer(StoredPushSubscription.serializer()), items))
        restrictPushStorePermissions(file)
    }

    private fun restrictPushStorePermissions(file: File) {
        if (!file.exists()) return
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
