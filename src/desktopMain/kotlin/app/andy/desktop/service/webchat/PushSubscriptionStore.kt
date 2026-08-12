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
)

/**
 * Persists browser PushSubscription records under `~/.andy/push-subscriptions.json`.
 */
class PushSubscriptionStore(
    private val file: File = File(System.getProperty("user.home"), ".andy/push-subscriptions.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<StoredPushSubscription> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredPushSubscription.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    fun upsert(subscription: StoredPushSubscription) = synchronized(lock) {
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
    }
}
