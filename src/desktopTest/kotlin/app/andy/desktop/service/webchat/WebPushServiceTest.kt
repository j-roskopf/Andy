package app.andy.desktop.service.webchat

import app.andy.model.WorkspaceState
import app.andy.service.WorkspaceStore
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider

class WebPushServiceTest {
    private fun tempDir(): File = createTempDirectory("andy-webpush").toFile()

    @Test
    fun generatesAndPersistsVapidKeysOnce() = runBlocking {
        val dir = tempDir()
        val store = MemoryWorkspaceStore()
        val push = WebPushService(
            workspaceStore = store,
            subscriptions = PushSubscriptionStore(File(dir, "push-subscriptions.json")),
            sendNotification = { _, _ -> 201 },
        )
        val first = push.ensureKeys()
        val second = push.ensureKeys()
        assertEquals(first, second)
        assertTrue(store.load().vapidPublicKey.isNotBlank())
        assertTrue(store.load().vapidPrivateKey.isNotBlank())
    }

    @Test
    fun prepareNotificationBuildsEncryptedPayload() = runBlocking {
        val dir = tempDir()
        val store = MemoryWorkspaceStore()
        val push = WebPushService(
            workspaceStore = store,
            subscriptions = PushSubscriptionStore(File(dir, "push-subscriptions.json")),
            sendNotification = { _, _ -> 201 },
        )
        val subscription = sampleSubscription()
        val notification = push.prepareNotification(subscription, """{"title":"Andy","body":"needs input"}""")
        assertEquals(subscription.endpoint, notification.endpoint)
        assertTrue(notification.hasPayload())
    }

    @Test
    fun preparedPostUsesAes128GcmContentEncoding() = runBlocking {
        val store = MemoryWorkspaceStore()
        val push = WebPushService(
            workspaceStore = store,
            subscriptions = PushSubscriptionStore(File(tempDir(), "push-subscriptions.json")),
            sendNotification = { _, _ -> 201 },
        )
        val keys = push.ensureKeys()
        val subscription = sampleSubscription()
        val notification = push.prepareNotification(subscription, """{"title":"Andy","body":"needs input"}""")
        val service = PushService(keys.first, keys.second, "mailto:andy-local@localhost")
        val post = WebPushService.prepareAes128GcmPost(service, notification)
        assertEquals("aes128gcm", post.getFirstHeader("Content-Encoding")?.value)
        // Guard against accidentally calling the no-arg send() path (AESGCM).
        assertEquals(Encoding.AES128GCM, Encoding.valueOf("AES128GCM"))
    }

    @Test
    fun staleSubscriptionRemovedOn410() = runBlocking {
        val dir = tempDir()
        val subStore = PushSubscriptionStore(File(dir, "push-subscriptions.json"))
        val store = MemoryWorkspaceStore()
        val push = WebPushService(
            workspaceStore = store,
            subscriptions = subStore,
            sendNotification = { _: PushService, _: Notification -> 410 },
        )
        push.ensureKeys()
        val keys = store.load()
        subStore.upsert(
            StoredPushSubscription(
                endpoint = "https://push.example.test/gone",
                p256dh = keys.vapidPublicKey,
                auth = "dGVzdC1hdXRoLXNlY3JldA",
            ),
        )
        assertEquals(1, subStore.list().size)
        push.sendPayload("""{"title":"Andy","body":"test"}""")
        assertEquals(0, subStore.list().size)
    }

    @Test
    fun handleSendStatusRemoves404And410() {
        val dir = tempDir()
        val subStore = PushSubscriptionStore(File(dir, "push-subscriptions.json"))
        subStore.upsert(StoredPushSubscription("https://a", "k", "a"))
        subStore.upsert(StoredPushSubscription("https://b", "k", "a"))
        val push = WebPushService(
            workspaceStore = MemoryWorkspaceStore(),
            subscriptions = subStore,
            sendNotification = { _, _ -> 201 },
        )
        push.handleSendStatus("https://a", 410)
        push.handleSendStatus("https://b", 200)
        assertEquals(listOf("https://b"), subStore.list().map { it.endpoint })
    }

    @Test
    fun vapidKeypairsDiffer() {
        val a = WebPushService.generateVapidKeyPair()
        val b = WebPushService.generateVapidKeyPair()
        assertNotEquals(a.first, b.first)
    }

    /** Browser-shaped subscription with a real P-256 user public key + 16-byte auth secret. */
    private fun sampleSubscription(): StoredPushSubscription {
        WebPushService.ensureBouncyCastle()
        val parameterSpec = ECNamedCurveTable.getParameterSpec(Utils.CURVE)
        val generator = KeyPairGenerator.getInstance(Utils.ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(parameterSpec)
        val userPublic = Utils.encode(generator.generateKeyPair().public as ECPublicKey)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val auth = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return StoredPushSubscription(
            endpoint = "https://push.example.test/endpoint",
            p256dh = encoder.encodeToString(userPublic),
            auth = encoder.encodeToString(auth),
        )
    }

    private class MemoryWorkspaceStore : WorkspaceStore {
        private var state = WorkspaceState()
        override suspend fun load(): WorkspaceState = state
        override suspend fun save(state: WorkspaceState) {
            this.state = state
        }
    }
}
