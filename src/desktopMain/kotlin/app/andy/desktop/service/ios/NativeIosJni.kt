package app.andy.desktop.service.ios

import app.andy.desktop.service.mirror.NativeMirrorJni
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object NativeIosBridge {
    val loadResult: Result<Unit> by lazy {
        NativeMirrorJni.ensureLoaded()
    }

    fun resourcePath(): String? = NativeMirrorJni.resourcePath()
}

internal object NativeIosSimJni {
    fun ensureLoaded() = NativeIosBridge.loadResult

    fun isAvailable(): Boolean = ensureLoaded().isSuccess && runCatching { nativeProbe() }.getOrDefault(false)

    fun diagnostic(): String = if (!ensureLoaded().isSuccess) {
        "Native mirror bridge unavailable"
    } else {
        runCatching { nativeDiagnostic() }.getOrDefault("")
    }

    fun connect(udid: String): IntArray? {
        if (!isAvailable()) return null
        return runCatching { nativeConnect(udid) }.getOrNull()?.takeIf { it.size >= 2 && it[0] > 0 && it[1] > 0 }
    }

    fun disconnect() {
        if (ensureLoaded().isSuccess) runCatching { nativeDisconnect() }
    }

    fun contentSizePoints(): IntArray =
        if (ensureLoaded().isSuccess) runCatching { nativeContentSizePoints() }.getOrNull() ?: intArrayOf(390, 844)
        else intArrayOf(390, 844)

    /** Best-effort HID handshake after connect; safe to call when Simulator.app is still starting. */
    fun ensureInputReady(): Boolean =
        ensureLoaded().isSuccess && runCatching { nativeEnsureInputReady() }.getOrDefault(false)

    fun sendTouch(action: Int, nx: Float, ny: Float): Boolean =
        ensureLoaded().isSuccess && runCatching { nativeSendTouch(action, nx, ny) }.getOrDefault(false)

    fun sendSwipe(startX: Float, startY: Float, endX: Float, endY: Float, steps: Int) {
        if (ensureLoaded().isSuccess) runCatching { nativeSendSwipe(startX, startY, endX, endY, steps) }
    }

    fun sendText(value: String) {
        if (ensureLoaded().isSuccess) runCatching { nativeSendText(value) }
    }

    fun sendButton(button: Int) {
        if (ensureLoaded().isSuccess) runCatching { nativeSendButton(button) }
    }

    /**
     * True when Simulator.app shows an on-screen device window. [displayName] matches the window
     * title when available (e.g. "iPhone 17 Pro"); null matches any device-sized window.
     */
    fun hasVisibleDeviceWindow(displayName: String? = null): Boolean =
        ensureLoaded().isSuccess &&
            runCatching { nativeHasVisibleDeviceWindow(displayName) }.getOrDefault(false)

    /** Hides Simulator.app after an embedded Live handoff so its windows stop competing. */
    fun hideSimulatorApp() {
        if (ensureLoaded().isSuccess) runCatching { nativeHideSimulatorApp() }
    }

    /** Drops a stale LegacyHID client after Simulator.app was frontmost during handoff. */
    fun resetInput() {
        if (ensureLoaded().isSuccess) runCatching { nativeResetInput() }
    }

    /** True when SimDeviceIO is attached to a booted simulator for the active session. */
    fun isCaptureHealthy(): Boolean =
        ensureLoaded().isSuccess && runCatching { nativeIsCaptureHealthy() }.getOrDefault(false)

    private external fun nativeProbe(): Boolean
    private external fun nativeDiagnostic(): String
    private external fun nativeHasVisibleDeviceWindow(displayName: String?): Boolean
    private external fun nativeHideSimulatorApp()
    private external fun nativeResetInput()
    private external fun nativeIsCaptureHealthy(): Boolean
    private external fun nativeConnect(udid: String): IntArray
    private external fun nativeDisconnect()
    private external fun nativeContentSizePoints(): IntArray
    private external fun nativeEnsureInputReady(): Boolean
    private external fun nativeSendTouch(action: Int, nx: Float, ny: Float): Boolean
    private external fun nativeSendSwipe(startX: Float, startY: Float, endX: Float, endY: Float, steps: Int)
    private external fun nativeSendText(value: String)
    private external fun nativeSendButton(button: Int)
}

internal object NativeIosDeviceJni {
    fun isAvailable(): Boolean = NativeIosBridge.loadResult.isSuccess && runCatching { nativeProbe() }.getOrDefault(false)

    fun diagnostic(): String =
        if (!NativeIosBridge.loadResult.isSuccess) "Native mirror bridge unavailable"
        else runCatching { nativeDiagnostic() }.getOrDefault("")

    fun prepareForCapture() {
        if (!isAvailable()) return
        runCatching { nativePrepareForCapture() }
    }

    fun connect(udid: String, displayName: String? = null, coreDeviceIdentifier: String? = null): IntArray? =
        if (!isAvailable()) null
        else runCatching {
            nativeConnect(udid, coreDeviceIdentifier.orEmpty(), displayName.orEmpty())
        }.getOrNull()?.takeIf { it.size >= 2 }

    fun disconnect() {
        if (NativeIosBridge.loadResult.isSuccess) runCatching { nativeDisconnect() }
    }

    fun contentSize(): IntArray =
        if (!isAvailable()) intArrayOf(0, 0)
        else runCatching { nativeContentSize() }.getOrNull() ?: intArrayOf(0, 0)

    private external fun nativeProbe(): Boolean
    private external fun nativeDiagnostic(): String
    private external fun nativePrepareForCapture()
    private external fun nativeConnect(udid: String, altUdid: String, displayName: String): IntArray
    private external fun nativeDisconnect()
    private external fun nativeContentSize(): IntArray
}
