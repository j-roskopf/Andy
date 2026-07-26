package app.andy.desktop

import app.andy.desktop.service.createDaemonRuntime
import app.andy.terminal.TmuxAndy
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Headless Andy daemon (`andyd`).
 *
 * Owns agent/project state (SQLite), spawns agents into `tmux -L andy`, and
 * serves MCP over `~/.andy/andyd.sock` (plus optional loopback HTTP for agent CLIs).
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    // Keep andyd out of the macOS Dock (classpath still pulls AWT/Compose).
    System.setProperty("apple.awt.UIElement", "true")
    System.setProperty("java.awt.headless", "true")

    val andyHome = File(System.getProperty("user.home"), ".andy").also { it.mkdirs() }
    runCatching { app.andy.desktop.service.agents.AndyStatusHookInstaller.ensureInstalled() }
    val pidFile = File(andyHome, "andyd.pid")
    val lock = acquirePidLock(pidFile) ?: run {
        System.err.println("andyd already running (pidfile ${pidFile.absolutePath})")
        exitProcess(1)
    }

    if (!TmuxAndy.isAvailable()) {
        System.err.println(
            "WARNING: tmux not found. Re-run install-andy.sh or set ANDY_TMUX=/path/to/tmux",
        )
    } else {
        runCatching { TmuxAndy.startServer() }
    }

    val runtime = try {
        createDaemonRuntime()
    } catch (error: Throwable) {
        System.err.println("andyd failed to start: ${error.message}")
        error.printStackTrace()
        runCatching { lock.close() }
        runCatching { pidFile.delete() }
        exitProcess(1)
    }
    System.err.println("andyd started pid=${ProcessHandle.current().pid()} sock=${runtime.socketPath}")
    check(runtime.socketPath.exists()) {
        "andyd started but socket missing: ${runtime.socketPath.absolutePath}"
    }

    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        println("andyd shutting down…")
        System.out.flush()
        runCatching { runtime.shutdown() }
        runCatching { lock.close() }
        runCatching { pidFile.delete() }
        runCatching { File(andyHome, "andyd.sock").delete() }
        done.countDown()
    })

    // Keep alive until SIGTERM / Ctrl-C.
    done.await()
}

data class DaemonPidLock(
    private val channel: FileChannel,
) {
    fun close() {
        runCatching { channel.close() }
    }
}

private fun acquirePidLock(pidFile: File): DaemonPidLock? {
    pidFile.parentFile?.mkdirs()
    val channel = FileChannel.open(
        pidFile.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.READ,
    )
    val lock = runCatching { channel.tryLock() }.getOrNull()
    if (lock == null) {
        runCatching { channel.close() }
        return null
    }
    channel.truncate(0)
    val pid = ManagementFactory.getRuntimeMXBean().name.substringBefore('@')
    channel.write(ByteBuffer.wrap("$pid\n".toByteArray()))
    channel.force(true)
    return DaemonPidLock(channel)
}
