package com.joetr.andy.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.joetr.andy.MainActivity
import com.joetr.andy.R

/**
 * Home-screen widget that starts the same voice-new-thread path as the launcher shortcut:
 * [MainActivity.ACTION_VOICE_NEW_THREAD] → system speech UI → confirm Start.
 */
class VoiceThreadWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    companion object {
        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_voice_thread)
            val launch = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_VOICE_NEW_THREAD
                // Fresh task from the home screen so singleTop still receives the voice action.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            views.setOnClickPendingIntent(
                R.id.voice_thread_widget_root,
                PendingIntent.getActivity(context, REQUEST_VOICE_THREAD, launch, flags),
            )
            return views
        }

        private const val REQUEST_VOICE_THREAD = 4201
    }
}
