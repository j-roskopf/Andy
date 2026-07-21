package app.andy.ui.live

import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-wide Live stream quality/renderer settings. The Live tab owns edits; project and other
 * embedded panes reconnect with the same [MirrorVideoConfig] instead of engine defaults.
 */
internal object LiveMirrorSettings {
    private val mutableConfig = MutableStateFlow(MirrorVideoConfig())
    val config: StateFlow<MirrorVideoConfig> = mutableConfig.asStateFlow()

    fun update(config: MirrorVideoConfig) {
        mutableConfig.value = config
    }
}
