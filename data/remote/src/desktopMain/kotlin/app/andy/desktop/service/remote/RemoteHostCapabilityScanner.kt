package app.andy.desktop.service.remote

import app.andy.service.RemoteHostCapabilities
import app.andy.service.RemoteHostCapabilityProbe
import app.andy.service.RemoteHostOs
import java.io.File

/**
 * Probes a remote host once per connect for VNC / screenshot capability.
 * Never modifies remote configuration — detect and guide only.
 *
 * Screen Sharing detection deliberately avoids relying on unprivileged `lsof`
 * alone: on macOS the listener is owned by root/`launchd`, so SSH-user `lsof`
 * often returns empty even when Sharing is on.
 */
object RemoteHostCapabilityScanner {
    fun probe(target: String, controlPath: File): RemoteHostCapabilities {
        val probes = SshRemoteProbes(target, controlPath)
        val uname = probes.sshShell("uname -s")
        val os = RemoteHostCapabilityProbe.parseUname(uname.stdout)
        val vncPort = RemoteHostCapabilities.DefaultVncPort

        // Bundle listen probes in one SSH round-trip. Markers keep sections distinct.
        // Do not rely on lsof alone — Screen Sharing's socket is often root/launchd-owned.
        val listenBundle = probes.sshShell(
            """
            echo '___ANDY_LSOF___'
            lsof -nP -iTCP:$vncPort -sTCP:LISTEN -F pcPn 2>/dev/null || true
            echo '___ANDY_NETSTAT___'
            (netstat -an -p tcp 2>/dev/null || netstat -an 2>/dev/null || ss -ltn 2>/dev/null || true) | grep -E '(:|\.)$vncPort([^0-9]|$)' || true
            echo '___ANDY_CONNECT___'
            if command -v nc >/dev/null 2>&1; then
              if nc -z -G 1 127.0.0.1 $vncPort 2>/dev/null || nc -z -w 1 127.0.0.1 $vncPort 2>/dev/null; then
                echo ANDY_VNC_OPEN
              fi
            elif (echo >/dev/tcp/127.0.0.1/$vncPort) >/dev/null 2>&1; then
              echo ANDY_VNC_OPEN
            fi
            echo '___ANDY_LAUNCHCTL___'
            launchctl print system/com.apple.screensharing 2>&1 || true
            """.trimIndent(),
        )
        val sections = splitProbeSections(listenBundle.stdout)

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
            lsofVncStdout = sections["LSOF"].orEmpty(),
            screenshotCommandV = screenshot.stdout,
            linuxVncCommandV = linuxVncStdout,
            waylandDisplay = waylandStdout,
            localVncClient = LocalVncClient.detect(),
            vncPort = vncPort,
            netstatStdout = sections["NETSTAT"].orEmpty(),
            connectProbeStdout = sections["CONNECT"].orEmpty(),
            macLaunchctlStdout = sections["LAUNCHCTL"].orEmpty(),
        )
    }

    internal fun splitProbeSections(stdout: String): Map<String, String> {
        val keys = listOf("LSOF", "NETSTAT", "CONNECT", "LAUNCHCTL")
        val markers = keys.associateWith { "___ANDY_${it}___" }
        val indexed = markers.mapValues { (_, marker) -> stdout.indexOf(marker) }
            .filterValues { it >= 0 }
            .entries
            .sortedBy { it.value }
        if (indexed.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        indexed.forEachIndexed { i, (key, start) ->
            val contentStart = start + markers.getValue(key).length
            val contentEnd = indexed.getOrNull(i + 1)?.value ?: stdout.length
            result[key] = stdout.substring(contentStart, contentEnd).trim()
        }
        return result
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
