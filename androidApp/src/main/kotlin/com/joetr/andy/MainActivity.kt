package com.joetr.andy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.joetr.andy.mobile.AndyMobileApp
import com.joetr.andy.mobile.data.attention.AndroidChatNotificationService

class MainActivity : ComponentActivity() {
    private var pendingOpenChatId by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark system bars — the app is pitch-black; Light.NoActionBar left a white status strip.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AndroidChatNotificationService(this).ensureChannel()
        pendingOpenChatId = intent.chatIdExtra()
        setContent {
            AndyMobileApp(
                pendingOpenChatId = pendingOpenChatId,
                onPendingOpenChatConsumed = { pendingOpenChatId = null },
                onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChatId = intent.chatIdExtra()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT_ID = "open_chat_id"
        const val EXTRA_OPEN_PROJECT_ID = "open_project_id"
    }
}

private fun Intent.chatIdExtra(): String? =
    getStringExtra(MainActivity.EXTRA_OPEN_CHAT_ID)?.takeIf { it.isNotBlank() }
