package app.andy.desktop.service.remote

import app.andy.service.RemoteHostCapabilities
import app.andy.service.RemoteHostCapabilityProbe
import app.andy.service.RemoteHostOs
import java.io.File

/**
 * Probes a remote host once per connect for VNC / screenshot capability.
 * Never modifies remote configuration — detect and guide only.
 */
object RemoteHostCapabilityScanner {
    fun probe(target: String, controlPath: File): RemoteHostCapabilities {
        val probes = SshRemoteProbes(target, controlPath)
        val uname = probes.sshShell("uname -s")
        val os = RemoteHostCapabilityProbe.parseUname(uname.stdout)
        val vncPort = RemoteHostCapabilities.DefaultVncPort
        val lsof = probes.sshShell(
            "lsof -nP -iTCP:$vncPort -sTCP:LISTEN -F pcPn 2>/dev/null || true",
        )
        val screenshot = probes.sshShell(
            "command -v screencapture grim scrot import 2>/dev/null || true",
        )
        val linuxVncStdout = if (os == RemoteHostOs.Linux) {
            probes.sshShell("command -v x11vnc wayvnc 2>/dev/null || true").stdout
        } else {
            ""
        }
        val waylandStdout = if (os == RemoteHostOs.Linux) {
            probes.sshShell("printf '%s' \"\${WAYLAND_DISPLAY:-}\"").stdout
        } else {
            ""
        }
        return RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = uname.stdout,
            lsofVncStdout = lsof.stdout,
            screenshotCommandV = screenshot.stdout,
            linuxVncCommandV = linuxVncStdout,
            waylandDisplay = waylandStdout,
            localVncClient = LocalVncClient.detect(),
            vncPort = vncPort,
        )
    }
}

/** Detect a VNC client on the GUI host (not the remote). */
object LocalVncClient {
    fun detect(osName: String = System.getProperty("os.name").orEmpty()): String? {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "open"
            os.contains("linux") -> detectLinuxClient()
            else -> null
        }
    }

    fun launchArgv(client: String, vncUrl: String): List<String> =
        when (client) {
            "open" -> listOf("open", vncUrl)
            else -> listOf(client, vncUrl)
        }

    private fun detectLinuxClient(): String? {
        val candidates = listOf("vncviewer", "remmina", "vinagre", "gvncviewer")
        for (name in candidates) {
            val found = runCatching {
                val process = ProcessBuilder("sh", "-c", "command -v $name")
                    .redirectErrorStream(true)
                    .start()
                val out = process.inputStream.bufferedReader().readText().trim()
                out.takeIf { process.waitFor() == 0 && it.isNotBlank() }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }
}
