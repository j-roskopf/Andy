package app.andy.desktop.service.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshCredentialStoreTest {
    @Test
    fun normalizeTrimsAndLowercases() {
        assertEquals("user@host.example", SshCredentialStore.normalize("  User@Host.Example  "))
        assertNull(SshCredentialStore.normalize("   "))
        assertNull(SshCredentialStore.normalize(""))
    }
}
