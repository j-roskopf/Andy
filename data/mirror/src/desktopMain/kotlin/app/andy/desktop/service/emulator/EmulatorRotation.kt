package app.andy.desktop.service.emulator

import app.andy.domain.isEmulatorSerial
import app.andy.service.CommandResult

suspend fun applyEmulatorGrpcDisplayRotation(
    serial: String,
    quarterTurn: Int,
): CommandResult {
    if (!serial.isEmulatorSerial()) return CommandResult.failure("Not an emulator")
    val discovery = emulatorGrpcDiscoveryFor(serial)
        ?: return CommandResult.failure("Emulator gRPC discovery not found")
    val port = discovery.port ?: return CommandResult.failure("Emulator gRPC port not found")
    val client = EmulatorGrpcClient(
        host = "127.0.0.1",
        port = port,
        token = discovery.token,
        initialDisplaySize = null,
    )
    return try {
        client.setPhysicalRotation(quarterTurn)
    } finally {
        client.close()
    }
}

suspend fun readEmulatorGrpcDisplayRotation(serial: String): Int? {
    if (!serial.isEmulatorSerial()) return null
    val discovery = emulatorGrpcDiscoveryFor(serial) ?: return null
    val port = discovery.port ?: return null
    val client = EmulatorGrpcClient(
        host = "127.0.0.1",
        port = port,
        token = discovery.token,
        initialDisplaySize = null,
    )
    return try {
        client.getPhysicalRotation()
    } finally {
        client.close()
    }
}
