package app.andy.desktop.service.voice

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers a system-wide keyboard shortcut that fires even when Andy is unfocused.
 *
 * macOS uses Carbon [RegisterEventHotKey] (no Accessibility grant). Windows/Linux get a
 * no-op whose [isSupported] is false so Settings hides the binding row.
 */
interface GlobalHotKeyRegistrar {
    val pressed: SharedFlow<Unit>
    val isSupported: Boolean
    /**
     * Why the hotkey is not active. Set synchronously for unsupported/unmappable combos, and
     * asynchronously when macOS itself rejects the registration (see [register]).
     */
    val lastError: StateFlow<String?>
    /**
     * Null unregisters. Returns false when unsupported or the combo cannot be mapped.
     *
     * Safe to call from the AWT EDT: the macOS registration is applied asynchronously on the
     * AppKit main thread, so a `true` return only means the request was accepted. Watch
     * [lastError] for the outcome.
     */
    fun register(spec: GlobalHotKeySpec?): Boolean
}

/** Packed Compose [androidx.compose.ui.input.key.Key.keyCode] plus modifiers. */
data class GlobalHotKeySpec(
    val packedKeyCode: Long,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

fun GlobalHotKeyRegistrar(): GlobalHotKeyRegistrar =
    if (MacOsMicrophoneAccess.isSupported()) MacOsGlobalHotKeyRegistrar else NoOpGlobalHotKeyRegistrar

/** Process-wide registrar — Settings and Main share one Carbon registration. */
val desktopGlobalHotKeyRegistrar: GlobalHotKeyRegistrar by lazy { GlobalHotKeyRegistrar() }

object NoOpGlobalHotKeyRegistrar : GlobalHotKeyRegistrar {
    private val _pressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val pressed: SharedFlow<Unit> = _pressed.asSharedFlow()
    override val isSupported: Boolean = false
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()
    override fun register(spec: GlobalHotKeySpec?): Boolean {
        _lastError.value = if (spec == null) null else "Global voice hotkey is only available on macOS"
        return false
    }
}

/**
 * Loads the same andy-voice JNI dylib as [MacOsMicrophoneAccess] and registers a Carbon hotkey.
 */
internal object MacOsGlobalHotKeyRegistrar : GlobalHotKeyRegistrar {
    private val _pressed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val pressed: SharedFlow<Unit> = _pressed.asSharedFlow()
    override val isSupported: Boolean
        get() = MacOsMicrophoneAccess.isSupported() && ensureLoaded()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var loaded: Boolean? = null
    private val lock = Any()
    @Volatile private var handle: Int = -1

    override fun register(spec: GlobalHotKeySpec?): Boolean {
        synchronized(lock) {
            _lastError.value = null
            if (spec == null) {
                unregisterLocked()
                return true
            }
            if (!ensureLoaded()) {
                _lastError.value = "macOS voice bridge unavailable"
                return false
            }
            val mapped = CarbonHotKeyMapping.fromPackedKeyCode(
                packedKeyCode = spec.packedKeyCode,
                ctrl = spec.ctrl,
                meta = spec.meta,
                alt = spec.alt,
                shift = spec.shift,
            )
            if (mapped == null) {
                _lastError.value = "That key combination cannot be registered as a global hotkey"
                unregisterLocked()
                return false
            }
            return try {
                val newHandle = MacOsGlobalHotKeyBridge.nativeRegisterHotKey(
                    mapped.virtualKeyCode,
                    mapped.carbonModifiers,
                )
                if (newHandle < 0) {
                    _lastError.value = "Failed to register global hotkey with macOS (status $newHandle)"
                    handle = -1
                    voiceDebugLog("GlobalHotKey: register failed status=$newHandle key=0x${mapped.virtualKeyCode.toString(16)} mods=0x${mapped.carbonModifiers.toString(16)}")
                    false
                } else {
                    handle = newHandle
                    voiceDebugLog("GlobalHotKey: register requested handle=$newHandle key=0x${mapped.virtualKeyCode.toString(16)} mods=0x${mapped.carbonModifiers.toString(16)}")
                    true
                }
            } catch (t: Throwable) {
                _lastError.value = t.message ?: "Failed to register global hotkey"
                handle = -1
                voiceDebugLog("GlobalHotKey: register threw ${t.message}")
                false
            }
        }
    }

    /**
     * Called from JNI on the AppKit main thread once Carbon has rejected the registration that
     * [register] kicked off. [status] is a negated OSStatus (or -1 when the handler failed).
     * Ignores failures for a handle that is no longer current (stale async work).
     */
    fun notifyRegisterFailed(failedHandle: Int, status: Int) {
        synchronized(lock) {
            if (handle != failedHandle) {
                voiceDebugLog("GlobalHotKey: ignoring stale register failure handle=$failedHandle status=$status current=$handle")
                return
            }
            handle = -1
            _lastError.value = "Failed to register global hotkey with macOS (status $status)"
            voiceDebugLog("GlobalHotKey: register failed async status=$status")
        }
    }

    /** Called from JNI on the AppKit main thread via [MacOsGlobalHotKeyBridge.dispatchHotKeyPressed]. */
    fun notifyPressed() {
        // Hop to the AWT EDT so Compose collectors observe the press on the UI thread.
        val emit = {
            val emitted = _pressed.tryEmit(Unit)
            voiceDebugLog("GlobalHotKey: pressed tryEmit=$emitted")
        }
        if (java.awt.EventQueue.isDispatchThread()) {
            emit()
        } else {
            java.awt.EventQueue.invokeLater(emit)
        }
    }

    private fun unregisterLocked() {
        if (handle < 0 && loaded != true) return
        if (ensureLoaded()) {
            runCatching { MacOsGlobalHotKeyBridge.nativeUnregisterHotKey(handle) }
        }
        handle = -1
    }

    private fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = MacOsMicrophoneAccess.ensureNativeLoaded()
        loaded = ok
        voiceDebugLog("GlobalHotKey: ensureLoaded=$ok")
        return ok
    }
}

/**
 * Thin JNI surface for Carbon RegisterEventHotKey. Lives beside [MacOsMicrophoneAccess]
 * so both share the andy-voice dylib.
 */
internal object MacOsGlobalHotKeyBridge {
    /** Static entry the Carbon handler invokes — must stay `@JvmStatic` for GetStaticMethodID. */
    @JvmStatic
    fun dispatchHotKeyPressed() {
        MacOsGlobalHotKeyRegistrar.notifyPressed()
    }

    /** Static entry the async registration path invokes on failure — keep `@JvmStatic`. */
    @JvmStatic
    fun dispatchRegisterFailed(handle: Int, status: Int) {
        MacOsGlobalHotKeyRegistrar.notifyRegisterFailed(handle, status)
    }

    @JvmStatic
    external fun nativeRegisterHotKey(
        virtualKeyCode: Int,
        carbonModifiers: Int,
    ): Int

    @JvmStatic
    external fun nativeUnregisterHotKey(handle: Int)
}
