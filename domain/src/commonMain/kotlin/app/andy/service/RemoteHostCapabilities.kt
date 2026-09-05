package app.andy.service

/**
 * Probed once per SSH connect — tells the UI whether Screen Sharing / VNC and
 * host screenshots are available without modifying the remote host.
 */
enum class RemoteHostOs {
    Mac,
    Linux,
    Unknown,
}

enum class RemoteScreenAvailability {
    /** VNC server is listening; client can be launched. */
    Available,
    /** OS supports Screen Sharing / VNC but nothing is listening — show enablement steps. */
    NeedsEnabling,
    /** No known VNC path for this OS / no client tools. */
    Unsupported,
}

data class RemoteHostCapabilities(
    val os: RemoteHostOs = RemoteHostOs.Unknown,
    val vncPort: Int = DefaultVncPort,
    val vncServerListening: Boolean = false,
    /** Absolute path or bare name of a screenshot tool on the remote PATH, if any. */
    val screenshotTool: String? = null,
    /** Exact steps to enable screen sharing on the detected OS (never applied automatically). */
    val enablementHint: String? = null,
    /** Local client command that can open a `vnc://` URL, if detected on this GUI host. */
    val localVncClient: String? = null,
) {
    val screenAvailability: RemoteScreenAvailability
        get() = when {
            os == RemoteHostOs.Unknown -> RemoteScreenAvailability.Unsupported
            vncServerListening -> RemoteScreenAvailability.Available
            enablementHint != null -> RemoteScreenAvailability.NeedsEnabling
            else -> RemoteScreenAvailability.Unsupported
        }

    companion object {
        const val DefaultVncPort = 5900
    }
}

/**
 * Pure parsers for remote host capability probe output. Desktop runs the probes
 * over SSH; tests feed fixtures.
 */
object RemoteHostCapabilityProbe {
    fun parseUname(stdout: String): RemoteHostOs {
        val token = stdout.trim().lineSequence().firstOrNull().orEmpty().lowercase()
        return when {
            token.contains("darwin") -> RemoteHostOs.Mac
            token.contains("linux") -> RemoteHostOs.Linux
            else -> RemoteHostOs.Unknown
        }
    }

    /**
     * True when any listen/open signal reports [port] ready.
     *
     * Prefer [netstatOutput] / [connectProbeOutput] over [lsofOutput]: on macOS an
     * unprivileged SSH user often cannot see root/`launchd` Screen Sharing sockets
     * via `lsof`, which produced false "enable Screen Sharing" guidance.
     */
    fun parseVncListening(
        lsofOutput: String,
        port: Int = RemoteHostCapabilities.DefaultVncPort,
        netstatOutput: String = "",
        connectProbeOutput: String = "",
    ): Boolean {
        if (parseLsofListening(lsofOutput, port)) return true
        if (parseNetstatListening(netstatOutput, port)) return true
        if (parseConnectProbeOpen(connectProbeOutput)) return true
        return false
    }

    fun parseLsofListening(lsofOutput: String, port: Int = RemoteHostCapabilities.DefaultVncPort): Boolean {
        val listeners = LocalServerScan.parseLsofTcpListenOutput(lsofOutput)
        if (listeners.any { it.port == port }) return true
        // Also accept one-line `lsof -i` style without -F.
        return lsofOutput.lineSequence().any { line ->
            line.contains(":$port") && line.contains("LISTEN", ignoreCase = true)
        }
    }

    /** `netstat -an` / `ss -ltn` style lines that mention [port] in LISTEN state. */
    fun parseNetstatListening(netstatOutput: String, port: Int = RemoteHostCapabilities.DefaultVncPort): Boolean {
        val patterns = listOf(
            Regex("""\.${port}\b"""),
            Regex(""":${port}\b"""),
            Regex("""\*+\.${port}\b"""),
            Regex("""\[::\]:$port\b"""),
            Regex("""0\.0\.0\.0:$port\b"""),
            Regex("""127\.0\.0\.1:$port\b"""),
        )
        return netstatOutput.lineSequence().any { line ->
            val upper = line.uppercase()
            (upper.contains("LISTEN") || upper.contains("LISTENING")) &&
                patterns.any { it.containsMatchIn(line) }
        }
    }

    /** Marker line from `nc -z` / `/dev/tcp` probe (`ANDY_VNC_OPEN`). */
    fun parseConnectProbeOpen(connectProbeOutput: String): Boolean =
        connectProbeOutput.lineSequence().any { it.trim() == "ANDY_VNC_OPEN" }

    /**
     * True when macOS `launchctl print system/com.apple.screensharing` shows the
     * daemon is configured (loaded), even if `lsof` cannot see the socket.
     */
    fun parseMacScreensharingLaunchd(launchctlOutput: String): Boolean {
        val text = launchctlOutput.trim()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        if (lower.contains("could not find service")) return false
        if (lower.contains("bad request") && !lower.contains("com.apple.screensharing")) return false
        // Disabled override wins when present.
        if (Regex("""disabled\s*=\s*1""").containsMatchIn(lower)) return false
        if (Regex(""""disabled"\s*=>\s*true""").containsMatchIn(lower)) return false
        // Loaded service prints path / state / program.
        return lower.contains("com.apple.screensharing") &&
            (
                lower.contains("state =") ||
                    lower.contains("path =") ||
                    lower.contains("program =") ||
                    lower.contains("pid =")
                )
    }

    /**
     * Picks the first available screenshot binary from `command -v` space-separated output
     * (or one tool per line). Preference: screencapture, grim, scrot, import.
     */
    fun parseScreenshotTool(commandVOutput: String): String? {
        val found = commandVOutput.lineSequence()
            .flatMap { it.trim().split(Regex("""\s+""")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return HostScreenshotCommand.resolveTool(found)
    }

    fun enablementHint(
        os: RemoteHostOs,
        vncServerListening: Boolean,
        linuxHasVncBinary: Boolean,
        waylandDisplaySet: Boolean,
    ): String? {
        if (vncServerListening) return null
        return when (os) {
            RemoteHostOs.Mac ->
                "On the remote Mac: System Settings → General → Sharing → enable Screen Sharing. " +
                    "Andy will not change remote settings for you."
            RemoteHostOs.Linux -> when {
                linuxHasVncBinary ->
                    "A VNC server binary is installed but nothing is listening on port 5900. " +
                        "Start your VNC server (e.g. x11vnc / wayvnc) on the remote host, then reconnect."
                waylandDisplaySet ->
                    "Remote host is Wayland. Install and start wayvnc (or another Wayland VNC server) " +
                        "listening on port 5900. Andy will not enable it for you."
                else ->
                    "Install and start a VNC server on the remote Linux host (x11vnc, wayvnc, …) " +
                        "listening on port 5900. Andy will not enable it for you."
            }
            RemoteHostOs.Unknown -> null
        }
    }

    fun fromProbeOutputs(
        unameStdout: String,
        lsofVncStdout: String,
        screenshotCommandV: String,
        linuxVncCommandV: String = "",
        waylandDisplay: String = "",
        localVncClient: String? = null,
        vncPort: Int = RemoteHostCapabilities.DefaultVncPort,
        netstatStdout: String = "",
        connectProbeStdout: String = "",
        macLaunchctlStdout: String = "",
    ): RemoteHostCapabilities {
        val os = parseUname(unameStdout)
        val portOpen = parseVncListening(
            lsofOutput = lsofVncStdout,
            port = vncPort,
            netstatOutput = netstatStdout,
            connectProbeOutput = connectProbeStdout,
        )
        val macConfigured = os == RemoteHostOs.Mac && parseMacScreensharingLaunchd(macLaunchctlStdout)
        // Treat launchd-configured Screen Sharing as available even when unprivileged
        // lsof misses the socket — Open will still forward :5900 and hand off to the OS client.
        val listening = portOpen || macConfigured
        val screenshotTool = parseScreenshotTool(screenshotCommandV)
        val linuxHasVnc = os == RemoteHostOs.Linux &&
            linuxVncCommandV.lineSequence()
                .flatMap { it.trim().split(Regex("""\s+""")) }
                .any { it.isNotBlank() }
        val hint = enablementHint(
            os = os,
            vncServerListening = listening,
            linuxHasVncBinary = linuxHasVnc,
            waylandDisplaySet = waylandDisplay.trim().isNotEmpty(),
        )
        return RemoteHostCapabilities(
            os = os,
            vncPort = vncPort,
            vncServerListening = listening,
            screenshotTool = screenshotTool,
            enablementHint = hint,
            localVncClient = localVncClient,
        )
    }
}

/** Whole-screen capture argv for the host OS screenshot binary. */
object HostScreenshotCommand {
    fun argv(toolPath: String, outputPath: String): List<String>? {
        val name = toolPath.substringAfterLast('/').lowercase()
        return when (name) {
            "screencapture" -> listOf(toolPath, "-x", "-t", "png", outputPath)
            "grim" -> listOf(toolPath, outputPath)
            "scrot" -> listOf(toolPath, "-o", outputPath)
            "import" -> listOf(toolPath, "-window", "root", outputPath)
            else -> null
        }
    }

    /** Prefer the first known tool present in [availableTools] (paths or bare names). */
    fun resolveTool(availableTools: Collection<String>): String? {
        val preference = listOf("screencapture", "grim", "scrot", "import")
        return preference.firstOrNull { name ->
            availableTools.any { path ->
                path == name || path.endsWith("/$name")
            }
        }?.let { name ->
            availableTools.first { path -> path == name || path.endsWith("/$name") }
        }
    }
}
