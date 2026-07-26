package app.andy.desktop.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndydProcessTest {
    @Test
    fun removeStaleArtifactsDeletesDeadPidAndSocket() {
        val home = Files.createTempDirectory("andy-andyd-test").toFile()
        try {
            val sock = File(home, "andyd.sock")
            val pid = File(home, "andyd.pid")
            Files.createFile(sock.toPath())
            pid.writeText("999999999\n")

            AndydProcess.removeStaleArtifacts(socketPath = sock, pidPath = pid)

            assertFalse(pid.exists(), "dead pidfile should be removed")
            assertFalse(sock.exists(), "stale socket should be removed")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun removeStaleArtifactsKeepsLivePidFiles() {
        val home = Files.createTempDirectory("andy-andyd-live").toFile()
        try {
            val sock = File(home, "andyd.sock")
            val pid = File(home, "andyd.pid")
            Files.createFile(sock.toPath())
            pid.writeText("${ProcessHandle.current().pid()}\n")

            AndydProcess.removeStaleArtifacts(socketPath = sock, pidPath = pid)

            assertTrue(pid.exists(), "live pidfile should remain")
            assertTrue(sock.exists(), "socket file should remain while pid is alive")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun isExternalDaemonLiveRequiresPidAndSocket() {
        assertFalse(AndydProcess.isExternalDaemonLive())
    }

    @Test
    fun devJavaLaunchCommandUsesCurrentClasspath() {
        val command = AndydProcess.resolveLaunchCommand()
        if (System.getProperty("java.class.path").isNullOrBlank()) {
            return
        }
        assertNotNull(command)
        assertTrue(command.contains("app.andy.desktop.AndydMainKt"))
        assertTrue(
            command.any { arg ->
                arg.endsWith("java") || arg.endsWith("java.exe") ||
                    arg.contains("bin/java") || arg.contains("bin\\java")
            },
        )
    }
}
