package app.andy.desktop.service

import app.andy.service.OpenAgentTaskRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Delivers macOS Notification Center banners from Andy's process so clicks
 * activate Andy (with the app icon) instead of Script Editor via osascript.
 */
internal object MacOsNotificationBridge {
    @Volatile private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        val loaded = loadLibrary().isSuccess && runCatching { nativeInstall() }.getOrDefault(false)
        available = loaded
        return loaded
    }

    fun show(title: String, subtitle: String, body: String, taskId: String, projectId: String?) {
        if (!isAvailable()) return
        nativeShow(title, subtitle, body, taskId, projectId)
    }

    /** Invoked from native code on the notification click path. */
    @Suppress("unused")
    fun onNotificationActivated(taskId: String, projectId: String?) {
        val id = taskId.trim()
        if (id.isEmpty()) return
        PendingAgentTaskOpen.activate(
            OpenAgentTaskRequest(id, projectId?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }

    private fun loadLibrary() = runCatching {
        val resourcePath = resourcePath() ?: error("No macOS notification bridge for this platform")
        val target = File(System.getProperty("user.home"), ".andy/notifications/$resourcePath")
        target.parentFile.mkdirs()
        javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
            Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing packaged notification bridge: $resourcePath")
        System.load(target.absolutePath)
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-notifications/macos-arm64/andy-notifications-jni.dylib"
            "x86_64", "amd64" -> "andy-notifications/macos-x86_64/andy-notifications-jni.dylib"
            else -> null
        }
    }

    private external fun nativeInstall(): Boolean
    private external fun nativeShow(
        title: String,
        subtitle: String,
        body: String,
        taskId: String,
        projectId: String?,
    )
}
