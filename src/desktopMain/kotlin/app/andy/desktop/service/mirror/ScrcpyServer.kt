package app.andy.desktop.service.mirror

import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object ScrcpyServerLocator {
    fun find(): File? {
        val envPath = System.getenv("SCRCPY_SERVER_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
        if (envPath != null && envPath.isFile) return envPath

        // Mirroring must not depend on a developer's Homebrew or application install. The
        // server is copied from Andy's own resources, and an explicit environment override is
        // reserved for local protocol/codec development.
        return bundledServer()
    }

    private fun bundledServer(): File? {
        val target = File(System.getProperty("user.home"), ".andy/scrcpy/andy-scrcpy-server-v4")
        // Prefer the pinned release binary under scrcpy/; andy-mirror/android is a legacy alias.
        val resource = javaClass.classLoader.getResourceAsStream("scrcpy/scrcpy-server")
            ?: javaClass.classLoader.getResourceAsStream("andy-mirror/android/scrcpy-server")
            ?: return null
        target.parentFile.mkdirs()
        try {
            resource.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setReadable(true, false)
        } catch (_: Exception) {
            if (!target.isFile || target.length() == 0L) return null
        }
        return target.takeIf { it.isFile && it.length() > 0 }
    }
}

internal object ScrcpyControlMessage {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TEXT = 1
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val POINTER_ID_GENERIC_FINGER = -2L

    fun serialize(input: MirrorInput, frame: MirrorFrame): List<ByteArray> {
        return when (input) {
            is MirrorInput.Touch -> listOf(
                touch(
                    action = when (input.action) {
                        MirrorTouchAction.Down -> ACTION_DOWN
                        MirrorTouchAction.Move -> ACTION_MOVE
                        MirrorTouchAction.Up -> ACTION_UP
                    },
                    x = input.x,
                    y = input.y,
                    frame = frame,
                    pressure = if (input.action == MirrorTouchAction.Up) 0f else 1f,
                ),
            )
            is MirrorInput.Tap -> listOf(
                touch(ACTION_DOWN, input.x, input.y, frame, pressure = 1f),
                touch(ACTION_UP, input.x, input.y, frame, pressure = 0f),
            )
            is MirrorInput.Swipe -> buildList {
                add(touch(ACTION_DOWN, input.startX, input.startY, frame, pressure = 1f))
                add(touch(ACTION_MOVE, input.endX, input.endY, frame, pressure = 1f))
                add(touch(ACTION_UP, input.endX, input.endY, frame, pressure = 0f))
            }
            is MirrorInput.Key -> keyPress(input.keyCode)
            is MirrorInput.Text -> listOf(text(input.value))
            MirrorInput.Back -> keyPress(4)
            MirrorInput.Home -> keyPress(3)
            MirrorInput.Recents -> keyPress(187)
            MirrorInput.Power -> keyPress(26)
        }
    }

    private fun keyPress(keyCode: Int): List<ByteArray> = listOf(
        key(ACTION_DOWN, keyCode),
        key(ACTION_UP, keyCode),
    )

    private fun key(action: Int, keyCode: Int): ByteArray {
        val bytes = ByteArray(14)
        bytes[0] = TYPE_INJECT_KEYCODE.toByte()
        bytes[1] = action.toByte()
        bytes.writeInt(2, keyCode)
        bytes.writeInt(6, 0)
        bytes.writeInt(10, 0)
        return bytes
    }

    private fun text(value: String): ByteArray {
        val payload = value.encodeToByteArray().take(300).toByteArray()
        val bytes = ByteArray(1 + 4 + payload.size)
        bytes[0] = TYPE_INJECT_TEXT.toByte()
        bytes.writeInt(1, payload.size)
        payload.copyInto(bytes, destinationOffset = 5)
        return bytes
    }

    private fun touch(action: Int, x: Int, y: Int, frame: MirrorFrame, pressure: Float): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val bytes = ByteArray(32)
        bytes[0] = TYPE_INJECT_TOUCH_EVENT.toByte()
        bytes[1] = action.toByte()
        bytes.writeLong(2, POINTER_ID_GENERIC_FINGER)
        bytes.writeInt(10, x.coerceIn(0, width - 1))
        bytes.writeInt(14, y.coerceIn(0, height - 1))
        bytes.writeShort(18, width)
        bytes.writeShort(20, height)
        bytes.writeShort(22, (pressure.coerceIn(0f, 1f) * 0xffff).toInt())
        bytes.writeInt(24, 0)
        bytes.writeInt(28, 0)
        return bytes
    }

    private fun ByteArray.writeShort(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = ((value ushr 24) and 0xff).toByte()
        this[offset + 1] = ((value ushr 16) and 0xff).toByte()
        this[offset + 2] = ((value ushr 8) and 0xff).toByte()
        this[offset + 3] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeLong(offset: Int, value: Long) {
        this[offset] = ((value ushr 56) and 0xff).toByte()
        this[offset + 1] = ((value ushr 48) and 0xff).toByte()
        this[offset + 2] = ((value ushr 40) and 0xff).toByte()
        this[offset + 3] = ((value ushr 32) and 0xff).toByte()
        this[offset + 4] = ((value ushr 24) and 0xff).toByte()
        this[offset + 5] = ((value ushr 16) and 0xff).toByte()
        this[offset + 6] = ((value ushr 8) and 0xff).toByte()
        this[offset + 7] = (value and 0xff).toByte()
    }
}
