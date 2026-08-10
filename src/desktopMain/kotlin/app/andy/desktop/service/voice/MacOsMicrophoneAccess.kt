package app.andy.desktop.service.voice

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Result of querying / requesting macOS microphone TCC access. */
enum class MacOsMicPermission {
    Granted,
    Denied,
    Restricted,
    NotDetermined,
    Unavailable,
}

/**
 * Triggers the real macOS microphone permission prompt via AVFoundation.
 *
 * javax.sound alone does not drive TCC on modern macOS — the line opens and yields
 * digital silence with no Control Center indicator — so dictation must call this first.
 */
internal object MacOsMicrophoneAccess {
    @Volatile private var loaded: Boolean? = null

    fun isSupported(): Boolean = resourcePath() != null

    fun authorizationStatus(): MacOsMicPermission {
        if (!ensureLoaded()) return MacOsMicPermission.Unavailable
        return decode(runCatching { nativeAuthorizationStatus() }.getOrDefault(-1))
    }

    /** Blocks until the user answers the system prompt (or returns the cached decision). */
    fun requestAccess(): MacOsMicPermission {
        if (!ensureLoaded()) return MacOsMicPermission.Unavailable
        return decode(runCatching { nativeRequestAccess() }.getOrDefault(-1))
    }

    private fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = loadLibrary().isSuccess
        loaded = ok
        if (!ok) {
            voiceDebugLog("MacOsMicrophoneAccess: failed to load native bridge")
        }
        return ok
    }

    private fun loadLibrary() = runCatching {
        val resourcePath = resourcePath() ?: error("No macOS voice bridge for this platform")
        val target = File(System.getProperty("user.home"), ".andy/voice/native/$resourcePath")
        target.parentFile.mkdirs()
        try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
                Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } ?: error("Missing packaged voice bridge: $resourcePath")
        } catch (error: Exception) {
            if (!target.isFile) throw error
        }
        System.load(target.absolutePath)
        voiceDebugLog("MacOsMicrophoneAccess: loaded $resourcePath")
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-voice/macos-arm64/andy-voice-jni.dylib"
            "x86_64", "amd64" -> "andy-voice/macos-x86_64/andy-voice-jni.dylib"
            else -> null
        }
    }

    private fun decode(code: Int): MacOsMicPermission = when (code) {
        1 -> MacOsMicPermission.Granted
        0 -> MacOsMicPermission.Denied
        2 -> MacOsMicPermission.Restricted
        3 -> MacOsMicPermission.NotDetermined
        else -> MacOsMicPermission.Unavailable
    }

    @JvmStatic private external fun nativeAuthorizationStatus(): Int
    @JvmStatic private external fun nativeRequestAccess(): Int
}
