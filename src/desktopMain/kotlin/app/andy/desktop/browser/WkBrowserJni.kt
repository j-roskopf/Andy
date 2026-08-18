package app.andy.desktop.browser

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to a borderless WKWebView child window over Andy's Compose canvas.
 * All AppKit/WebKit work runs on the Cocoa main queue inside the native library —
 * never on AWT-EDT — so we avoid the "Must only be used from the main thread" crash
 * that killed in-process JCEF/JOGL on macOS.
 */
internal object WkBrowserJni {
    private val installed = AtomicBoolean(false)
    private val loadResult: Result<Unit> = loadLibrary()

    @Volatile
    var onNavState: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit =
        { _, _, _, _, _ -> }
        private set

    /** Last URL reported by WKWebView; used to skip reload when the surface remounts. */
    @Volatile
    private var currentUrl: String = ""

    val available: Boolean get() = loadResult.isSuccess

    fun failureMessage(): String? = loadResult.exceptionOrNull()?.message

    fun install(
        listener: (title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
    ): Boolean {
        if (!loadResult.isSuccess) return false
        onNavState = listener
        if (installed.get()) return true
        val ok = runCatching { nativeInstall() }.getOrDefault(false)
        if (ok) installed.set(true)
        return ok
    }

    fun open(): Boolean =
        loadResult.isSuccess && runCatching { nativeOpen() }.getOrDefault(false)

    fun setBottomCornerRadius(radiusPx: Float) {
        if (loadResult.isSuccess) runCatching { nativeSetBottomCornerRadius(radiusPx) }
    }

    fun updateFrame(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double,
        parentWindowNumber: Int,
    ) {
        if (!loadResult.isSuccess) return
        runCatching { nativeUpdateFrame(x, y, width, height, scale, parentWindowNumber) }
    }

    fun setVisible(visible: Boolean) {
        if (loadResult.isSuccess) runCatching { nativeSetVisible(visible) }
    }

    fun loadUrl(url: String) {
        if (!loadResult.isSuccess || url.isBlank()) return
        if (url == currentUrl) return
        runCatching { nativeLoadUrl(url) }
    }

    /** Give WKWebView key-window status so page fields accept typing. */
    fun focus() {
        if (loadResult.isSuccess) runCatching { nativeFocus() }
    }

    /** Hand key-window status back to Andy's AWT frame (address bar / dock tabs). */
    fun resignKey() {
        if (loadResult.isSuccess) runCatching { nativeResignKey() }
    }

    fun goBack() {
        if (loadResult.isSuccess) runCatching { nativeGoBack() }
    }

    fun goForward() {
        if (loadResult.isSuccess) runCatching { nativeGoForward() }
    }

    fun reload() {
        if (loadResult.isSuccess) runCatching { nativeReload() }
    }

    fun close() {
        currentUrl = ""
        if (loadResult.isSuccess) runCatching { nativeClose() }
    }

    /** Called from native (any thread); must stay public for JNI GetMethodID. */
    @Suppress("unused")
    fun onNavStateFromNative(
        title: String?,
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        loading: Boolean,
    ) {
        runCatching {
            currentUrl = url.orEmpty()
            onNavState(title, url.orEmpty(), canGoBack, canGoForward, loading)
        }
    }

    private fun loadLibrary() = runCatching {
        val resourcePath = resourcePath() ?: error("WKWebView bridge is only packaged for macOS")
        val target = File(System.getProperty("user.home"), ".andy/browser/$resourcePath")
        target.parentFile.mkdirs()
        javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
            Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing packaged WKWebView bridge: $resourcePath")
        System.load(target.absolutePath)
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-browser/macos-arm64/andy-browser-jni.dylib"
            "x86_64", "amd64" -> "andy-browser/macos-x86_64/andy-browser-jni.dylib"
            else -> null
        }
    }

    private external fun nativeInstall(): Boolean
    private external fun nativeOpen(): Boolean
    private external fun nativeSetBottomCornerRadius(radiusPx: Float)
    private external fun nativeUpdateFrame(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double,
        parentWindowNumber: Int,
    )
    private external fun nativeSetVisible(visible: Boolean)
    private external fun nativeLoadUrl(url: String)
    private external fun nativeFocus()
    private external fun nativeResignKey()
    private external fun nativeGoBack()
    private external fun nativeGoForward()
    private external fun nativeReload()
    private external fun nativeClose()
}
