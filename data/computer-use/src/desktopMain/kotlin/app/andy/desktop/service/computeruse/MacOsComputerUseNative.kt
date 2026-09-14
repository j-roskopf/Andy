package app.andy.desktop.service.computeruse

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI entry points for macOS computer-use (AX tree, AXPress, CGEvent, capture).
 */
internal object MacOsComputerUseNative {
    @Volatile private var loaded: Boolean? = null

    fun isSupported(): Boolean = resourcePath() != null

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = loadLibrary().isSuccess
        loaded = ok
        return ok
    }

    fun isAccessibilityTrusted(): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { nativeIsAccessibilityTrusted() }.getOrDefault(false)
    }

    fun isScreenCaptureAllowed(): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { nativeIsScreenCaptureAllowed() }.getOrDefault(false)
    }

    fun openAccessibilitySettings() {
        if (!ensureLoaded()) return
        runCatching { nativeOpenAccessibilitySettings() }
    }

    fun openScreenRecordingSettings() {
        if (!ensureLoaded()) return
        runCatching { nativeOpenScreenRecordingSettings() }
    }

    fun listRunningAppsJson(): String {
        if (!ensureLoaded()) return "[]"
        return runCatching { nativeListRunningApps() }.getOrDefault("[]")
    }

    fun dumpTree(appName: String, menus: Boolean): String {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        return nativeDumpTree(appName, menus)
    }

    fun pressElement(elementId: String, settleMs: Int = 250): String {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        return nativePressElement(elementId, settleMs)
    }

    fun setElementValue(elementId: String, value: String): String {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        return nativeSetElementValue(elementId, value)
    }

    fun click(x: Int, y: Int) {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        nativeClick(x, y)
    }

    fun scroll(x: Int, y: Int, deltaX: Int, deltaY: Int) {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        nativeScroll(x, y, deltaX, deltaY)
    }

    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        nativeDrag(x1, y1, x2, y2, durationMs)
    }

    fun typeText(text: String) {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        nativeTypeText(text)
    }

    fun pressKey(keyCode: Int, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean) {
        check(ensureLoaded()) { "andy-computer-use native bridge unavailable" }
        nativePressKey(keyCode, shift, ctrl, alt, meta)
    }

    fun capturePng(x: Int = 0, y: Int = 0, w: Int = 0, h: Int = 0): ByteArray? {
        if (!ensureLoaded()) return null
        return runCatching { nativeCapturePng(x, y, w, h) }.getOrNull()
    }

    fun displayGeometryJson(): String {
        if (!ensureLoaded()) return "[]"
        return runCatching { nativeDisplayGeometry() }.getOrDefault("[]")
    }

    fun focusElement(elementId: String): Boolean {
        if (!ensureLoaded()) return false
        return runCatching { nativeFocusElement(elementId) }.getOrDefault(false)
    }

    fun elementMeta(elementId: String): String {
        if (!ensureLoaded()) return """{"error":"unavailable"}"""
        return runCatching { nativeElementMeta(elementId) }.getOrDefault("""{"error":"unavailable"}""")
    }

    /** Focused application + whether its focused control is a secure field. */
    fun focusedAppInfo(): String {
        if (!ensureLoaded()) return """{"error":"unavailable"}"""
        return runCatching { nativeFocusedAppInfo() }.getOrDefault("""{"error":"unavailable"}""")
    }

    /** Union of an app's on-screen window frames, as `{x,y,w,h}` logical points. */
    fun appWindowBounds(appName: String): String {
        if (!ensureLoaded()) return """{"error":"unavailable"}"""
        return runCatching { nativeAppWindowBounds(appName) }.getOrDefault("""{"error":"unavailable"}""")
    }

    private fun loadLibrary() = runCatching {
        val resourcePath = resourcePath() ?: error("No macOS computer-use bridge for this platform")
        val target = File(System.getProperty("user.home"), ".andy/computer-use/native/$resourcePath")
        target.parentFile.mkdirs()
        try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
                Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } ?: error("Missing packaged computer-use bridge: $resourcePath")
        } catch (error: Exception) {
            if (!target.isFile) throw error
        }
        System.load(target.absolutePath)
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-computer-use/macos-arm64/andy-computer-use-jni.dylib"
            "x86_64", "amd64" -> "andy-computer-use/macos-x86_64/andy-computer-use-jni.dylib"
            else -> null
        }
    }

    @JvmStatic private external fun nativeIsAccessibilityTrusted(): Boolean
    @JvmStatic private external fun nativeIsScreenCaptureAllowed(): Boolean
    @JvmStatic private external fun nativeOpenAccessibilitySettings()
    @JvmStatic private external fun nativeOpenScreenRecordingSettings()
    @JvmStatic private external fun nativeListRunningApps(): String
    @JvmStatic private external fun nativeDumpTree(appName: String, menus: Boolean): String
    @JvmStatic private external fun nativePressElement(elementId: String, settleMs: Int): String
    @JvmStatic private external fun nativeSetElementValue(elementId: String, value: String): String
    @JvmStatic private external fun nativeClick(x: Int, y: Int)
    @JvmStatic private external fun nativeScroll(x: Int, y: Int, deltaX: Int, deltaY: Int)
    @JvmStatic private external fun nativeDrag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    @JvmStatic private external fun nativeTypeText(text: String)
    @JvmStatic private external fun nativePressKey(
        keyCode: Int,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
    )
    @JvmStatic private external fun nativeCapturePng(x: Int, y: Int, w: Int, h: Int): ByteArray?
    @JvmStatic private external fun nativeDisplayGeometry(): String
    @JvmStatic private external fun nativeFocusElement(elementId: String): Boolean
    @JvmStatic private external fun nativeElementMeta(elementId: String): String
    @JvmStatic private external fun nativeFocusedAppInfo(): String
    @JvmStatic private external fun nativeAppWindowBounds(appName: String): String
}
