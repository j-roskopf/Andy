package app.andy.desktop.service.remote

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Generic `ssh -O forward -L` / `-O cancel` over an existing ControlMaster.
 * Prefers binding the same local port as the remote port; allocates a fallback
 * when that local port is already taken.
 */
class SshPortForwarder(
    val target: String,
    val controlPath: File,
    private val sshControl: (extra: List<String>) -> Boolean = { extra ->
        defaultSshControl(target, controlPath, extra)
    },
    private val isLocalPortFree: (Int) -> Boolean = ::probeLocalPortFree,
    private val allocateLocalPort: () -> Int = { SshAdbTunnel.allocateLocalPort() },
) {
    /** remotePort → localPort */
    private val active = ConcurrentHashMap<Int, Int>()

    fun mapping(): Map<Int, Int> = HashMap(active)

    fun localPortFor(remotePort: Int): Int? = active[remotePort]

    /**
     * Open a local forward to `127.0.0.1:[remotePort]` on the remote side.
     * Returns the bound local port (may differ from [remotePort] on collision).
     */
    fun forward(remotePort: Int): Int {
        require(remotePort in 1..65535) { "invalid remote port: $remotePort" }
        active[remotePort]?.let { return it }

        val preferred = if (isLocalPortFree(remotePort)) remotePort else allocateLocalPort()
        val localPort = if (openForward(localPort = preferred, remotePort = remotePort)) {
            preferred
        } else if (preferred == remotePort) {
            // Race: preferred looked free but bind failed — allocate and retry once.
            val fallback = allocateLocalPort()
            check(openForward(localPort = fallback, remotePort = remotePort)) {
                "ssh forward failed for remote port $remotePort"
            }
            fallback
        } else {
            error("ssh forward failed for remote port $remotePort → local $preferred")
        }
        active[remotePort] = localPort
        return localPort
    }

    /**
     * Open a same-number forward only (no allocation fallback). Used by scrcpy
     * where the local port must match the adb-forwarded port.
     */
    fun forwardExact(port: Int): Boolean {
        require(port in 1..65535) { "invalid port: $port" }
        active[port]?.let { return it == port }
        val ok = openForward(localPort = port, remotePort = port)
        if (ok) active[port] = port
        return ok
    }

    fun release(remotePort: Int) {
        val localPort = active.remove(remotePort) ?: return
        cancelForward(localPort = localPort, remotePort = remotePort)
    }

    fun releaseAll() {
        active.keys.toList().forEach { release(it) }
    }

    private fun openForward(localPort: Int, remotePort: Int): Boolean =
        sshControl(
            listOf(
                "-O", "forward",
                "-L", "$localPort:127.0.0.1:$remotePort",
            ),
        )

    private fun cancelForward(localPort: Int, remotePort: Int) {
        sshControl(
            listOf(
                "-O", "cancel",
                "-L", "$localPort:127.0.0.1:$remotePort",
            ),
        )
    }

    companion object {
        fun defaultSshControl(target: String, controlPath: File, extra: List<String>): Boolean {
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

        fun probeLocalPortFree(port: Int): Boolean =
            runCatching {
                ServerSocket(port).use { true }
            }.getOrDefault(false)
    }
}
