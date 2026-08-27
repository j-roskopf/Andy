package app.andy.desktop.service.dhu.capture

import app.andy.desktop.service.dhu.DhuHostEnvironment
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuHostKind
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * macOS host for the separate-window DHU flow: locate / focus the native
 * desktop-head-unit window. Capture and pointer forwarding are unused.
 */
internal class MacOsDhuWindowHost : ProcessDhuWindowHost() {
    override val hostKind: DhuHostKind = DhuHostKind.MacOs

    private var parkedBounds: DhuWindowBounds? = null
    /** True while a pointer gesture is staged on-screen for Robot injection. */
    private var gestureActive = false
    private var stagedChromeTop = 28
    private val stageOriginX = 64
    private val stageOriginY = 64

    override fun environment(): DhuHostEnvironment =
        DhuHostEnvironment(
            hostKind = hostKind,
            isWindows = false,
            // Embedding/capture is disabled; never probe Screen Recording via screencapture.
            capturePermissionGranted = true,
            capturePermissionDetail = "Separate desktop-head-unit window (not embedded in Andy)",
        )

    override fun findWindow(processPid: Long?, titleHint: String): DhuWindowRef? {
        // Prefer CoreGraphics by PID — SDL/DHU titles vary and System Events often misses
        // processes launched from Andy's JVM as "background only".
        findViaWindowList(processPid, titleHint)?.let { return it }

        if (processPid == null) return null
        val script = """
            tell application "System Events"
              set procs to every process whose unix id is $processPid
              if (count of procs) is 0 then return ""
              set p to item 1 of procs
              try
                set w to window 1 of p
                set t to name of w as text
                return t & "\t" & (unix id of p as text)
              end try
              return ""
            end tell
            """.trimIndent()
        val out = osascript(script)?.trim().orEmpty()
        if (out.isBlank()) return null
        val parts = out.split('\t')
        val title = parts.getOrNull(0).orEmpty().ifBlank { titleHint }
        val pid = parts.getOrNull(1)?.toLongOrNull() ?: processPid
        val cgId = cgWindowId(pid, title) ?: return DhuWindowRef(id = "pid:$pid", pid = pid, title = title)
        return DhuWindowRef(id = cgId, pid = pid, title = title)
    }

    override fun hideFromUser(window: DhuWindowRef): Boolean {
        val pid = window.pid ?: return false
        // Remember real size before parking so input chrome offset stays accurate.
        val live = readWindowBounds(pid, window.title)
        val ok = setWindowPosition(pid, -4000, -4000)
        if (ok) {
            parkedBounds = DhuWindowBounds(
                x = -4000,
                y = -4000,
                width = live?.width ?: DhuFixedConfig.Width,
                height = live?.height ?: (DhuFixedConfig.Height + 28),
            )
            gestureActive = false
        }
        return ok
    }

    override fun focus(window: DhuWindowRef): Boolean {
        val pid = window.pid ?: return false
        return setFrontmost(pid)
    }

    override fun bounds(window: DhuWindowRef): DhuWindowBounds? {
        if (!gestureActive) {
            parkedBounds?.let { return it }
        }
        val pid = window.pid ?: return null
        return readWindowBounds(pid, window.title)
            ?: DhuWindowBounds(0, 0, DhuFixedConfig.Width, DhuFixedConfig.Height + 28)
    }

    override fun capture(window: DhuWindowRef): DhuCaptureFrame? {
        var cgId = window.id.toLongOrNull()
        if (cgId == null) {
            // Resolve a real CGWindowID if we only had a pid: handle.
            cgId = cgWindowId(window.pid, window.title)?.toLongOrNull()
        }
        if (cgId != null) {
            val tmp = File.createTempFile("andy-dhu-", ".png")
            try {
                val code = ProcessBuilder("screencapture", "-x", "-l", cgId.toString(), tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (code == 0 && tmp.isFile && tmp.length() > 0) {
                    val image = ImageIO.read(tmp) ?: return null
                    // Reject empty/near-black permission placeholders when possible by size.
                    if (image.width >= 2 && image.height >= 2) {
                        return bufferedImageToFrame(image)
                    }
                }
            } finally {
                tmp.delete()
            }
        }
        val b = bounds(window) ?: return null
        return robotCapture(b)
    }

    override fun sendPointer(window: DhuWindowRef, action: DhuPointerAction): Boolean {
        val (nx, ny) = when (action) {
            is DhuPointerAction.Down -> action.x to action.y
            is DhuPointerAction.Move -> action.x to action.y
            is DhuPointerAction.Up -> action.x to action.y
        }
        return when (action) {
            is DhuPointerAction.Down -> {
                if (!ensureInputStaging(window)) return false
                robotClickAtContent(nx, ny, press = true, release = false)
            }
            is DhuPointerAction.Move -> {
                if (!gestureActive && !ensureInputStaging(window)) return false
                robotClickAtContent(nx, ny, press = false, release = false)
            }
            is DhuPointerAction.Up -> {
                if (!gestureActive && !ensureInputStaging(window)) return false
                val ok = robotClickAtContent(nx, ny, press = false, release = true)
                endInputStaging(window)
                ok
            }
        }
    }

    override fun sendKey(window: DhuWindowRef, keyCode: Int, typedChar: Char?): Boolean {
        if (!ensureInputStaging(window)) return false
        val ok = robotKey(keyCode, typedChar)
        endInputStaging(window)
        return ok
    }

    override fun resize(window: DhuWindowRef, width: Int, height: Int): Boolean {
        // Avoid OS-level resize — it breaks the DHU GL/video stream on some phones.
        return false
    }

    override fun teardown(window: DhuWindowRef) {
        if (gestureActive) {
            endInputStaging(window)
        }
        parkedBounds = null
        gestureActive = false
    }

    /**
     * Briefly bring the parked DHU window on-screen (without resizing) so AWT Robot /
     * Accessibility can deliver mouse events into the SDL surface.
     */
    private fun ensureInputStaging(window: DhuWindowRef): Boolean {
        val pid = window.pid ?: return false
        if (gestureActive) return true
        if (!setWindowPosition(pid, stageOriginX, stageOriginY)) return false
        if (!setFrontmost(pid)) return false
        // Let AppKit/SDL apply the move before we click.
        Thread.sleep(40)
        val live = readWindowBounds(pid, window.title)
        val height = live?.height ?: (DhuFixedConfig.Height + 28)
        stagedChromeTop = (height - DhuFixedConfig.Height).coerceIn(0, 160)
        parkedBounds = DhuWindowBounds(
            x = stageOriginX,
            y = stageOriginY,
            width = live?.width ?: DhuFixedConfig.Width,
            height = height,
        )
        gestureActive = true
        return true
    }

    private fun endInputStaging(window: DhuWindowRef) {
        val pid = window.pid ?: run {
            gestureActive = false
            return
        }
        setWindowPosition(pid, -4000, -4000)
        parkedBounds = DhuWindowBounds(
            x = -4000,
            y = -4000,
            width = parkedBounds?.width ?: DhuFixedConfig.Width,
            height = parkedBounds?.height ?: (DhuFixedConfig.Height + stagedChromeTop),
        )
        gestureActive = false
    }

    private fun robotClickAtContent(nx: Float, ny: Float, press: Boolean, release: Boolean): Boolean =
        runCatching {
            val robot = java.awt.Robot()
            val x = stageOriginX + (nx.coerceIn(0f, 1f) * DhuFixedConfig.Width).toInt()
            val y = stageOriginY + stagedChromeTop + (ny.coerceIn(0f, 1f) * DhuFixedConfig.Height).toInt()
            robot.mouseMove(x, y)
            if (press) robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            if (release) robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            true
        }.getOrDefault(false)

    private fun setWindowPosition(pid: Long, x: Int, y: Int): Boolean =
        osascript(
            """
            tell application "System Events"
              set procs to every process whose unix id is $pid
              if (count of procs) is 0 then return "missing"
              set p to item 1 of procs
              repeat with w in (every window of p)
                try
                  set position of w to {$x, $y}
                end try
              end repeat
              return "ok"
            end tell
            """.trimIndent(),
        )?.contains("ok") == true

    private fun setFrontmost(pid: Long): Boolean =
        osascript(
            """
            tell application "System Events"
              set procs to every process whose unix id is $pid
              if (count of procs) is 0 then return "missing"
              set frontmost of item 1 of procs to true
              return "ok"
            end tell
            """.trimIndent(),
        )?.contains("ok") == true

    private fun readWindowBounds(pid: Long, title: String): DhuWindowBounds? {
        val escaped = title.replace("\\", "\\\\").replace("\"", "\\\"")
        val out = osascript(
            """
            tell application "System Events"
              set procs to every process whose unix id is $pid
              if (count of procs) is 0 then return ""
              set p to item 1 of procs
              set w to missing value
              repeat with candidate in (every window of p)
                try
                  set t to name of candidate as text
                  if t contains "$escaped" or t contains "${DhuFixedConfig.WindowTitleHint}" or t contains "Android Auto" then
                    set w to candidate
                    exit repeat
                  end if
                end try
              end repeat
              if w is missing value then
                try
                  set w to window 1 of p
                end try
              end if
              if w is missing value then return ""
              set pos to position of w
              set sz to size of w
              return ((item 1 of pos) as text) & "," & ((item 2 of pos) as text) & "," & ((item 1 of sz) as text) & "," & ((item 2 of sz) as text)
            end tell
            """.trimIndent(),
        )?.trim().orEmpty()
        val parts = out.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        return DhuWindowBounds(parts[0], parts[1], parts[2].coerceAtLeast(1), parts[3].coerceAtLeast(1))
    }

    private fun osascript(script: String): String? =
        runCapture(listOf("osascript", "-e", script))

    private fun cgWindowId(pid: Long?, title: String): String? {
        if (pid == null) return null
        val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
        val list = runCapture(
            listOf(
                "/usr/bin/swift",
                "-e",
                """
                import Cocoa
                guard let info = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as? [[String: Any]] else { exit(1) }
                var fallback: Int? = nil
                for w in info {
                  let owner = w[kCGWindowOwnerPID as String] as? pid_t
                  if owner != $pid { continue }
                  let name = (w[kCGWindowName as String] as? String) ?? ""
                  let layer = w[kCGWindowLayer as String] as? Int ?? -1
                  let alpha = w[kCGWindowAlpha as String] as? Double ?? 1.0
                  let bounds = w[kCGWindowBounds as String] as? [String: Any]
                  let width = (bounds?["Width"] as? NSNumber)?.intValue ?? 0
                  let height = (bounds?["Height"] as? NSNumber)?.intValue ?? 0
                  guard let id = w[kCGWindowNumber as String] as? Int else { continue }
                  if layer == 0 && alpha > 0.01 && width >= 100 && height >= 80 {
                    let hint = "$escapedTitle"
                    if hint.isEmpty ||
                        name.localizedCaseInsensitiveContains(hint) ||
                        name.localizedCaseInsensitiveContains("Desktop Head Unit") ||
                        name.localizedCaseInsensitiveContains("Android Auto") ||
                        name.isEmpty {
                      print(id)
                      exit(0)
                    }
                    if fallback == nil { fallback = id }
                  } else if fallback == nil && width >= 2 && height >= 2 {
                    fallback = id
                  }
                }
                if let fallback { print(fallback) }
                """.trimIndent(),
            ),
            timeoutSeconds = 8,
        )?.trim()
        return list?.lineSequence()?.firstOrNull { it.toLongOrNull() != null }
    }

    private fun findViaWindowList(processPid: Long?, titleHint: String): DhuWindowRef? {
        val id = cgWindowId(processPid, titleHint) ?: return null
        return DhuWindowRef(id = id, pid = processPid, title = titleHint)
    }
}
