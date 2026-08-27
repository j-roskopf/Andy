package app.andy.desktop.service.emulator

import app.andy.domain.isEmulatorSerial
import java.io.File

fun String.emulatorConsolePort(): Int? = removePrefix("emulator-").toIntOrNull()

fun readEmulatorGrpcToken(grpcPort: Int): String? {
    return emulatorGrpcDiscoveryFiles()
        .asSequence()
        .mapNotNull { file -> loadEmulatorGrpcDiscovery(file) }
        .firstOrNull { discovery -> discovery.port == grpcPort }
        ?.token
}

data class EmulatorGrpcDiscovery(val port: Int?, val token: String?)

fun emulatorGrpcDiscoveryFiles(): List<File> {
    val home = File(System.getProperty("user.home"))
    val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let(::File)
    val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
    val roots = listOfNotNull(
        File(home, "Library/Caches/TemporaryItems/avd/running"),
        tmpDir?.resolve("avd/running"),
        xdgRuntime?.resolve("avd/running"),
        File(System.getProperty("java.io.tmpdir"), "avd/running"),
        File("/tmp/android-${System.getProperty("user.name")}/avd/running"),
    ).distinctBy { it.absolutePath }
    return roots.flatMap { root ->
        root.listFiles { file -> file.isFile && file.name.startsWith("pid_") && file.name.endsWith(".ini") }
            ?.toList()
            .orEmpty()
    }
}

fun emulatorGrpcDiscoveryFor(serial: String): EmulatorGrpcDiscovery? {
    val consolePort = serial.emulatorConsolePort()?.toString() ?: return null
    val discoveries = emulatorGrpcDiscoveryFiles().mapNotNull { file ->
        val entries = loadEmulatorGrpcDiscoveryEntries(file) ?: return@mapNotNull null
        val discovery = loadEmulatorGrpcDiscovery(file) ?: return@mapNotNull null
        if (discovery.port == null) return@mapNotNull null
        val matches = entries["port.serial"] == consolePort ||
            entries["adb.port"] == consolePort ||
            entries["port.adb"] == consolePort
        if (matches) discovery else null
    }
    if (discoveries.size == 1) return discoveries.first()
    return discoveries.firstOrNull()
}

fun loadEmulatorGrpcDiscoveryEntries(file: File): Map<String, String>? =
    runCatching {
        file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || "=" !in trimmed) {
                    null
                } else {
                    trimmed.substringBefore("=").trim() to trimmed.substringAfter("=").trim()
                }
            }
            .toMap()
    }.getOrNull()

fun loadEmulatorGrpcDiscovery(file: File): EmulatorGrpcDiscovery? {
    val entries = loadEmulatorGrpcDiscoveryEntries(file) ?: return null
    val port = entries["grpc.port"]?.toIntOrNull()
    val token = entries["grpc.token"]?.takeIf { it.isNotBlank() }
    return EmulatorGrpcDiscovery(port = port, token = token)
}
