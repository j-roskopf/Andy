package com.joetr.andy.mobile.data.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import javax.crypto.interfaces.DHPublicKey

class ArdAuthenticationTest {
    @Test
    fun producesCiphertextAndPublicKeyOfExpectedSizes() {
        val keyPairGenerator = KeyPairGenerator.getInstance("DH")
        keyPairGenerator.initialize(1024)
        val serverPair = keyPairGenerator.generateKeyPair()
        val params = (serverPair.public as DHPublicKey).params
        val keyLength = 128
        val prime = bigIntToFixed(params.p, keyLength)
        val peer = bigIntToFixed((serverPair.public as DHPublicKey).y, keyLength)
        val generator = bigIntToFixed(params.g, 2)

        val (cipher, pub) = ArdAuthentication.authenticate(
            generatorBytes = generator,
            keyLength = keyLength,
            prime = prime,
            peerPublicKey = peer,
            username = "",
            password = "secret",
        )
        assertEquals(128, cipher.size)
        assertEquals(keyLength, pub.size)
        assertTrue(cipher.any { it != 0.toByte() })
        assertEquals(ArdAuthentication.SecurityType, 30)
    }

    private fun bigIntToFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length).also { System.arraycopy(raw, 0, it, length - raw.size, raw.size) }
        }
    }
}
