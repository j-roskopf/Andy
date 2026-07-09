package app.andy.desktop.service

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SHA-256 golden for the mitmproxy addon source.
 *
 * Phase 0 locks both the evaluated Kotlin heredoc ([AndyMitmAddonSource]) and the
 * classpath resource that [DesktopProxyService] prefers at runtime. They currently
 * differ (resource is ahead); Phase 3 deletes the heredoc and keeps the resource
 * as the single source of truth — update [EXPECTED_HEREDOC_SHA256] removal then.
 */
class MitmAddonSourceTest {
    companion object {
        /** SHA-256 of [AndyMitmAddonSource] after Kotlin `trimIndent()`. */
        const val EXPECTED_HEREDOC_SHA256 =
            "8e337b576aa9c54f985d411ef04287d8d572d8b76ac1ceed97bd83e75762c1f9"

        /** SHA-256 of `src/desktopMain/resources/proxy/andy_mitm_addon.py` (runtime source). */
        const val EXPECTED_RESOURCE_SHA256 =
            "9873513a43d1986934cc51d6e2c6edec2616c5fb6db54843a3876d6ed6b788a6"
    }

    @Test
    fun evaluatedHeredocMatchesGoldenSha256() {
        assertEquals(EXPECTED_HEREDOC_SHA256, sha256Hex(AndyMitmAddonSource.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun classpathResourceMatchesGoldenSha256() {
        val bytes = javaClass.classLoader.getResourceAsStream("proxy/andy_mitm_addon.py").use { stream ->
            assertNotNull(stream, "classpath resource proxy/andy_mitm_addon.py missing")
            stream.readBytes()
        }
        assertEquals(EXPECTED_RESOURCE_SHA256, sha256Hex(bytes))
        assertTrue(bytes.isNotEmpty())
        assertTrue(String(bytes, Charsets.UTF_8).contains("def request("))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
