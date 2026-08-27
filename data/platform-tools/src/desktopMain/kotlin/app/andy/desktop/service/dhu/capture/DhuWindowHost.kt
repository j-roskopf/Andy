package app.andy.desktop.service.dhu.capture

import app.andy.desktop.service.dhu.DhuHostEnvironment
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuHostKind
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opaque handle to a hosted DHU native window. */
data class DhuWindowRef(
    val id: String,
    val pid: Long?,
    val title: String,
)

data class DhuWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

sealed interface DhuPointerAction {
    data class Down(val x: Float, val y: Float) : DhuPointerAction
    data class Move(val x: Float, val y: Float) : DhuPointerAction
    data class Up(val x: Float, val y: Float) : DhuPointerAction
}

/**
 * Platform adapter: find/hide the DHU window, capture frames, and forward input.
 * Never leaves a detached interactive DHU window as the primary UX.
 */
interface DhuWindowHost {
    val hostKind: DhuHostKind
    fun environment(): DhuHostEnvironment
    fun findWindow(processPid: Long?, titleHint: String = DhuFixedConfig.WindowTitleHint): DhuWindowRef?
    fun hideFromUser(window: DhuWindowRef): Boolean
    fun focus(window: DhuWindowRef): Boolean
    fun bounds(window: DhuWindowRef): DhuWindowBounds?
    fun capture(window: DhuWindowRef): DhuCaptureFrame?
    fun sendPointer(window: DhuWindowRef, action: DhuPointerAction): Boolean
    fun sendKey(window: DhuWindowRef, keyCode: Int, typedChar: Char? = null): Boolean
    fun resize(window: DhuWindowRef, width: Int, height: Int): Boolean
    fun teardown(window: DhuWindowRef)
    /** Launch DHU outside Andy for labeled troubleshooting only. */
    fun launchExternal(executable: String, args: List<String>, workingDir: File): Boolean
}

fun createDhuWindowHost(): DhuWindowHost {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> MacOsDhuWindowHost()
        os.contains("win") -> WindowsDhuWindowHost()
        os.contains("linux") || os.contains("bsd") -> {
            val session = System.getenv("XDG_SESSION_TYPE")?.lowercase().orEmpty()
            val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
            val isWayland = session == "wayland" || !waylandDisplay.isNullOrBlank()
            // Prefer X11 when DISPLAY is set even under XWayland.
            val hasX11 = !System.getenv("DISPLAY").isNullOrBlank()
            when {
                isWayland && !hasX11 -> UnsupportedDhuWindowHost(DhuHostKind.LinuxWayland)
                hasX11 -> X11DhuWindowHost()
                isWayland -> UnsupportedDhuWindowHost(DhuHostKind.LinuxWayland)
                else -> UnsupportedDhuWindowHost(DhuHostKind.Unsupported)
            }
        }
        else -> UnsupportedDhuWindowHost(DhuHostKind.Unsupported)
    }
}

class UnsupportedDhuWindowHost(
    override val hostKind: DhuHostKind,
) : DhuWindowHost {
    override fun environment() = DhuHostEnvironment(
        hostKind = hostKind,
        isWindows = false,
        capturePermissionGranted = false,
        capturePermissionDetail = when (hostKind) {
            DhuHostKind.LinuxWayland -> "Wayland capture is unsupported."
            else -> "Host does not support DHU window capture."
        },
    )

    override fun findWindow(processPid: Long?, titleHint: String): DhuWindowRef? = null
    override fun hideFromUser(window: DhuWindowRef) = false
    override fun focus(window: DhuWindowRef) = false
    override fun bounds(window: DhuWindowRef): DhuWindowBounds? = null
    override fun capture(window: DhuWindowRef): DhuCaptureFrame? = null
    override fun sendPointer(window: DhuWindowRef, action: DhuPointerAction) = false
    override fun sendKey(window: DhuWindowRef, keyCode: Int, typedChar: Char?) = false
    override fun resize(window: DhuWindowRef, width: Int, height: Int) = false
    override fun teardown(window: DhuWindowRef) = Unit
    override fun launchExternal(executable: String, args: List<String>, workingDir: File): Boolean =
        runCatching {
            ProcessBuilder(listOf(executable) + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
}

/** Shared helpers for process-based window tooling and Robot fallbacks. */
internal abstract class ProcessDhuWindowHost : DhuWindowHost {
    protected val frameCounter = AtomicLong(0)

    protected fun runCapture(command: List<String>, timeoutSeconds: Long = 5): String? =
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            output
        }.getOrNull()

    protected fun bufferedImageToFrame(image: java.awt.image.BufferedImage): DhuCaptureFrame {
        val w = image.width.coerceAtLeast(1)
        val h = image.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        return DhuCaptureFrame(w, h, pixels, frameCounter.incrementAndGet())
    }

    protected fun robotCapture(bounds: DhuWindowBounds): DhuCaptureFrame? = runCatching {
        val robot = Robot()
        val screen = Toolkit.getDefaultToolkit().screenSize
        val x = bounds.x.coerceIn(0, screen.width - 1)
        val y = bounds.y.coerceIn(0, screen.height - 1)
        val w = bounds.width.coerceIn(1, screen.width - x)
        val h = bounds.height.coerceIn(1, screen.height - y)
        // Off-screen windows cannot be captured by Robot — callers should prefer window APIs.
        if (bounds.x < -50 || bounds.y < -50) return null
        bufferedImageToFrame(robot.createScreenCapture(Rectangle(x, y, w, h)))
    }.getOrNull()

    protected fun robotPointer(bounds: DhuWindowBounds, action: DhuPointerAction): Boolean = runCatching {
        val robot = Robot()
        val (nx, ny) = when (action) {
            is DhuPointerAction.Down -> action.x to action.y
            is DhuPointerAction.Move -> action.x to action.y
            is DhuPointerAction.Up -> action.x to action.y
        }
        val sx = bounds.x + (nx.coerceIn(0f, 1f) * bounds.width).toInt()
        val sy = bounds.y + (ny.coerceIn(0f, 1f) * bounds.height).toInt()
        robot.mouseMove(sx, sy)
        when (action) {
            is DhuPointerAction.Down -> robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            is DhuPointerAction.Up -> robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            is DhuPointerAction.Move -> Unit
        }
        true
    }.getOrDefault(false)

    protected fun robotKey(keyCode: Int, typedChar: Char?): Boolean = runCatching {
        val robot = Robot()
        if (typedChar != null && !typedChar.isISOControl()) {
            val vk = KeyEvent.getExtendedKeyCodeForChar(typedChar.code)
            if (vk != KeyEvent.VK_UNDEFINED) {
                robot.keyPress(vk)
                robot.keyRelease(vk)
                return@runCatching true
            }
        }
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        true
    }.getOrDefault(false)

    override fun launchExternal(executable: String, args: List<String>, workingDir: File): Boolean =
        runCatching {
            ProcessBuilder(listOf(executable) + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)

    protected fun pointerScreenOk(): Boolean = runCatching {
        MouseInfo.getPointerInfo() != null
    }.getOrDefault(false)
}
