package com.joetr.andy.mobile.data.vnc

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.interfaces.DHPublicKey
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Apple Remote Desktop / macOS Screen Sharing security type 30.
 *
 * Protocol (from gtk-vnc / Valence): Diffie-Hellman → MD5(shared) → AES-128-ECB of
 * username[64]+password[64], then send ciphertext + client DH public key.
 *
 * For “VNC viewers may control screen with password”, username is typically empty
 * and [password] is the VNC password.
 */
internal object ArdAuthentication {
    const val SecurityType = 30

    fun authenticate(
        generatorBytes: ByteArray,
        keyLength: Int,
        prime: ByteArray,
        peerPublicKey: ByteArray,
        username: String,
        password: String,
    ): Pair<ByteArray, ByteArray> {
        require(keyLength in 16..1024) { "Invalid ARD key length: $keyLength" }
        require(prime.size == keyLength && peerPublicKey.size == keyLength) {
            "ARD prime/peer key length mismatch"
        }

        val primeInt = BigInteger(1, prime)
        val generatorInt = BigInteger(1, generatorBytes)
        val peerY = BigInteger(1, peerPublicKey)

        val keyPairGenerator = KeyPairGenerator.getInstance("DH")
        keyPairGenerator.initialize(DHParameterSpec(primeInt, generatorInt))
        val keyPair = keyPairGenerator.generateKeyPair()

        val keyFactory = KeyFactory.getInstance("DH")
        val peerKey = keyFactory.generatePublic(DHPublicKeySpec(peerY, primeInt, generatorInt)) as DHPublicKey

        val agreement = KeyAgreement.getInstance("DH")
        agreement.init(keyPair.private)
        agreement.doPhase(peerKey, true)
        val sharedSecret = agreement.generateSecret()

        val aesKey = MessageDigest.getInstance("MD5").digest(sharedSecret)
        val credentials = ByteArray(128).also { SecureRandom().nextBytes(it) }
        packCredential(credentials, 0, username)
        packCredential(credentials, 64, password)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val ciphertext = cipher.doFinal(credentials)

        val publicKey = bigIntegerToFixedLength((keyPair.public as DHPublicKey).y, keyLength)
        return ciphertext to publicKey
    }

    private fun packCredential(dest: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = minOf(bytes.size, 63)
        System.arraycopy(bytes, 0, dest, offset, length)
        dest[offset + length] = 0
    }

    private fun bigIntegerToFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length).also { dest ->
                System.arraycopy(raw, 0, dest, length - raw.size, raw.size)
            }
        }
    }
}
