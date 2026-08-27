package app.andy.desktop.service.dhu.capture

import app.andy.desktop.service.dhu.DhuHostEnvironment
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuHostKind
import java.awt.image.BufferedImage
import java.io.File

/**
 * Linux X11 host. Prefers `xdotool`/`import` when present; otherwise reports remediation
 * and falls back to Robot for on-screen windows.
 */
internal class X11DhuWindowHost : ProcessDhuWindowHost() {
    override val hostKind: DhuHostKind = DhuHostKind.LinuxX11

    private var parked: Boolean = false

    override fun environment(): DhuHostEnvironment {
        val display = System.getenv("DISPLAY")
        val hasDisplay = !display.isNullOrBlank()
        val hasXdotool = commandExists("xdotool")
        return DhuHostEnvironment(
            hostKind = hostKind,
            isWindows = false,
            capturePermissionGranted = hasDisplay,
            capturePermissionDetail = when {
                !hasDisplay -> "DISPLAY is unset; X11 capture unavailable."
                hasXdotool -> "X11 display $display with xdotool available."
                else -> "X11 display $display (install xdotool/ImageMagick for more reliable capture)."
            },
        )
    }

    override fun findWindow(processPid: Long?, titleHint: String): DhuWindowRef? {
        if (commandExists("xdotool")) {
            val search = buildList {
                add("xdotool")
                add("search")
                add("--name")
                add(titleHint)
                if (processPid != null) {
                    add("--pid")
                    add(processPid.toString())
                }
            }
            val id = runCapture(search)?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            if (id != null) {
                return DhuWindowRef(id = id, pid = processPid, title = titleHint)
            }
            val alt = runCapture(listOf("xdotool", "search", "--name", "desktop-head-unit"))
                ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            if (alt != null) return DhuWindowRef(id = alt, pid = processPid, title = titleHint)
        }
        if (processPid != null) {
            return DhuWindowRef(id = "pid:$processPid", pid = processPid, title = titleHint)
        }
        return null
    }

    override fun hideFromUser(window: DhuWindowRef): Boolean {
        if (!window.id.startsWith("pid:") && commandExists("xdotool")) {
            // Move off-screen rather than unmap so rendering continues.
            val ok = runCapture(listOf("xdotool", "windowmove", window.id, "--", "-4000", "-4000")) != null
            if (ok) parked = true
            return ok
        }
        return false
    }

    override fun focus(window: DhuWindowRef): Boolean {
        if (!window.id.startsWith("pid:") && commandExists("xdotool")) {
            return runCapture(listOf("xdotool", "windowactivate", "--sync", window.id)) != null
        }
        return false
    }

    override fun bounds(window: DhuWindowRef): DhuWindowBounds? {
        if (parked) {
            return DhuWindowBounds(-4000, -4000, DhuFixedConfig.Width, DhuFixedConfig.Height)
        }
        if (!window.id.startsWith("pid:") && commandExists("xdotool")) {
            val geo = runCapture(listOf("xdotool", "getwindowgeometry", "--shell", window.id)) ?: return null
            var x = 0
            var y = 0
            var w = DhuFixedConfig.Width
            var h = DhuFixedConfig.Height
            geo.lineSequence().forEach { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@forEach
                when (parts[0]) {
                    "X" -> x = parts[1].toIntOrNull() ?: x
                    "Y" -> y = parts[1].toIntOrNull() ?: y
                    "WIDTH" -> w = parts[1].toIntOrNull() ?: w
                    "HEIGHT" -> h = parts[1].toIntOrNull() ?: h
                }
            }
            return DhuWindowBounds(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
        return DhuWindowBounds(0, 0, DhuFixedConfig.Width, DhuFixedConfig.Height)
    }

    override fun capture(window: DhuWindowRef): DhuCaptureFrame? {
        if (!window.id.startsWith("pid:") && commandExists("import")) {
            val tmp = File.createTempFile("andy-dhu-", ".png")
            try {
                val code = ProcessBuilder("import", "-window", window.id, tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (code == 0 && tmp.isFile && tmp.length() > 0) {
                    val image = javax.imageio.ImageIO.read(tmp) ?: return null
                    return bufferedImageToFrame(image)
                }
            } finally {
                tmp.delete()
            }
        }
        val b = bounds(window) ?: return null
        return robotCapture(b)
    }

    override fun sendPointer(window: DhuWindowRef, action: DhuPointerAction): Boolean {
        val b = bounds(window) ?: return false
        if (!window.id.startsWith("pid:") && commandExists("xdotool") && (b.x < -100 || parked)) {
            val (nx, ny) = when (action) {
                is DhuPointerAction.Down -> action.x to action.y
                is DhuPointerAction.Move -> action.x to action.y
                is DhuPointerAction.Up -> action.x to action.y
            }
            val x = (nx.coerceIn(0f, 1f) * DhuFixedConfig.Width).toInt()
            val y = (ny.coerceIn(0f, 1f) * DhuFixedConfig.Height).toInt()
            // Temporarily restore, click in window coords, re-park.
            runCapture(listOf("xdotool", "windowmove", window.id, "40", "40"))
            runCapture(listOf("xdotool", "windowactivate", "--sync", window.id))
            when (action) {
                is DhuPointerAction.Down -> runCapture(listOf("xdotool", "mousemove", "--window", window.id, "$x", "$y", "mousedown", "1"))
                is DhuPointerAction.Up -> runCapture(listOf("xdotool", "mousemove", "--window", window.id, "$x", "$y", "mouseup", "1"))
                is DhuPointerAction.Move -> runCapture(listOf("xdotool", "mousemove", "--window", window.id, "$x", "$y"))
            }
            runCapture(listOf("xdotool", "windowmove", window.id, "--", "-4000", "-4000"))
            return true
        }
        return robotPointer(b, action)
    }

    override fun sendKey(window: DhuWindowRef, keyCode: Int, typedChar: Char?): Boolean {
        focus(window)
        if (!window.id.startsWith("pid:") && commandExists("xdotool") && typedChar != null) {
            return runCapture(listOf("xdotool", "key", "--window", window.id, typedChar.toString())) != null
        }
        return robotKey(keyCode, typedChar)
    }

    override fun resize(window: DhuWindowRef, width: Int, height: Int): Boolean {
        if (!window.id.startsWith("pid:") && commandExists("xdotool")) {
            return runCapture(listOf("xdotool", "windowsize", window.id, width.toString(), height.toString())) != null
        }
        return false
    }

    override fun teardown(window: DhuWindowRef) {
        parked = false
    }

    private fun commandExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(File.pathSeparator).any { dir ->
            val f = File(dir, name)
            f.isFile && f.canExecute()
        }
    }
}
