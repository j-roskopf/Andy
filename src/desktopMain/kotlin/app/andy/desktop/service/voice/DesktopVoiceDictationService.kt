package app.andy.desktop.service.voice

import app.andy.service.VoiceDictationService
import app.andy.service.VoiceSetupService
import app.andy.service.VoiceSetupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class DesktopVoiceDictationService(
    override val setup: VoiceSetupService,
    private val recorder: VoiceRecorder = JavaxSoundVoiceRecorder(),
    private val transcriberFactory: () -> WhisperTranscriber = {
        val voice = setup as? DesktopVoiceSetupService
            ?: error("DesktopVoiceDictationService requires DesktopVoiceSetupService")
        CliWhisperTranscriber(
            binary = voice.binaryFile(),
            model = voice.modelFile(),
            libDir = voice.libDirectory(),
            backendFile = voice.preferredBackendFile(),
        )
    },
) : VoiceDictationService {
    private val busy = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override val isReady: Boolean
        get() = setup.state.value is VoiceSetupState.Ready

    override suspend fun startRecording(): Boolean {
        _lastError.value = null
        if (!isReady) {
            _lastError.value = "Enable voice dictation in Settings"
            return false
        }
        if (!busy.compareAndSet(false, true)) return false
        return try {
            val ok = recorder.startRecording()
            voiceDebugLog("startRecording: recorder.startRecording() -> $ok")
            if (!ok) {
                _lastError.value = micAccessDeniedMessage()
                busy.set(false)
                false
            } else {
                recording.set(true)
                true
            }
        } catch (t: Throwable) {
            voiceDebugLog("startRecording: threw ${t.message}")
            _lastError.value = t.message ?: "Failed to start recording"
            busy.set(false)
            false
        }
    }

    override suspend fun finishRecording(): String? {
        if (!recording.getAndSet(false)) {
            busy.set(false)
            return null
        }
        return try {
            val wav = recorder.stopRecording()
            if (wav == null) {
                val silent = (recorder as? JavaxSoundVoiceRecorder)?.lastStopWasSilent == true
                voiceDebugLog(
                    "finishRecording: recorder.stopRecording returned null " +
                        "(dropped, silent=$silent; see stopRecording log line above)",
                )
                if (silent) {
                    _lastError.value = micAccessDeniedMessage()
                }
                return null
            }
            val text = transcriberFactory().transcribe(wav).getOrElse { error ->
                voiceDebugLog("finishRecording: transcribe failed: ${error.message}")
                _lastError.value = error.message
                return null
            }
            voiceDebugLog("finishRecording: whisper returned text=\"$text\"")
            val cleaned = text.trim()
            // Defense in depth: whisper's common silence hallucination.
            if (cleaned.equals("you", ignoreCase = true) ||
                cleaned.equals("thank you.", ignoreCase = true) ||
                cleaned.equals("thanks.", ignoreCase = true)
            ) {
                voiceDebugLog("finishRecording: dropping likely silence hallucination \"$cleaned\"")
                _lastError.value = micAccessDeniedMessage()
                return null
            }
            cleaned.takeIf { it.isNotBlank() }
        } finally {
            busy.set(false)
        }
    }

    override fun cancelRecording() {
        if (!recording.getAndSet(false)) {
            busy.set(false)
            return
        }
        runCatching { recorder.abandonRecording() }
        busy.set(false)
        voiceDebugLog("cancelRecording: abandoned in-progress capture")
    }

    private fun micAccessDeniedMessage(): String =
        "Microphone access denied — macOS never prompted (common for unsigned debug builds). " +
            "Quit Andy, run ./gradlew runDistributable, then Allow when asked. " +
            "Andy should then appear under System Settings → Privacy & Security → Microphone."
}
