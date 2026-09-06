package app.andy.desktop.service.webchat

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing for the optional Network Access master password.
 * Encoding: `argon2id$m=<memKb>,t=<iters>,p=<lanes>$<b64url-salt>$<b64url-hash>`
 */
internal object NetworkAccessPasswordHasher {
    private const val PREFIX = "argon2id"
    private const val MEMORY_KB = 65_536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()

    fun hash(password: String, random: SecureRandom = SecureRandom()): String {
        require(password.isNotEmpty()) { "password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val hash = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM)
        return "$PREFIX\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        if (password.isEmpty() || encoded.isBlank()) return false
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val params = parseParams(parts[1]) ?: return false
        val salt = runCatching { b64Decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { b64Decoder.decode(parts[3]) }.getOrNull() ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        val actual = derive(password, salt, params.memoryKb, params.iterations, params.parallelism)
        return constantTimeEquals(actual, expected)
    }

    private data class Params(val memoryKb: Int, val iterations: Int, val parallelism: Int)

    private fun parseParams(raw: String): Params? {
        var memoryKb: Int? = null
        var iterations: Int? = null
        var parallelism: Int? = null
        for (part in raw.split(',')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) return null
            val value = kv[1].toIntOrNull() ?: return null
            when (kv[0]) {
                "m" -> memoryKb = value
                "t" -> iterations = value
                "p" -> parallelism = value
                else -> return null
            }
        }
        val m = memoryKb ?: return null
        val t = iterations ?: return null
        val p = parallelism ?: return null
        if (m < 8 || t < 1 || p < 1) return null
        return Params(m, t, p)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(HASH_BYTES)
        generator.generateBytes(password.toCharArray(), out)
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
