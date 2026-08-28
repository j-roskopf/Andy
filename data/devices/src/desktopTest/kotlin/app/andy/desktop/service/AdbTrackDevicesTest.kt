package app.andy.desktop.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.EOFException

class AdbTrackDevicesTest {
    @Test
    fun writeServiceRequestUsesHexLengthPrefix() {
        val out = ByteArrayOutputStream()
        AdbTrackDevices.writeServiceRequest(out, "host:track-devices-l")
        assertEquals("0014host:track-devices-l", out.toString(Charsets.UTF_8))
    }

    @Test
    fun readLengthPrefixedReadsPayload() {
        val payload = "SERIAL\tdevice product:x model:Pixel"
        val framed = "%04x%s".format(payload.length, payload)
        val input = ByteArrayInputStream(framed.encodeToByteArray())
        assertEquals(payload, AdbTrackDevices.readLengthPrefixed(input))
    }

    @Test
    fun readLengthPrefixedEmptyPayloadMeansNoDevices() {
        val input = ByteArrayInputStream("0000".encodeToByteArray())
        assertEquals("", AdbTrackDevices.readLengthPrefixed(input))
    }

    @Test
    fun readExactThrowsOnEof() {
        val input = ByteArrayInputStream("OK".encodeToByteArray())
        assertFailsWith<EOFException> {
            AdbTrackDevices.readExact(input, 4)
        }
    }

    @Test
    fun resolveServerPortPrefersEnv() {
        assertEquals(12345, AdbTrackDevices.resolveServerPort(envPort = "12345", propertyPort = "5037"))
        assertEquals(5037, AdbTrackDevices.resolveServerPort(envPort = null, propertyPort = null))
    }
}
