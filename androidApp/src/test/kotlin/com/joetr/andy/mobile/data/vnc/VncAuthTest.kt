package com.joetr.andy.mobile.data.vnc

import org.junit.Assert.assertEquals
import org.junit.Test

class VncAuthTest {
    @Test
    fun encryptsClassicChallengeDeterministically() {
        // Fixture: password "password", challenge all zeros → known DES output shape (16 bytes).
        val challenge = ByteArray(16)
        val out = vncEncryptChallenge(challenge, "password")
        assertEquals(16, out.size)
        val again = vncEncryptChallenge(challenge, "password")
        assertEquals(out.toList(), again.toList())
    }
}
