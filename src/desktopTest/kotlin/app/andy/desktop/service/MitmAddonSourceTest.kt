package app.andy.desktop.service

import app.andy.desktop.service.proxy.MitmAddon
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SHA-256 golden for the mitmproxy addon classpath resource.
 *
 * Phase 3: the resource is the sole source of truth; [MitmAddon.getAddonSource]
 * must return bytes matching [EXPECTED_RESOURCE_SHA256].
 */
class MitmAddonSourceTest {
    companion object {
        /** SHA-256 of `src/desktopMain/resources/proxy/andy_mitm_addon.py` (runtime source). */
        const val EXPECTED_RESOURCE_SHA256 =
            "4a5eae319c815f08889822fcbc6cc47d61ec2b3f659c6c9983800e02f22a8e3f"
    }

    @Test
    fun mitmAddonLoaderMatchesGoldenSha256() {
        val bytes = MitmAddon.getAddonSource()
        assertEquals(EXPECTED_RESOURCE_SHA256, sha256Hex(bytes))
        assertEquals(EXPECTED_RESOURCE_SHA256, MitmAddon.getAddonSourceSha256())
        assertTrue(bytes.isNotEmpty())
        val source = String(bytes, Charsets.UTF_8)
        assertTrue(source.contains("def request("))
        assertTrue(source.contains("def tls_failed_client("))
        assertTrue(source.contains("tls_failed"))
        assertTrue(source.contains("def tls_clienthello("))
        assertTrue(source.contains("tls_passthrough"))
        assertTrue(source.contains("happy_eyeballs_delay"))
        assertTrue(source.contains("ANDY_HAPPY_EYEBALLS_DELAY"))
        assertTrue(source.contains("_install_happy_eyeballs"))
        assertTrue(source.contains("def client_connected("))
        assertTrue(source.contains("async def server_connect("))
        assertTrue(source.contains("events_dropped"))
        assertTrue(source.contains("addon_hello"))
        assertTrue(source.contains("put_nowait"))
        assertTrue(source.contains("raw_content"))
        // Hot path must not call message.content (full synchronous decompress).
        assertTrue(!source.contains("message.content"))
    }

    @Test
    fun mitmAddonPassesPythonSyntaxCheckWhenPythonAvailable() {
        val python = listOf("python3", "python").firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        } ?: return
        val source = MitmAddon.getAddonSource()
        val temp = kotlin.io.path.createTempFile("andy-mitm-addon", ".py").toFile()
        try {
            temp.writeBytes(source)
            val process = ProcessBuilder(python, "-m", "py_compile", temp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                fail("py_compile failed ($code): $output")
            }
        } finally {
            temp.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
