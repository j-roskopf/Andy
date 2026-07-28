package app.andy.desktop.service.dhu.capture

import app.andy.desktop.service.dhu.DhuHostEnvironment
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuHostKind
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.image.BufferedImage

/**
 * Windows host via JNA User32/GDI: PrintWindow capture while the DHU HWND is hidden.
 */
internal class WindowsDhuWindowHost : ProcessDhuWindowHost() {
    override val hostKind: DhuHostKind = DhuHostKind.Windows

    override fun environment(): DhuHostEnvironment {
        val ok = runCatching { User32.INSTANCE }.isSuccess
        return DhuHostEnvironment(
            hostKind = hostKind,
            isWindows = true,
            capturePermissionGranted = ok,
            capturePermissionDetail = if (ok) {
                "Win32 window capture APIs available."
            } else {
                "Unable to load Win32 User32 for window capture."
            },
        )
    }

    override fun findWindow(processPid: Long?, titleHint: String): DhuWindowRef? {
        var found: DhuWindowRef? = null
        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            val length = User32.INSTANCE.GetWindowTextLength(hwnd) + 1
            val buffer = CharArray(length.coerceAtLeast(2))
            User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
            val title = Native.toString(buffer)
            if (title.contains(titleHint, ignoreCase = true) ||
                title.contains("desktop-head-unit", ignoreCase = true)
            ) {
                val pidRef = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
                val pid = pidRef.value.toLong()
                if (processPid == null || pid == processPid) {
                    found = DhuWindowRef(
                        id = Pointer.nativeValue(hwnd.pointer).toString(),
                        pid = pid,
                        title = title.ifBlank { titleHint },
                    )
                    return@EnumWindows false
                }
            }
            true
        }, null)
        return found
    }

    override fun hideFromUser(window: DhuWindowRef): Boolean {
        val hwnd = hwndOf(window) ?: return false
        return User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE)
    }

    override fun focus(window: DhuWindowRef): Boolean {
        val hwnd = hwndOf(window) ?: return false
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE)
        return User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    override fun bounds(window: DhuWindowRef): DhuWindowBounds? {
        val hwnd = hwndOf(window) ?: return null
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null
        val width = (rect.right - rect.left).coerceAtLeast(1)
        val height = (rect.bottom - rect.top).coerceAtLeast(1)
        return DhuWindowBounds(rect.left, rect.top, width, height)
    }

    override fun capture(window: DhuWindowRef): DhuCaptureFrame? {
        val hwnd = hwndOf(window) ?: return null
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetClientRect(hwnd, rect)) return null
        val width = (rect.right - rect.left).coerceAtLeast(1)
        val height = (rect.bottom - rect.top).coerceAtLeast(1)
        return printWindow(hwnd, width, height) ?: bounds(window)?.let(::robotCapture)
    }

    override fun sendPointer(window: DhuWindowRef, action: DhuPointerAction): Boolean {
        val hwnd = hwndOf(window) ?: return false
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetClientRect(hwnd, rect)) return false
        val width = (rect.right - rect.left).coerceAtLeast(1)
        val height = (rect.bottom - rect.top).coerceAtLeast(1)
        val (nx, ny) = when (action) {
            is DhuPointerAction.Down -> action.x to action.y
            is DhuPointerAction.Move -> action.x to action.y
            is DhuPointerAction.Up -> action.x to action.y
        }
        val x = (nx.coerceIn(0f, 1f) * (width - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (height - 1)).toInt()
        val lParam = (y shl 16) or (x and 0xffff)
        val msg = when (action) {
            is DhuPointerAction.Down -> WM_LBUTTONDOWN
            is DhuPointerAction.Up -> WM_LBUTTONUP
            is DhuPointerAction.Move -> WM_MOUSEMOVE
        }
        User32.INSTANCE.PostMessage(
            hwnd,
            msg,
            WinDef.WPARAM(if (action is DhuPointerAction.Down) 1 else 0),
            WinDef.LPARAM(lParam.toLong()),
        )
        return true
    }

    override fun sendKey(window: DhuWindowRef, keyCode: Int, typedChar: Char?): Boolean {
        val hwnd = hwndOf(window) ?: return false
        focus(window)
        val vk = typedChar?.let { Character.toUpperCase(it).code } ?: keyCode
        User32.INSTANCE.PostMessage(hwnd, WinUser.WM_KEYDOWN, WinDef.WPARAM(vk.toLong()), WinDef.LPARAM(0))
        User32.INSTANCE.PostMessage(hwnd, WinUser.WM_KEYUP, WinDef.WPARAM(vk.toLong()), WinDef.LPARAM(0))
        return true
    }

    override fun resize(window: DhuWindowRef, width: Int, height: Int): Boolean {
        val hwnd = hwndOf(window) ?: return false
        return User32.INSTANCE.SetWindowPos(
            hwnd,
            null,
            0,
            0,
            width,
            height,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
        )
    }

    override fun teardown(window: DhuWindowRef) = Unit

    private fun hwndOf(window: DhuWindowRef): WinDef.HWND? {
        val value = window.id.toLongOrNull() ?: return null
        return WinDef.HWND(Pointer.createConstant(value))
    }

    private fun printWindow(hwnd: WinDef.HWND, width: Int, height: Int): DhuCaptureFrame? = runCatching {
        val hdcWindow = User32.INSTANCE.GetDC(hwnd)
        val hdcMem = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow)
        val hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height)
        val old = GDI32.INSTANCE.SelectObject(hdcMem, hBitmap)
        val ok = User32.INSTANCE.PrintWindow(hwnd, hdcMem, PW_RENDERFULLCONTENT)
        if (!ok) {
            GDI32.INSTANCE.BitBlt(hdcMem, 0, 0, width, height, hdcWindow, 0, 0, GDI32.SRCCOPY)
        }
        val bmi = WinGDI.BITMAPINFO()
        bmi.bmiHeader.biSize = 40
        bmi.bmiHeader.biWidth = width
        bmi.bmiHeader.biHeight = -height
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB
        val buffer = Memory((width * height * 4).toLong())
        GDI32.INSTANCE.GetDIBits(hdcMem, hBitmap, 0, height, buffer, bmi, WinGDI.DIB_RGB_COLORS)
        GDI32.INSTANCE.SelectObject(hdcMem, old)
        GDI32.INSTANCE.DeleteObject(hBitmap)
        GDI32.INSTANCE.DeleteDC(hdcMem)
        User32.INSTANCE.ReleaseDC(hwnd, hdcWindow)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var i = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val b = buffer.getByte(i).toInt() and 0xff
                val g = buffer.getByte(i + 1).toInt() and 0xff
                val r = buffer.getByte(i + 2).toInt() and 0xff
                val a = buffer.getByte(i + 3).toInt() and 0xff
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                i += 4
            }
        }
        bufferedImageToFrame(image)
    }.getOrNull()

    companion object {
        private const val WM_MOUSEMOVE = 0x0200
        private const val WM_LBUTTONDOWN = 0x0201
        private const val WM_LBUTTONUP = 0x0202
        private const val PW_RENDERFULLCONTENT = 0x00000002
    }
}
