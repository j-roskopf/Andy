package app.andy.desktop.service

import app.andy.model.AgentNotificationSound
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import app.andy.service.NotificationSoundPlayer
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OsNotificationService
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlin.concurrent.thread

object PendingAgentTaskOpen {
    @Volatile var request: OpenAgentTaskRequest? = null
    @Volatile private var onActivate: (() -> Unit)? = null

    fun consume(): OpenAgentTaskRequest? = request.also { request = null }

    fun setActivationHandler(handler: (() -> Unit)?) {
        onActivate = handler
    }

    fun offer(request: OpenAgentTaskRequest) {
        this.request = request
    }

    /** Notification click (or equivalent): stash the task and bring Andy forward. */
    fun activate(request: OpenAgentTaskRequest) {
        this.request = request
        onActivate?.invoke()
    }
}

class DesktopOsNotificationService : OsNotificationService {
    override fun show(event: AgentAttentionEvent) {
        PendingAgentTaskOpen.offer(OpenAgentTaskRequest(event.taskId, event.projectId))
        val subtitle = when (event.kind) {
            AgentAttentionKind.NeedsInput, AgentAttentionKind.Blocked -> "Needs your input"
            AgentAttentionKind.Completed, AgentAttentionKind.Done -> "Agent completed"
            AgentAttentionKind.Failed -> "Agent failed"
            AgentAttentionKind.Working -> "Agent working"
            AgentAttentionKind.Idle -> "Agent idle"
        }
        val os = System.getProperty("os.name").orEmpty()
        when {
            os.contains("mac", true) -> {
                if (MacOsNotificationBridge.isAvailable()) {
                    MacOsNotificationBridge.show(
                        title = "Andy",
                        subtitle = subtitle,
                        body = event.title,
                        taskId = event.taskId,
                        projectId = event.projectId,
                    )
                } else {
                    // Last resort: osascript shows a banner but click opens Script Editor.
                    runCatching {
                        ProcessBuilder(
                            "osascript", "-e",
                            "display notification \"${appleScriptString(event.title)}\" with title \"Andy\" subtitle \"$subtitle\"",
                        ).start()
                    }
                }
            }
            os.contains("linux", true) -> runCatching {
                val command = mutableListOf("notify-send", "Andy", "$subtitle: ${event.title}")
                resolveAppIconFile()?.absolutePath?.let { path ->
                    command += listOf("--icon", path)
                }
                ProcessBuilder(command).start()
            }
            os.contains("windows", true) -> WindowsNotifications.show(subtitle, event.title)
        }
    }
}

private fun appleScriptString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

private fun resolveAppIconFile(): File? {
    val candidates = listOf(
        File("src/desktopMain/resources/icons/andy.png"),
        File("src/commonMain/composeResources/drawable/andy_robot.png"),
    )
    candidates.firstOrNull { it.isFile }?.let { return it }
    val packaged = File(System.getProperty("user.home"), ".andy/icons/andy.png")
    if (packaged.isFile) return packaged
    return runCatching {
        val stream = DesktopOsNotificationService::class.java.getResourceAsStream("/icons/andy.png")
            ?: return@runCatching null
        packaged.parentFile.mkdirs()
        stream.use { input -> packaged.outputStream().use { input.copyTo(it) } }
        packaged.takeIf { it.isFile }
    }.getOrNull()
}

/** Best-effort Windows notification fallback via a dedicated AWT tray icon. */
private object WindowsNotifications {
    private var trayIcon: TrayIcon? = null

    @Synchronized
    fun show(title: String, body: String) {
        if (!SystemTray.isSupported()) return
        val icon = trayIcon ?: runCatching {
            TrayIcon(loadTrayImage(), "Andy").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()?.also { trayIcon = it } ?: return
        runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.INFO) }
    }

    private fun loadTrayImage(): Image {
        resolveAppIconFile()?.let { file ->
            runCatching { ImageIO.read(file) }.getOrNull()?.let { return it }
        }
        return BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    }
}

class DesktopNotificationSoundPlayer : NotificationSoundPlayer {
    private val lock = Any()
    private var generation = 0L
    private var activeClip: Clip? = null

    override fun play(soundId: String) {
        val id = AgentNotificationSound.entries.firstOrNull { it.id == soundId }?.id ?: AgentNotificationSound.Chime.id
        val playGeneration = synchronized(lock) {
            generation += 1
            activeClip?.close()
            activeClip = null
            generation
        }
        thread(name = "andy-notification-sound", isDaemon = true) {
            try {
                // Compose packages file resources below the module-qualified
                // composeResources directory, rather than at the classpath root.
                val path = "/composeResources/app.andy.andy.generated.resources/files/sounds/$id.wav"
                val input = javaClass.getResourceAsStream(path)
                if (input != null) {
                    AudioSystem.getAudioInputStream(BufferedInputStream(input)).use { audio ->
                        AudioSystem.getClip().use { clip ->
                            clip.open(audio)
                            val shouldPlay = synchronized(lock) {
                                if (generation == playGeneration) {
                                    activeClip = clip
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!shouldPlay) return@use
                            clip.start()
                            // A Clip can report !isRunning immediately after start(),
                            // particularly with CoreAudio. Waiting on that flag closes
                            // the clip before the first samples reach the device.
                            while (clip.isOpen && clip.microsecondPosition < clip.microsecondLength) {
                                Thread.sleep(10)
                            }
                        }
                    }
                } else {
                    // Keep packaged builds audible even if a packager omits compose file resources.
                    playFallbackTone(id)
                }
            } catch (_: Exception) {
                // Notifications must never affect the agent runtime.
            } finally {
                synchronized(lock) {
                    if (generation == playGeneration) activeClip = null
                }
            }
        }
    }

    private fun playFallbackTone(id: String) {
        val frequency = when (id) { "ping" -> 880.0; "soft" -> 440.0; else -> 660.0 }
        val rate = 22_050f
        val frames = (rate * .18).toInt()
        val bytes = ByteArray(frames * 2)
        repeat(frames) { index ->
            val envelope = (1.0 - index.toDouble() / frames).coerceAtLeast(0.0)
            val sample = (kotlin.math.sin(2.0 * Math.PI * frequency * index / rate) * envelope * Short.MAX_VALUE * .18).toInt().toShort()
            bytes[index * 2] = sample.toInt().toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        val format = AudioFormat(rate, 16, 1, true, false)
        AudioSystem.getSourceDataLine(format).use { line -> line.open(format); line.start(); line.write(bytes, 0, bytes.size); line.drain() }
    }
}
