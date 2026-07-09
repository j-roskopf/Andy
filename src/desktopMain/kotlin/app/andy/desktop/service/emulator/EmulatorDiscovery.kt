package app.andy.desktop.service.emulator

import java.io.File

internal fun String.isEmulatorSerial(): Boolean = startsWith("emulator-")

internal fun String.emulatorConsolePort(): Int? = removePrefix("emulator-").toIntOrNull()

internal fun readEmulatorGrpcToken(grpcPort: Int): String? {
    return emulatorGrpcDiscoveryFiles()
        .asSequence()
        .mapNotNull { file -> loadEmulatorGrpcDiscovery(file) }
        .firstOrNull { discovery -> discovery.port == grpcPort }
        ?.token
}

internal data class EmulatorGrpcDiscovery(val port: Int?, val token: String?)

internal fun emulatorGrpcDiscoveryFiles(): List<File> {
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

internal fun loadEmulatorGrpcDiscovery(file: File): EmulatorGrpcDiscovery? {
    val entries = runCatching {
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
    }.getOrNull() ?: return null
    val port = entries["grpc.port"]?.toIntOrNull()
    val token = entries["grpc.token"]?.takeIf { it.isNotBlank() }
    return EmulatorGrpcDiscovery(port = port, token = token)
}
