package app.andy.desktop.service.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterCredentialStoreTest {
    /**
     * Exercises the DPAPI-backed Windows store on CI. Skipped elsewhere and when no CI
     * environment is present, since it writes the real per-user credential store.
     */
    @Test
    fun windowsRoundTripsKeyWhenAvailable() {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (!isWindows || System.getenv("CI").isNullOrBlank()) return

        val previous = OpenRouterCredentialStore.load()
        val secret = "sk-or-test-${System.nanoTime()}"
        try {
            OpenRouterCredentialStore.save(secret)
            assertTrue(OpenRouterCredentialStore.isPresent())
            assertEquals(secret, OpenRouterCredentialStore.load())

            assertTrue(OpenRouterCredentialStore.delete())
            assertFalse(OpenRouterCredentialStore.isPresent())
        } finally {
            if (previous != null) OpenRouterCredentialStore.save(previous) else OpenRouterCredentialStore.delete()
        }
    }
}
