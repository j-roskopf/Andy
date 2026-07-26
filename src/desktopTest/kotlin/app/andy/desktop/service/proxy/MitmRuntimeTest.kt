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
}
