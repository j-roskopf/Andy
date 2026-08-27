package app.andy.desktop.service

import app.andy.terminal.TmuxAndy
import java.io.File
import java.nio.file.Files

/** Paths and process management for the headless `andyd` daemon. */
object AndydProcess {
    fun andyHome(): File = File(System.getProperty("user.home"), ".andy").also { it.mkdirs() }

    fun socketPath(): File = File(andyHome(), "andyd.sock")

    fun pidPath(): File = File(andyHome(), "andyd.pid")

    /**
     * True when a standalone `andyd` (pidfile + live unix socket) is already running.
     * The GUI does not spawn `andyd` for itself — it uses [RuntimeMode.EmbeddedDaemon]
     * with the terminal embed unless an external daemon is present (launchd / `runAndyd`).
     */
    fun isExternalDaemonLive(
        socketPath: File = socketPath(),
        pidPath: File = pidPath(),
    ): Boolean = isPidAlive(pidPath) && isAndydSocketLive(socketPath)

    /**
     * Ensure a live `andyd` unix socket exists, spawning a local daemon when needed.
     * Used by tooling/CLI bootstrap — not for GUI runtime mode selection.
     */
    fun ensureRunning(
        socketPath: File = socketPath(),
        pidPath: File = pidPath(),
        waitTimeoutMs: Long = 15_000,
    ): Boolean {
        if (isAndydSocketLive(socketPath)) return true
        removeStaleArtifacts(socketPath, pidPath)
        if (isPidAlive(pidPath)) {
            return waitForSocket(socketPath, waitTimeoutMs)
        }
        if (!tryLaunch()) return false
        return waitForSocket(socketPath, waitTimeoutMs)
    }

    /** Drop lock files left behind when `andyd` crashed or was killed. */
    fun removeStaleArtifacts(
        socketPath: File = socketPath(),
        pidPath: File = pidPath(),
    ) {
        val pid = pidPath.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (pid != null) {
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (alive) return
            pidPath.delete()
        }
        if (socketPath.exists() && !isAndydSocketLive(socketPath)) {
            socketPath.delete()
        }
    }

    fun isPidAlive(pidPath: File = pidPath()): Boolean {
        val pid = pidPath.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    fun waitForSocket(socketPath: File, timeoutMs: Long, pollMs: Long = 100): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAndydSocketLive(socketPath)) return true
            Thread.sleep(pollMs)
        }
        return isAndydSocketLive(socketPath)
    }

    fun tryLaunch(): Boolean {
        val command = resolveLaunchCommand() ?: return false
        val logDir = File(andyHome(), "logs").also { it.mkdirs() }
        val stdout = File(logDir, "andyd.log")
        val stderr = File(logDir, "andyd.err.log")
        return runCatching {
            ProcessBuilder(command)
                .directory(andyHome())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr))
                .apply {
                    environment()["PATH"] = augmentedPath()
                    TmuxAndy.bundledTmuxBinary()?.let { environment()["ANDY_TMUX"] = it }
                }
                .start()
            true
        }.getOrDefault(false)
    }

    /**
     * Prefer a packaged `andyd` binary beside the GUI, then dev `java -cp … AndydMainKt`.
     */
    fun resolveLaunchCommand(): List<String>? {
        System.getenv("ANDY_ANDYD")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf { it.isFile && it.canExecute() }?.let { return listOf(it.absolutePath) }
        }
        bundledExecutable()?.let { return listOf(it.absolutePath) }
        File(andyHome(), "bin/andyd").takeIf { it.isFile && it.canExecute() }?.let {
            return listOf(it.absolutePath)
        }
        return devJavaLaunchCommand()
    }

    private fun bundledExecutable(): File? {
        val macOsDir = appMacOsDirectory() ?: return null
        return File(macOsDir, "andyd").takeIf { it.isFile && it.canExecute() }
    }

    /** `Andy.app/Contents/MacOS` when running from a packaged desktop build. */
    fun appMacOsDirectory(): File? {
        val location = AndydProcess::class.java.protectionDomain?.codeSource?.location ?: return null
        val file = runCatching { File(location.toURI()) }.getOrNull() ?: return null
        val contents = generateSequence(file) { it.parentFile }
            .firstOrNull { it.name == "Contents" }
            ?: return null
        val macOs = File(contents, "MacOS")
        return macOs.takeIf { it.isDirectory }
    }

    fun devJavaLaunchCommand(): List<String>? {
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: return null
        val javaBin = File(System.getProperty("java.home"), "bin")
        val java = sequenceOf("java.exe", "java")
            .map { File(javaBin, it) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?: return null
        return listOf(
            java.absolutePath,
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-Dapple.awt.UIElement=true",
            "-Djava.awt.headless=true",
            "-cp",
            classpath,
            "app.andy.desktop.AndydMainKt",
        )
    }

    private fun augmentedPath(): String {
        val existing = System.getenv("PATH").orEmpty()
        val andyBin = File(andyHome(), "bin").absolutePath
        val extras = listOf(andyBin, "/opt/homebrew/bin", "/usr/local/bin")
        return (extras + existing.split(File.pathSeparatorChar).filter { it.isNotBlank() })
            .distinct()
            .joinToString(File.pathSeparator)
    }
}

/** True when [socketPath] accepts a unix connection (not just when the file exists). */
fun isAndydSocketLive(socketPath: File): Boolean {
    if (!socketPath.exists()) return false
    return runCatching {
        java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(true)
            channel.connect(java.net.UnixDomainSocketAddress.of(socketPath.toPath()))
            true
        }
    }.getOrDefault(false)
}

/** Remove a stale socket inode that blocks a new bind. */
fun deleteSocketIfStale(socketPath: File) {
    if (!socketPath.exists()) return
    if (isAndydSocketLive(socketPath)) return
    runCatching { Files.deleteIfExists(socketPath.toPath()) }
}
