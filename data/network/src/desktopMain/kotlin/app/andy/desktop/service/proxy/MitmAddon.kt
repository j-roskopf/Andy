package app.andy.desktop.service.proxy

import java.security.MessageDigest

/**
 * Loads the mitmproxy addon from the classpath resource `proxy/andy_mitm_addon.py`.
 * The resource is the sole source of truth; missing resource fails clearly.
 */
object MitmAddon {
    private const val RESOURCE_PATH = "proxy/andy_mitm_addon.py"

    /** Returns the addon source bytes from the classpath resource. */
    fun getAddonSource(): ByteArray {
        val stream = MitmAddon::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: error("Classpath resource $RESOURCE_PATH is missing")
        return stream.use { it.readBytes() }.also { bytes ->
            check(bytes.isNotEmpty()) { "Classpath resource $RESOURCE_PATH is empty" }
        }
    }

    /** Returns the addon source as UTF-8 text. */
    fun getAddonSourceText(): String = getAddonSource().toString(Charsets.UTF_8)

    /** SHA-256 hex digest of the classpath addon source. */
    fun getAddonSourceSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(getAddonSource())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
