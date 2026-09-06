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
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
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
    private lateinit var notifications: AndroidChatNotificationService
    private val tracker = ChatAttentionTracker()
    private val notifyMutex = Mutex()
    private val recentKeys = mutableMapOf<String, Long>()
    private val pushStatus = AtomicReference("push connecting…")
    private val pullStatus = AtomicReference("pull starting…")
    private var hostLabel: String = "Andy host"

    override fun onCreate() {
        super.onCreate()
        notifications = AndroidChatNotificationService(this)
        notifications.ensureChannel()
        ensureListeningChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                clearSession(this)
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
                persistSession(this, baseUrl, token, hostName)
                hostLabel = hostName
                startAsForeground(combinedStatus())
                connect(baseUrl, token)
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
        scope.cancel()
        super.onDestroy()
    }

    private fun connect(baseUrl: String, token: String) {
        listenJob?.cancel()
        client?.close()
        tracker.reset()
        val next = NetworkAccessClient(baseUrl, longLived = true).also { it.sessionToken = token }
        client = next
        listenJob = scope.launch {
            launch { pushLoop(next) }
            launch { pullLoop(next) }
        }
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
                pullStatus.set("pull down")
                publishStatus()
            }
            delay(PULL_INTERVAL_MS)
        }
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
        notifyMutex.withLock {
            val last = recentKeys[key]
            if (last != null && now - last < DEDUPE_MS) return
            recentKeys[key] = now
            if (recentKeys.size > 200) {
                recentKeys.entries.removeIf { now - it.value > DEDUPE_MS }
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureListeningChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            LISTENING_CHANNEL_ID,
            "Chat listener",
            NotificationManager.IMPORTANCE_LOW,
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
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_HOST_NAME = "host_name"

        private const val LISTENING_CHANNEL_ID = "andy_attention_listening"
        private const val LISTENING_NOTIFICATION_ID = 42_001
        private const val PREFS = "andy_attention_push"
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_HOST = "host_name"
        private const val PULL_INTERVAL_MS = 1_500L
        private const val DEDUPE_MS = 5_000L

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

        fun stop(context: Context) {
            val intent = Intent(context, AttentionPushService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun prefs(context: Context) = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        private fun persistSession(context: Context, baseUrl: String, token: String, hostName: String) {
            prefs(context).edit()
                .putString(KEY_BASE, baseUrl)
                .putString(KEY_TOKEN, token)
                .putString(KEY_HOST, hostName)
                .apply()
        }

        private fun clearSession(context: Context) {
            prefs(context).edit().clear().apply()
        }

        private fun loadSession(context: Context): Session? {
            val p = prefs(context)
            val base = p.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() } ?: return null
            val token = p.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
            val host = p.getString(KEY_HOST, null).orEmpty().ifBlank { "Andy host" }
            return Session(base, token, host)
        }

        private data class Session(val baseUrl: String, val token: String, val hostName: String)
    }
}
