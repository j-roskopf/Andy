package app.andy.desktop.service

import app.andy.model.AgentNotificationSound
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import app.andy.service.NotificationSoundPlayer
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OsNotificationService
import java.io.BufferedInputStream
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlin.concurrent.thread

object PendingAgentTaskOpen {
    @Volatile var request: OpenAgentTaskRequest? = null
    fun consume(): OpenAgentTaskRequest? = request.also { request = null }
}

class DesktopOsNotificationService : OsNotificationService {
    override fun show(event: AgentAttentionEvent) {
        PendingAgentTaskOpen.request = OpenAgentTaskRequest(event.taskId, event.projectId)
        val subtitle = when (event.kind) {
            AgentAttentionKind.NeedsInput -> "Needs your input"
            AgentAttentionKind.Completed -> "Agent completed"
            AgentAttentionKind.Failed -> "Agent failed"
        }
        val os = System.getProperty("os.name").orEmpty()
        when {
            os.contains("mac", true) -> runCatching {
                ProcessBuilder(
                    "osascript", "-e",
                    "display notification \"${appleScriptString(event.title)}\" with title \"Andy\" subtitle \"$subtitle\"",
                ).start()
            }
            os.contains("linux", true) -> runCatching {
                ProcessBuilder("notify-send", "Andy", "$subtitle: ${event.title}").start()
            }
            os.contains("windows", true) -> WindowsNotifications.show(subtitle, event.title)
        }
    }
}

private fun appleScriptString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

/** Best-effort Windows notification fallback via a dedicated AWT tray icon. */
private object WindowsNotifications {
    private var trayIcon: TrayIcon? = null

    @Synchronized
    fun show(title: String, body: String) {
        if (!SystemTray.isSupported()) return
        val icon = trayIcon ?: runCatching {
            TrayIcon(transparentImage(), "Andy").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()?.also { trayIcon = it } ?: return
        runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.INFO) }
    }

    private fun transparentImage(): Image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
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
