package app.andy.desktop.service.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrcpyFrameProtocolTest {
    @Test
    fun decodesBigEndianPayloadLengthFromFramedScrcpyPacket() {
        assertEquals(258, scrcpyFramePayloadSize(ByteArray(12).also {
            it[10] = 1
            it[11] = 2
        }))
    }

    @Test
    fun rejectsMalformedOrExcessiveFramedPacketLengths() {
        assertFailsWith<IllegalArgumentException> { scrcpyFramePayloadSize(ByteArray(11)) }
        assertFailsWith<IllegalArgumentException> {
            scrcpyFramePayloadSize(ByteArray(12).also { it[8] = 0x7f })
        }
    }

    @Test
    fun parsesSessionHeadersWithoutTreatingTheirSizeAsPayloadLength() {
        val header = ByteArray(12).also {
            it[0] = 0x80.toByte()
            it[3] = 1
            it[6] = 0x02
            it[7] = 0xd0.toByte() // 720 px wide
            it[10] = 0x05
            it[11] = 0x00 // 1280 px tall
        }

        assertTrue(scrcpyFrameIsSession(header))
        assertEquals(ScrcpySessionFrame(720, 1280, clientResized = true), scrcpySessionFrame(header))
        assertFailsWith<IllegalArgumentException> { scrcpyFramePayloadSize(header) }
        assertFalse(scrcpyFrameIsSession(ByteArray(12)))
    }
}
