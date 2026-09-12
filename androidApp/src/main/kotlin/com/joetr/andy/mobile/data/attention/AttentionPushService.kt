package com.joetr.andy.mobile.data.attention

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.joetr.andy.MainActivity
import com.joetr.andy.R
import com.joetr.andy.mobile.AndyApplication
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessException
import com.joetr.andy.mobile.data.secrets.KeystoreSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps Network Access attention delivery alive while signed in:
 * 1. Push: `/ws/attention` (host → phone)
 * 2. Pull backup: poll `/api/chats` through [ChatAttentionTracker]
 */
class AttentionPushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: NetworkAccessClient? = null
    private var listenJob: Job? = null
    private var connectedBaseUrl: String? = null
    private var connectedToken: String? = null
    private lateinit var notifications: AndroidChatNotificationService
    private val tracker = ChatAttentionTracker()
    private val notifyMutex = Mutex()
    private val recentKeys = mutableMapOf<String, Long>()
    private val pushStatus = AtomicReference("push connecting…")
    private val pullStatus = AtomicReference("pull starting…")
    private var hostLabel: String = "Andy host"
    private lateinit var listenerPrefs: AttentionListenerPreferences

    /** Last poll that saw a Working chat; drives the idle shutdown in [stopIfIdle]. */
    @Volatile
    private var lastActiveAtMillis = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = AndroidChatNotificationService(this)
        notifications.ensureChannel()
        ensureListeningChannel()
        listenerPrefs = AttentionListenerPreferences(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                clearSession(this)
                connectedBaseUrl = null
                connectedToken = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val hostName = intent.getStringExtra(EXTRA_HOST_NAME).orEmpty().ifBlank { "Andy host" }
                if (baseUrl.isBlank() || token.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                hostLabel = hostName
                startAttentionSession(
                    scope = scope,
                    persist = { persistSession(this, baseUrl, token, hostName) },
                    startForeground = { startAsForeground(combinedStatus()) },
                    connect = { connect(baseUrl, token) },
                )
                return START_STICKY
            }
            else -> {
                val session = loadSession(this) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                hostLabel = session.hostName
                startAsForeground(combinedStatus())
                connect(session.baseUrl, session.token)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        listenJob?.cancel()
        client?.close()
        client = null
        connectedBaseUrl = null
        connectedToken = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        // Android 15+ dataSync foreground services are capped at a 6h/24h budget. Stop
        // cleanly instead of letting the system kill us with a RemoteServiceException so
        // no spurious crash is reported. The user can reopen Projects to start listening
        // again (or we resume via onStartCommand when restarted).
        Log.w(TAG, "dataSync foreground service timed out (6h budget exhausted); stopping listener")
        stopSelf(startId)
    }

    private fun connect(baseUrl: String, token: String) {
        // ProjectsScreen remounts (tab switch, activity recreate) call start() again.
        // Reconnecting resets the pull tracker and can re-alert the same completion.
        if (listenJob?.isActive == true &&
            connectedBaseUrl == baseUrl &&
            connectedToken == token
        ) {
            startAsForeground(combinedStatus())
            return
        }
        listenJob?.cancel()
        client?.close()
        val sameHost = connectedBaseUrl == baseUrl
        if (!sameHost) {
            tracker.reset()
            recentKeys.clear()
        }
        connectedBaseUrl = baseUrl
        connectedToken = token
        val next = NetworkAccessClient(
            baseUrl = baseUrl,
            okHttpClient = (application as? com.joetr.andy.mobile.AndyApplication)
                ?.graph?.longLivedOkHttp
                ?: okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            longLived = true,
        ).also { it.sessionToken = token }
        client = next
        listenJob = scope.launch {
            launch { pushLoop(next) }
            launch { pullLoop(next) }
        }
    }

    private fun isUnauthorized(e: Throwable): Boolean =
        (e as? NetworkAccessException)?.unauthorized == true

    private fun isAuthRejection(e: Throwable): Boolean {
        if (isUnauthorized(e)) return true
        val msg = e.message.orEmpty()
        return msg.contains("too many failed auth", ignoreCase = true)
    }

    /**
     * Expired / wiped host sessions must not keep polling — each 401 counts toward the
     * host IP auth rate limit and can lock the phone out of password login for minutes.
     */
    private fun stopForExpiredSession(source: String) {
        Log.w(TAG, "$source session expired — stopping listener")
        pushStatus.set("session expired · sign in again")
        pullStatus.set("session expired · sign in again")
        publishStatus()
        clearSession(this)
        listenJob?.cancel()
        listenJob = null
        client?.close()
        client = null
        connectedBaseUrl = null
        connectedToken = null
        stopSelf()
    }

    private suspend fun pushLoop(client: NetworkAccessClient) {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                pushStatus.set("push connecting…")
                publishStatus()
                client.observeAttention(
                    onReady = {
                        pushStatus.set("push connected")
                        publishStatus()
                        backoffMs = 1_000L
                    },
                ).collect { event ->
                    backoffMs = 1_000L
                    deliver(event)
                }
                pushStatus.set("push closed · retrying")
                publishStatus()
            } catch (e: Exception) {
                Log.w(TAG, "attention push failed: ${e.message}", e)
                if (isAuthRejection(e)) {
                    stopForExpiredSession("push")
                    return
                }
                val short = e.message?.take(48)?.replace('\n', ' ').orEmpty()
                pushStatus.set(
                    if (short.isBlank()) "push down · retrying"
                    else "push down · $short",
                )
                publishStatus()
            }
            if (!currentCoroutineContext().isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun pullLoop(client: NetworkAccessClient) {
        while (currentCoroutineContext().isActive) {
            val result = runCatching {
                val chats = client.listChats()
                for (event in tracker.onChatsChanged(chats)) {
                    deliver(event)
                }
                if (chats.any(ChatAttentionTracker::isWorking)) {
                    lastActiveAtMillis = System.currentTimeMillis()
                }
                chats.size
            }
            result.onSuccess {
                if (canPostNotifications()) {
                    pullStatus.set("pull ok")
                } else {
                    pullStatus.set("enable app notifications")
                }
                publishStatus()
            }.onFailure { e ->
                Log.w(TAG, "attention pull failed: ${e.message}")
                if (isAuthRejection(e)) {
                    stopForExpiredSession("pull")
                    return
                }
                pullStatus.set("pull down")
                publishStatus()
            }
            if (stopIfIdle()) return
            delay(PULL_INTERVAL_MS)
        }
    }

    /**
     * Shuts the listener down once nothing is Working.
     *
     * A foreground service must post an ongoing notification for as long as it runs (API 26+), so
     * the only way to keep the shade clean is to not be running. Alerts only matter while an agent
     * is mid-turn, so we idle out shortly after the last one finishes and the app starts us again
     * the next time it hands the host work.
     *
     * @return true when the service is stopping and the caller should unwind.
     */
    private fun stopIfIdle(): Boolean {
        if (listenerPrefs.alwaysListen) return false
        val idleForMs = System.currentTimeMillis() - lastActiveAtMillis
        if (idleForMs < IDLE_STOP_AFTER_MS) return false
        Log.i(TAG, "no chat working for ${idleForMs}ms — stopping listener")
        stopSelf()
        return true
    }

    private fun combinedStatus(): String {
        val pull = pullStatus.get()
        val push = pushStatus.get()
        return when {
            pull.contains("enable app notifications") ->
                "$hostLabel · enable app notifications"
            push.startsWith("push connected") && pull.startsWith("pull ok") ->
                "$hostLabel · listening"
            // Pull alone is enough for alerts — don't alarm on push TLS issues.
            pull.startsWith("pull ok") ->
                "$hostLabel · listening (pull)"
            push.startsWith("push connected") ->
                "$hostLabel · listening (push)"
            else ->
                "$hostLabel · $push · $pull"
        }
    }

    private fun publishStatus() {
        NotificationManagerCompat.from(this)
            .notify(LISTENING_NOTIFICATION_ID, listeningNotification(combinedStatus()))
    }

    private suspend fun deliver(event: ChatAttentionEvent) {
        if (event.chatId == viewingChatId) return
        val key = "${event.chatId}:${event.kind.name}"
        val now = System.currentTimeMillis()
        // Terminal kinds stay suppressed much longer — host push can re-emit Done when
        // scrape confidence latches, and pull can see finishedAt then status separately.
        val windowMs = when (event.kind) {
            ChatAttentionKind.Done, ChatAttentionKind.Error -> TERMINAL_DEDUPE_MS
            ChatAttentionKind.Blocked -> DEDUPE_MS
        }
        notifyMutex.withLock {
            val last = recentKeys[key]
            if (last != null && now - last < windowMs) return
            recentKeys[key] = now
            if (recentKeys.size > 200) {
                recentKeys.entries.removeIf { (k, at) ->
                    val ttl = if (k.endsWith(":Blocked")) DEDUPE_MS else TERMINAL_DEDUPE_MS
                    now - at > ttl
                }
            }
        }
        if (!canPostNotifications()) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — skipping ${event.kind} for ${event.chatId}")
            pullStatus.set("enable app notifications")
            publishStatus()
            return
        }
        notifications.show(event)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun startAsForeground(status: String) {
        // Every start (sign-in, ensureRunning, system restart) buys a fresh idle grace window so
        // we never shut down before the first poll has had a chance to see the work.
        lastActiveAtMillis = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                LISTENING_NOTIFICATION_ID,
                listeningNotification(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(LISTENING_NOTIFICATION_ID, listeningNotification(status))
        }
    }

    private fun listeningNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AttentionPushService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LISTENING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for Andy chats")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureListeningChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // MIN keeps the ongoing notification out of the status bar — it only appears once the
        // shade is pulled down. Importance is fixed when a channel is created (the system ignores
        // it on re-create so users keep control), so the downgrade from LOW needs a fresh id;
        // drop the old channel or both linger in app notification settings.
        manager.deleteNotificationChannel(LEGACY_LISTENING_CHANNEL_ID)
        val channel = NotificationChannel(
            LISTENING_CHANNEL_ID,
            "Chat listener",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps a live connection to your Andy host for chat alerts"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AndyAttention"
        const val ACTION_START = "com.joetr.andy.attention.START"
        const val ACTION_STOP = "com.joetr.andy.attention.STOP"
        private const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_HOST_NAME = "host_name"

        private const val LISTENING_CHANNEL_ID = "andy_attention_listening_v2"
        /** Pre-MIN channel, deleted on first run so it stops showing in notification settings. */
        private const val LEGACY_LISTENING_CHANNEL_ID = "andy_attention_listening"
        private const val LISTENING_NOTIFICATION_ID = 42_001
        private const val PREFS = "andy_attention_push"
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_HOST = "host_name"
        private const val PULL_INTERVAL_MS = 1_500L
        /**
         * Grace period after the last Working chat before the listener stops. Long enough that a
         * Done → follow-up turn does not thrash the service, short enough that the ongoing
         * notification clears soon after the work does.
         */
        private const val IDLE_STOP_AFTER_MS = 2 * 60_000L
        private const val DEDUPE_MS = 5_000L
        /** Suppress repeat Done/Error alerts for the same chat (push + pull + confidence). */
        private const val TERMINAL_DEDUPE_MS = 60 * 60_000L

        @Volatile
        var viewingChatId: String? = null

        fun start(context: Context, baseUrl: String, sessionToken: String, hostName: String) {
            val intent = Intent(context, AttentionPushService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_TOKEN, sessionToken)
                putExtra(EXTRA_HOST_NAME, hostName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Restarts the listener for the stored session after an idle shutdown.
         *
         * Call this whenever the app hands the host work (new chat, reply, permission answer,
         * plan implement) — that is the moment a Done/Blocked/Error event becomes possible again.
         * Cheap to call when already running: [connect] no-ops for an unchanged live session.
         *
         * Must be called while the app is in the foreground — Android 12+ rejects foreground
         * service starts from the background.
         */
        fun ensureRunning(context: Context) {
            val session = loadSession(context) ?: return
            start(context, session.baseUrl, session.token, session.hostName)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AttentionPushService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun secrets(context: Context): KeystoreSecretStore {
            val app = context.applicationContext as? AndyApplication
            return app?.graph?.secretStore ?: KeystoreSecretStore(context.applicationContext)
        }

        private fun persistSession(context: Context, baseUrl: String, token: String, hostName: String) {
            migrateLegacyPrefsIfNeeded(context)
            val store = secrets(context)
            store.put(KEY_BASE, baseUrl)
            store.put(KEY_TOKEN, token)
            store.put(KEY_HOST, hostName)
            store.remove("master_token")
        }

        private fun clearSession(context: Context) {
            migrateLegacyPrefsIfNeeded(context)
            val store = secrets(context)
            store.clearKeys(listOf(KEY_BASE, KEY_TOKEN, KEY_HOST, "master_token"))
        }

        private fun loadSession(context: Context): Session? {
            migrateLegacyPrefsIfNeeded(context)
            val store = secrets(context)
            val base = store.get(KEY_BASE)?.takeIf { it.isNotBlank() } ?: return null
            val token = store.get(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
            val host = store.get(KEY_HOST).orEmpty().ifBlank { "Andy host" }
            return Session(base, token, host)
        }

        private fun migrateLegacyPrefsIfNeeded(context: Context) {
            val store = secrets(context)
            if (store.get(KEY_BASE) != null || store.get(KEY_TOKEN) != null) return
            val legacy = runCatching {
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS,
                    MasterKey.Builder(context.applicationContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull() ?: return
            legacy.getString(KEY_BASE, null)?.let { store.put(KEY_BASE, it) }
            legacy.getString(KEY_TOKEN, null)?.let { store.put(KEY_TOKEN, it) }
            legacy.getString(KEY_HOST, null)?.let { store.put(KEY_HOST, it) }
            legacy.edit().clear().apply()
        }

        private data class Session(val baseUrl: String, val token: String, val hostName: String)
    }
}

internal fun startAttentionSession(
    scope: CoroutineScope,
    persist: suspend () -> Unit,
    startForeground: () -> Unit,
    connect: () -> Unit,
): Job {
    // Android dispatches Service.onStartCommand on Main. Start the required foreground state and
    // network listener immediately; encrypted persistence can safely finish on the service's IO scope.
    startForeground()
    connect()
    return scope.launch { persist() }
}
