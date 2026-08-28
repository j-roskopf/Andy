package app.andy.desktop.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Speaks ADB's `host:track-devices-l` wire protocol so empty device lists are
 * detectable (the CLI `adb track-devices` prints nothing for a zero-length update).
 */
object AdbTrackDevices {
    const val DefaultHost = "127.0.0.1"
    const val DefaultPort = 5037
    const val TrackDevicesLong = "host:track-devices-l"

    fun resolveServerPort(
        envPort: String? = System.getenv("ANDROID_ADB_SERVER_PORT"),
        propertyPort: String? = System.getProperty("android.adb.server.port"),
    ): Int = envPort?.toIntOrNull()
        ?: propertyPort?.toIntOrNull()
        ?: DefaultPort

    /**
     * Connects to the ADB server and invokes [onUpdate] with each raw device-list
     * payload (same line format as `adb devices -l`, without the header). Empty
     * payloads mean "no devices".
     */
    suspend fun collectUpdates(
        host: String = DefaultHost,
        port: Int = DefaultPort,
        service: String = TrackDevicesLong,
        connectTimeoutMs: Int = 3_000,
        onUpdate: suspend (payload: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            writeServiceRequest(output, service)
            when (val status = readExact(input, 4).decodeToString()) {
                "OKAY" -> Unit
                "FAIL" -> {
                    val message = readLengthPrefixed(input)
                    error("adb $service failed: ${message.ifBlank { "unknown error" }}")
                }
                else -> error("adb $service returned unexpected status: $status")
            }
            while (coroutineContext.isActive) {
                coroutineContext.ensureActive()
                val payload = readLengthPrefixed(input)
                onUpdate(payload)
            }
        }
    }

    internal fun writeServiceRequest(output: OutputStream, service: String) {
        val encoded = service.encodeToByteArray()
        val header = "%04x".format(encoded.size).encodeToByteArray()
        output.write(header)
        output.write(encoded)
        output.flush()
    }

    internal fun readLengthPrefixed(input: InputStream): String {
        val lengthHex = readExact(input, 4).decodeToString()
        val length = lengthHex.toIntOrNull(16)
            ?: error("Invalid ADB length prefix: '$lengthHex'")
        if (length == 0) return ""
        return readExact(input, length).decodeToString()
    }

    internal fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("ADB connection closed after $offset of $count bytes")
            offset += read
        }
        return buffer
    }
}
