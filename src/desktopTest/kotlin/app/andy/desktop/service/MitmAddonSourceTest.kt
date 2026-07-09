package app.andy.desktop.service

import app.andy.desktop.service.proxy.MitmAddon
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            "9873513a43d1986934cc51d6e2c6edec2616c5fb6db54843a3876d6ed6b788a6"
    }

    @Test
    fun mitmAddonLoaderMatchesGoldenSha256() {
        val bytes = MitmAddon.getAddonSource()
        assertEquals(EXPECTED_RESOURCE_SHA256, sha256Hex(bytes))
        assertTrue(bytes.isNotEmpty())
        assertTrue(String(bytes, Charsets.UTF_8).contains("def request("))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
