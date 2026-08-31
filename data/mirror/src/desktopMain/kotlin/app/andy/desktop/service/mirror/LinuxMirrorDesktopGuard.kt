package app.andy.desktop.service.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** KWin on Wayland exposes a single X11 root desktop; track Plasma desktops over D-Bus instead. */
internal object LinuxKdeDesktop {
    private val enabled: Boolean by lazy {
        System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true) &&
            System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", ignoreCase = true)
    }

    fun isActive(): Boolean = enabled

    fun currentIndex(): Int? {
        if (!enabled) return null
        return runCatching {
            val proc = ProcessBuilder(
                "busctl",
                "--user",
                "call",
                "org.kde.KWin",
                "/KWin",
                "org.kde.KWin",
                "currentDesktop",
            ).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread({
                runCatching {
                    proc.inputStream.bufferedReader().use { stream -> out.append(stream.readText()) }
                }
            }, "andy-kde-desktop-reader").apply { isDaemon = true }
            reader.start()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
                reader.join(1_000)
                return@runCatching null
            }
            reader.join(1_000)
            check(proc.exitValue() == 0) { out }
            out.toString().substringAfter("i ").trim().toInt()
        }.getOrNull()
    }
}

/**
 * Hide override-redirect GPU overlays when leaving the Plasma desktop where Live was
 * showing; restore only when that desktop is current again and a Live host is attached.
 *
 * liveDesktop is only updated while stable on a desktop — never during the change
 * detection tick (that previously set liveDesktop to the destination and immediately
 * resumed, remounting the floating overlay on the wrong desktop).
 */
@Composable
internal fun LinuxMirrorDesktopVisibilityEffect(enabled: Boolean) {
    if (!enabled || !LinuxKdeDesktop.isActive() || !GpuMirrorJni.isAvailable()) return
    LaunchedEffect(Unit) {
        var lastDesktop: Int? = withContext(Dispatchers.IO) { LinuxKdeDesktop.currentIndex() }
        var liveDesktop: Int? = lastDesktop
        var suppressed = false
        while (isActive) {
            val desktop = withContext(Dispatchers.IO) { LinuxKdeDesktop.currentIndex() }
            val hostShowing = GpuMirrorHostRegistry.anyHostShowing()
            val desktopChanged = desktop != null && lastDesktop != null && desktop != lastDesktop
            if (desktopChanged) {
                GpuMirrorJni.suppressForDesktopSwitch()
                suppressed = true
            } else if (hostShowing && !suppressed && desktop != null) {
                liveDesktop = desktop
            }
            if (suppressed && hostShowing && desktop != null && desktop == liveDesktop) {
                GpuMirrorJni.resumeAfterDesktopSwitch()
                GpuMirrorHostRegistry.attachedPresenters().forEach { presenter ->
                    presenter.refreshGeometry()
                    presenter.repaint()
                }
                suppressed = false
            }
            if (!hostShowing) {
                suppressed = false
            }
            if (desktop != null) lastDesktop = desktop
            delay(250)
        }
    }
}
