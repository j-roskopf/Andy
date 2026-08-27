package app.andy.ui.controls

import app.andy.domain.parseGpxTrack
import app.andy.domain.parseKmlLineString
import app.andy.domain.parseSensorStatus
import app.andy.domain.parseThermalStatus
import app.andy.model.AndroidDevice
import app.andy.model.EmulatorSensor
import app.andy.model.GeoFix
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorStateSupportTest {
    @Test
    fun geoFixSendsLongitudeBeforeLatitude() = runBlocking {
        val fake = RecordingDeviceService()
        fake.sendGeoFix("emu", GeoFix(latitude = 37.7749, longitude = -122.4194, altitudeMeters = 10.0))
        assertEquals(
            listOf("geo", "fix", "-122.4194", "37.7749", "10"),
            fake.emuCommands.single(),
        )
    }

    @Test
    fun sensorSetJoinsAxesWithColons() = runBlocking {
        val fake = RecordingDeviceService()
        fake.setSensor("emu", EmulatorSensor.Accelerometer, listOf(0f, 9.81f, 0f))
        assertEquals(
            listOf("sensor", "set", "acceleration", "0:9.81:0"),
            fake.emuCommands.single(),
        )
    }

    @Test
    fun smsPassesMessageAsSingleTrailingToken() = runBlocking {
        val fake = RecordingDeviceService()
        fake.sendSms("emu", "5550100", "hello world café")
        assertEquals(
            listOf("sms", "send", "5550100", "hello world café"),
            fake.emuCommands.single(),
        )
    }

    @Test
    fun parseSensorStatusReadsColonAxes() {
        val map = parseSensorStatus(
            """
            acceleration = 0:0:9.81
            light: 120
            OK
            """.trimIndent(),
        )
        assertEquals(listOf(0f, 0f, 9.81f), map["acceleration"])
        assertEquals(listOf(120f), map["light"])
    }

    @Test
    fun parseThermalStatusReadsCmdOutput() {
        assertEquals("Moderate", parseThermalStatus("Thermal Status: 2"))
        assertEquals("None", parseThermalStatus("Current thermal status: 0"))
    }

    @Test
    fun parseGpxTrackReadsTrkpt() {
        val xml = """
            <gpx><trk><trkseg>
              <trkpt lat="37.77" lon="-122.42"><ele>10</ele></trkpt>
              <trkpt lat="37.78" lon="-122.41"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val points = parseGpxTrack(xml)
        assertEquals(2, points.size)
        assertEquals(37.77, points[0].latitude)
        assertEquals(-122.42, points[0].longitude)
        assertEquals(10.0, points[0].altitudeMeters)
    }

    @Test
    fun parseKmlLineStringReadsLonLatAlt() {
        val xml = """
            <kml><coordinates>
              -122.42,37.77,10
              -122.41,37.78
            </coordinates></kml>
        """.trimIndent()
        val points = parseKmlLineString(xml)
        assertEquals(2, points.size)
        assertEquals(-122.42, points[0].longitude)
        assertEquals(37.77, points[0].latitude)
        assertEquals(10.0, points[0].altitudeMeters)
    }

    @Test
    fun formatGeoCoordinateAvoidsCommaDecimals() {
        assertTrue(!formatGeoCoordinate(37.5).contains(','))
        assertEquals("37.5", formatGeoCoordinate(37.5))
    }

    private class RecordingDeviceService : DeviceService {
        val emuCommands = mutableListOf<List<String>>()

        override suspend fun discoverSdk(): SdkDiscovery =
            SdkDiscovery(null, null, null, null, null, emptyList())
        override suspend fun listDevices(): List<AndroidDevice> = emptyList()
        override suspend fun shell(serial: String, command: List<String>): CommandResult =
            CommandResult.success("OK\n")
        override suspend fun emu(serial: String, command: List<String>): CommandResult {
            emuCommands += command
            return CommandResult.success("OK\n")
        }
        override suspend fun pair(host: String, port: Int, code: String): CommandResult =
            CommandResult.success("ok")
        override suspend fun connect(host: String, port: Int): CommandResult = CommandResult.success("ok")
        override suspend fun disconnect(serial: String): CommandResult = CommandResult.success("ok")
        override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
        override suspend fun mdnsAvailable(): Boolean = false
        override suspend fun generatePairingQr(content: String): ByteArray? = null
    }
}
