package app.andy.desktop.service.remote

import app.andy.desktop.service.CommandRunner
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tunnels the remote host's ADB server to a local TCP port and can add per-scrcpy
 * `ssh -O forward` locals so [app.andy.desktop.service.mirror.DesktopMirrorEngine]
 * gets real H.264 with only network latency.
 */
class SshAdbTunnel(
    val target: String,
    val controlPath: File,
    val localAdbPort: Int,
) {
    private val activeScrcpyForwards = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Local adb client talks to the tunneled remote server via `-P [localAdbPort]`. */
    fun adbRunner(base: CommandRunner): CommandRunner =
        CommandRunner(executor = { command, timeoutSeconds ->
            base.run(injectAdbServerPort(command), timeoutSeconds)
        })

    fun injectAdbServerPort(command: List<String>): List<String> {
        if (command.isEmpty()) return command
        val adbIndex = command.indexOfFirst { token ->
            token == "adb" || token.endsWith("/adb") || token.endsWith("\\adb")
        }
        if (adbIndex < 0) return command
        if (command.getOrNull(adbIndex + 1) == "-P") return command
        return command.take(adbIndex + 1) + listOf("-P", localAdbPort.toString()) + command.drop(adbIndex + 1)
    }

    fun ensureRemoteAdbServer(): CommandResult {
        val result = SshRemoteProbes(target, controlPath).sshShell(
            "export PATH=\"\$PATH:\$HOME/Library/Android/sdk/platform-tools:" +
                "\$ANDROID_HOME/platform-tools:\$ANDROID_SDK_ROOT/platform-tools\"; " +
                "adb start-server >/dev/null 2>&1; adb version",
        )
        return if (result.exitCode == 0) {
            CommandResult.success(result.stdout)
        } else {
            CommandResult.failure(
                result.stderr.ifBlank { result.stdout }.ifBlank { "adb start-server failed on $target" },
            )
        }
    }

    fun discoverRemoteSdk(localAdbPath: String?): SdkDiscovery {
        val probe = SshRemoteProbes(target, controlPath).sshShell(
            "SDK=\"\${ANDROID_HOME:-\${ANDROID_SDK_ROOT:-}}\"; " +
                "if [ -z \"\$SDK\" ] && [ -d \"\$HOME/Library/Android/sdk\" ]; then SDK=\"\$HOME/Library/Android/sdk\"; fi; " +
                "if [ -z \"\$SDK\" ] && [ -d \"\$HOME/Android/Sdk\" ]; then SDK=\"\$HOME/Android/Sdk\"; fi; " +
                "ADB=\$(command -v adb 2>/dev/null || true); " +
                "if [ -z \"\$ADB\" ] && [ -n \"\$SDK\" ] && [ -x \"\$SDK/platform-tools/adb\" ]; then ADB=\"\$SDK/platform-tools/adb\"; fi; " +
                "EMU=\$(command -v emulator 2>/dev/null || true); " +
                "if [ -z \"\$EMU\" ] && [ -n \"\$SDK\" ] && [ -x \"\$SDK/emulator/emulator\" ]; then EMU=\"\$SDK/emulator/emulator\"; fi; " +
                "printf '%s\\n%s\\n%s\\n' \"\$SDK\" \"\$ADB\" \"\$EMU\"",
        )
        val lines = probe.stdout.lines().map { it.trim() }
        val sdkPath = lines.getOrNull(0)?.takeIf { it.isNotBlank() }
        val remoteAdb = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
        val emulator = lines.getOrNull(2)?.takeIf { it.isNotBlank() }
        val clientOk = !localAdbPath.isNullOrBlank()
        // Healthy tunnel must not look like a missing-SDK warning in Devices.
        val issues = buildList {
            if (!clientOk) add("Local adb client not found (needed to talk to the tunneled remote adb server)")
            if (remoteAdb == null) add("Remote host has no adb on PATH / ANDROID_HOME")
        }
        return SdkDiscovery(
            sdkPath = sdkPath,
            adbPath = localAdbPath,
            emulatorPath = emulator,
            sdkManagerPath = null,
            avdManagerPath = null,
            issues = issues,
        )
    }

    /**
     * After remote `adb forward tcp:PORT …`, open a matching local SSH forward so the GUI can
     * connect to 127.0.0.1:PORT. Uses ControlMaster `-O forward`.
     */
    fun openLocalTcpForward(port: Int): Boolean {
        if (!activeScrcpyForwards.add(port)) return true
        val ok = sshControl(
            listOf(
                "-O", "forward",
                "-L", "$port:127.0.0.1:$port",
            ),
        )
        if (!ok) activeScrcpyForwards.remove(port)
        return ok
    }

    fun closeLocalTcpForward(port: Int) {
        if (!activeScrcpyForwards.remove(port)) return
        sshControl(
            listOf(
                "-O", "cancel",
                "-L", "$port:127.0.0.1:$port",
            ),
        )
    }

    fun closeAllScrcpyForwards() {
        activeScrcpyForwards.toList().forEach { closeLocalTcpForward(it) }
    }

    private fun sshControl(extra: List<String>): Boolean {
        // Control-master mux only — do not pass ControlMaster=auto here or OpenSSH may try
        // to open a second session instead of talking to the existing -N master.
        val cmd = buildList {
            add("ssh")
            add("-o")
            add("ControlPath=${controlPath.absolutePath}")
            add("-o")
            add("ConnectTimeout=8")
            addAll(extra)
            add(target)
        }
        return runCatching {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            val ok = finished && process.exitValue() == 0
            if (!ok) {
                System.err.println(
                    "andy remote: ssh ${extra.joinToString(" ")} failed " +
                        "(exit=${if (finished) process.exitValue() else "timeout"}): ${output.take(300)}",
                )
            }
            ok
        }.getOrDefault(false)
    }

    companion object {
        private val portSeq = AtomicInteger(15_000)

        fun allocateLocalPort(): Int {
            repeat(40) {
                val candidate = portSeq.updateAndGet { current ->
                    val next = current + 1
                    if (next > 60_000) 15_000 else next
                }
                runCatching {
                    ServerSocket(candidate).use { return it.localPort }
                }
            }
            ServerSocket(0).use { return it.localPort }
        }
    }
}

