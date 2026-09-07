package com.joetr.andy.mobile.data.attention

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.joetr.andy.MainActivity
import com.joetr.andy.R

/**
 * Posts system notifications for Network Access chat attention events
 * (same copy as desktop: needs input / completed / failed).
 */
class AndroidChatNotificationService(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent chats",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Chat complete, needs input, and failures from your Andy host"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(event: ChatAttentionEvent) {
        ensureChannel()
        val subtitle = ChatAttentionTracker.subtitle(event.kind, event.planMode)
        val open = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT_ID, event.chatId)
            event.projectId?.let { putExtra(MainActivity.EXTRA_OPEN_PROJECT_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            appContext,
            event.chatId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(subtitle)
            .setContentText(event.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.title))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId(event), notification)
        }.onFailure {
            android.util.Log.w("AndyAttention", "notify failed: ${it.message}")
        }
    }

    fun cancel(chatId: String) {
        val manager = NotificationManagerCompat.from(appContext)
        for (kind in ChatAttentionKind.entries) {
            manager.cancel(notificationId(chatId, kind))
        }
    }

    private fun notificationId(event: ChatAttentionEvent): Int =
        notificationId(event.chatId, event.kind)

    private fun notificationId(chatId: String, kind: ChatAttentionKind): Int =
        31 * chatId.hashCode() + kind.ordinal

    companion object {
        const val CHANNEL_ID = "andy_agent_chats"
    }
}
