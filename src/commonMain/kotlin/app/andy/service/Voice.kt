package app.andy.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceSetupState {
    data object NotEnabled : VoiceSetupState
    data class Downloading(val what: String, val progress: Float) : VoiceSetupState
    data object Ready : VoiceSetupState
    data class Failed(val what: String, val message: String) : VoiceSetupState
}

interface VoiceSetupService {
    val state: StateFlow<VoiceSetupState>
    suspend fun enable()
    fun disable()
}

/**
 * Desktop push-to-talk dictation. Web returns [UnavailableVoiceDictationService].
 *
 * [startRecording] begins capture; [finishRecording] stops, transcribes, and returns
 * inserted text (or null when the press was too short / silent / failed softly).
 */
interface VoiceDictationService {
    val setup: VoiceSetupService
    val isReady: Boolean
    suspend fun startRecording(): Boolean
    /** Stops recording and returns transcribed text, or null for short/empty/cancelled. */
    suspend fun finishRecording(): String?
    /** Soft error message from the last failed start/finish, if any. */
    val lastError: StateFlow<String?>
}

object UnavailableVoiceSetupService : VoiceSetupService {
    override val state = MutableStateFlow<VoiceSetupState>(VoiceSetupState.NotEnabled)
    override suspend fun enable() = Unit
    override fun disable() = Unit
}

object UnavailableVoiceDictationService : VoiceDictationService {
    override val setup: VoiceSetupService = UnavailableVoiceSetupService
    override val isReady: Boolean = false
    override suspend fun startRecording(): Boolean = false
    override suspend fun finishRecording(): String? = null
    override val lastError = MutableStateFlow<String?>(null)
}
