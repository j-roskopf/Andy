package com.joetr.andy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.joetr.andy.mobile.mobileGraph
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingOpenChatId by mutableStateOf<String?>(null)
    /**
     * Non-null means navigate to NewChat with voice defaults.
     * [PendingVoiceNewThread.prompt] null → launch [RecognizerIntent] first.
     */
    private var pendingVoiceNewThread by mutableStateOf<PendingVoiceNewThread?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val speechRecognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            // OK with empty results still opens confirm so the user can type.
            pendingVoiceNewThread = PendingVoiceNewThread(prompt = spoken.orEmpty())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark system bars — the app is pitch-black; Light.NoActionBar left a white status strip.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        application.mobileGraph.notificationService.ensureChannel()
        pendingOpenChatId = intent.chatIdExtra()
        handleVoiceIntent(intent)
        setContent {
            AndyMobileApp(
                graph = application.mobileGraph,
                pendingOpenChatId = pendingOpenChatId,
                onPendingOpenChatConsumed = { pendingOpenChatId = null },
                pendingVoiceNewThread = pendingVoiceNewThread,
                onPendingVoiceConsumed = { pendingVoiceNewThread = null },
                onRequestSpeechRecognition = ::launchSpeechRecognition,
                onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChatId = intent.chatIdExtra()
        handleVoiceIntent(intent)
    }

    private fun handleVoiceIntent(intent: Intent?) {
        if (intent?.action != ACTION_VOICE_NEW_THREAD) return
        // Never accept third-party prompt extras — widget/shortcut only trigger recognition.
        pendingVoiceNewThread = PendingVoiceNewThread(prompt = null)
        scrubVoiceIntent()
    }

    /** Drop the voice action so process death / recreate does not re-fire recognition. */
    private fun scrubVoiceIntent() {
        setIntent(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                // Preserve open-chat deep links if they were combined somehow.
                pendingOpenChatId?.let { putExtra(EXTRA_OPEN_CHAT_ID, it) }
            },
        )
    }

    private fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                getString(com.joetr.andy.R.string.voice_recognizer_prompt),
            )
            // Best-effort: give thinking pauses more room. OEM recognizers (esp. Google /
            // Samsung) may ignore these — full control needs an in-app SpeechRecognizer.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, VOICE_MIN_LENGTH_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                VOICE_COMPLETE_SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                VOICE_POSSIBLY_COMPLETE_SILENCE_MS,
            )
        }
        runCatching { speechRecognizer.launch(intent) }
            .onFailure {
                // No recognizer available — still open confirm so the user can type.
                pendingVoiceNewThread = PendingVoiceNewThread(prompt = "")
            }
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
        const val ACTION_VOICE_NEW_THREAD = "com.joetr.andy.action.VOICE_NEW_THREAD"

        /** Don't end the session in the first few seconds even if quiet. */
        private const val VOICE_MIN_LENGTH_MS = 4_000L
        /** Quiet after the last word before treating dictation as finished. */
        private const val VOICE_COMPLETE_SILENCE_MS = 4_000L
        /** Mid-phrase pause tolerance (whichever silence extra is lower tends to win). */
        private const val VOICE_POSSIBLY_COMPLETE_SILENCE_MS = 3_000L
    }
}

/** Prompt ready for NewChat; null prompt means recognition still needed. */
data class PendingVoiceNewThread(val prompt: String?)

private fun Intent.chatIdExtra(): String? =
    getStringExtra(MainActivity.EXTRA_OPEN_CHAT_ID)?.takeIf { it.isNotBlank() }
