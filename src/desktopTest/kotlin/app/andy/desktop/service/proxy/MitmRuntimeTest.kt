package app.andy.desktop.service.proxy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MitmRuntimeTest {
    @Test
    fun resolvePrefersPinnedVenvWhenPresent() {
        val home = kotlin.io.path.createTempDirectory("andy-mitm-runtime").toFile()
        try {
            val venvBin = File(home, ".andy/proxy/venv/bin").also { it.mkdirs() }
            val mitmdump = File(venvBin, "mitmdump").also {
                it.writeText("#!/bin/sh\necho Mitmproxy: ${MitmRuntime.PINNED_MITMPROXY_VERSION}\n")
                it.setExecutable(true)
            }
            File(home, ".andy/proxy/mitmproxy-version").writeText(MitmRuntime.PINNED_MITMPROXY_VERSION)

            val resolved = MitmRuntime.resolveMitmdump(
                userHome = home,
                provisionIfNeeded = false,
                findSystemMitmdump = { "/usr/bin/mitmdump-should-not-win" },
            )
            assertEquals(mitmdump.absolutePath, resolved.executable)
            assertEquals(MitmRuntime.ResolveResult.Source.PinnedVenv, resolved.source)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun resolveFallsBackToSupportedSystemMitmdump() {
        val home = kotlin.io.path.createTempDirectory("andy-mitm-runtime-fallback").toFile()
        try {
            val resolved = MitmRuntime.resolveMitmdump(
                userHome = home,
                provisionIfNeeded = false,
                findSystemMitmdump = { "/opt/homebrew/bin/mitmdump" },
                readVersion = { MitmRuntime.MitmVersion(12, 2, 3) },
            )
            assertEquals("/opt/homebrew/bin/mitmdump", resolved.executable)
            assertEquals(MitmRuntime.ResolveResult.Source.System, resolved.source)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun resolveRejectsUnsupportedSystemMitmdump() {
        val home = kotlin.io.path.createTempDirectory("andy-mitm-runtime-bad").toFile()
        try {
            val resolved = MitmRuntime.resolveMitmdump(
                userHome = home,
                provisionIfNeeded = false,
                findSystemMitmdump = { "/usr/bin/mitmdump" },
                readVersion = { MitmRuntime.MitmVersion(8, 1, 0) },
            )
            assertNull(resolved.executable)
            assertTrue(resolved.message.contains("requires"))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun resolvePrefersPinnedVenvOnWindowsPaths() {
        val home = kotlin.io.path.createTempDirectory("andy-mitm-runtime-win").toFile()
        val originalOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 10")
            val venvScripts = File(home, ".andy/proxy/venv/Scripts").also { it.mkdirs() }
            val mitmdump = File(venvScripts, "mitmdump.exe").also {
                it.writeText("stub")
                it.setExecutable(true)
            }
            File(home, ".andy/proxy/mitmproxy-version").writeText(MitmRuntime.PINNED_MITMPROXY_VERSION)

            val resolved = MitmRuntime.resolveMitmdump(
                userHome = home,
                provisionIfNeeded = false,
                findSystemMitmdump = { "/usr/bin/mitmdump-should-not-win" },
            )
            assertEquals(mitmdump.absolutePath, resolved.executable)
            assertEquals(MitmRuntime.ResolveResult.Source.PinnedVenv, resolved.source)
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs)
            } else {
                System.clearProperty("os.name")
            }
            home.deleteRecursively()
        }
    }
}
