package app.andy.desktop.service.mirror

import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrcpyControlMessageTest {
    @Test
    fun shortTextFitsInSingleInjectMessage() {
        val messages = ScrcpyControlMessage.serialize(MirrorInput.Text("hello"), emptyFrame())
        assertEquals(1, messages.size)
        assertEquals(1, messages[0][0].toInt())
        assertEquals(5, readBeInt(messages[0], 1))
        assertEquals("hello", messages[0].copyOfRange(5, 10).decodeToString())
    }

    @Test
    fun longTextIsChunkedUnderInjectLimit() {
        val value = "a".repeat(650)
        val messages = ScrcpyControlMessage.serialize(MirrorInput.Text(value), emptyFrame())
        assertEquals(3, messages.size)
        val rebuilt = messages.joinToString("") { message ->
            val size = readBeInt(message, 1)
            assertTrue(size <= 300)
            message.copyOfRange(5, 5 + size).decodeToString()
        }
        assertEquals(value, rebuilt)
    }

    private fun emptyFrame() = MirrorFrame(
        width = 1080,
        height = 1920,
        argb = IntArray(0),
    )

    private fun readBeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
