package app.andy.desktop.service.webchat

import app.andy.model.WorkspaceState
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import app.andy.service.WorkspaceStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import org.apache.http.client.methods.HttpPost
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Self-hosted Web Push: generates a VAPID keypair once, stores subscriptions, and
 * forwards [AttentionHub] events (generic lock-screen bodies — no chat prompt text).
 */
class WebPushService(
    private val workspaceStore: WorkspaceStore,
    private val subscriptions: PushSubscriptionStore = PushSubscriptionStore(),
    private val sendNotification: (PushService, Notification) -> Int = ::defaultSend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var watching = false

    init {
        ensureBouncyCastle()
    }

    suspend fun publicVapidKey(): String {
        ensureKeys()
        return workspaceStore.load().vapidPublicKey
    }

    suspend fun subscribe(endpoint: String, p256dh: String, auth: String, ownerFingerprint: String? = null) {
        require(endpoint.isNotBlank()) { "endpoint required" }
        require(p256dh.isNotBlank()) { "p256dh required" }
        require(auth.isNotBlank()) { "auth required" }
        require(!ownerFingerprint.isNullOrBlank()) { "authenticated session required for push" }
        ensureKeys()
        subscriptions.upsert(
            StoredPushSubscription(
                endpoint = endpoint,
                p256dh = p256dh,
                auth = auth,
                ownerFingerprint = ownerFingerprint,
            ),
        )
    }

    fun unsubscribe(endpoint: String) {
        if (endpoint.isNotBlank()) subscriptions.remove(endpoint)
    }

    fun listSubscriptions(): List<StoredPushSubscription> = subscriptions.list()

    /** Forward hub attention events to browser push subscribers. */
    fun startWatching(hub: AttentionHub) {
        if (watching) return
        watching = true
        scope.launch {
            hub.events.collect { event ->
                // Never block the SharedFlow collector on network I/O — that stalls every
                // subscriber (including Android /ws/attention) when the buffer fills.
                launch {
                    runCatching { sendAttention(event) }
                }
            }
        }
    }

    suspend fun sendAttention(event: AgentAttentionEvent) {
        val body = when (event.kind) {
            AgentAttentionKind.Blocked -> "Andy needs your input."
            AgentAttentionKind.Done -> "Agent completed."
            AgentAttentionKind.Error -> "Agent failed."
        }
        val payload = buildJsonObject {
            put("title", "Andy")
            put("body", body)
            put("taskId", event.taskId)
            put("kind", event.kind.name)
            put("url", "/#/chat/${event.taskId}")
        }.toString()
        sendPayload(payload)
    }

    /** @deprecated Prefer [sendAttention]; kept for tests that assert needs-input payload shape. */
    suspend fun sendNeedsInput(taskId: String, @Suppress("UNUSED_PARAMETER") title: String) {
        sendAttention(
            AgentAttentionEvent(
                taskId = taskId,
                projectId = null,
                title = title,
                kind = AgentAttentionKind.Blocked,
            ),
        )
    }

    /**
     * Encrypt+sign a payload for [subscription] without hitting the network.
     * Used by unit tests to verify VAPID construction.
     */
    suspend fun prepareNotification(
        subscription: StoredPushSubscription,
        payload: String,
    ): Notification {
        ensureKeys()
        val state = workspaceStore.load()
        return Notification(subscription.endpoint, subscription.p256dh, subscription.auth, payload)
            .also {
                // Touch keys so invalid key material fails early in tests.
                PushService(state.vapidPublicKey, state.vapidPrivateKey, VapidSubject)
            }
    }

    suspend fun sendPayload(payload: String) {
        ensureKeys()
        val state = workspaceStore.load()
        if (state.vapidPublicKey.isBlank() || state.vapidPrivateKey.isBlank()) return
        val push = PushService(state.vapidPublicKey, state.vapidPrivateKey, VapidSubject)
        for (sub in subscriptions.list()) {
            val status = runCatching {
                val notification = Notification(sub.endpoint, sub.p256dh, sub.auth, payload)
                sendNotification(push, notification)
            }.getOrElse { -1 }
            if (status == 404 || status == 410) {
                subscriptions.remove(sub.endpoint)
            }
        }
    }

    /** Remove stale subscription when the push service returns 404/410. */
    fun handleSendStatus(endpoint: String, status: Int) {
        if (status == 404 || status == 410) {
            subscriptions.remove(endpoint)
        }
    }

    suspend fun ensureKeys(): Pair<String, String> = mutex.withLock {
        val state = workspaceStore.load()
        if (state.vapidPublicKey.isNotBlank() && state.vapidPrivateKey.isNotBlank()) {
            return state.vapidPublicKey to state.vapidPrivateKey
        }
        val generated = generateVapidKeyPair()
        workspaceStore.save(
            state.copy(
                vapidPublicKey = generated.first,
                vapidPrivateKey = generated.second,
            ),
        )
        generated
    }

    companion object {
        private const val VapidSubject = "mailto:andy-local@localhost"

        fun ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        fun generateVapidKeyPair(): Pair<String, String> {
            ensureBouncyCastle()
            val parameterSpec = ECNamedCurveTable.getParameterSpec(Utils.CURVE)
            val generator = KeyPairGenerator.getInstance(Utils.ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
            generator.initialize(parameterSpec)
            val keyPair: KeyPair = generator.generateKeyPair()
            val publicKey = Utils.encode(keyPair.public as ECPublicKey)
            val privateKey = Utils.encode(keyPair.private as ECPrivateKey)
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return encoder.encodeToString(publicKey) to encoder.encodeToString(privateKey)
        }

        /**
         * Plan requires aes128gcm (RFC 8291). The no-arg [PushService.send] overload
         * defaults to legacy AESGCM — always pass [Encoding.AES128GCM] explicitly.
         */
        private fun defaultSend(push: PushService, notification: Notification): Int {
            val response = push.send(notification, Encoding.AES128GCM)
            return response.statusLine.statusCode
        }

        /** Test helper: build the outbound POST so encoding headers can be asserted. */
        internal fun prepareAes128GcmPost(push: PushService, notification: Notification): HttpPost =
            push.preparePost(notification, Encoding.AES128GCM)
    }
}
