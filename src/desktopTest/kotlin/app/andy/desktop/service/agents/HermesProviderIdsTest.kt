package app.andy.desktop.service.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

class HermesProviderIdsTest {
    @Test
    fun readsConfiguredProviderAndAuthenticatedFallback() {
        val home = File.createTempFile("andy-hermes-home", null).also { it.delete(); it.mkdirs() }
        try {
            File(home, ".hermes").mkdirs()
            File(home, ".hermes/config.yaml").writeText(
                """
                model:
                  default: anthropic/claude-opus-4.6
                  provider: auto
                """.trimIndent(),
            )
            File(home, ".hermes/auth.json").writeText(
                """{"providers":{"nous":{"access_token":"token-123"}}}""",
            )
            assertEquals("nous", HermesProviderIds.resolveForLaunch(home))

            File(home, ".hermes/config.yaml").writeText(
                """
                model:
                  provider: openrouter
                """.trimIndent(),
            )
            assertEquals("openrouter", HermesProviderIds.resolveForLaunch(home))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun stripsQuotedYamlProviderValues() {
        val home = File.createTempFile("andy-hermes-home-quoted", null).also { it.delete(); it.mkdirs() }
        try {
            File(home, ".hermes").mkdirs()
            File(home, ".hermes/config.yaml").writeText(
                """
                model:
                  provider: "auto"
                """.trimIndent(),
            )
            File(home, ".hermes/auth.json").writeText(
                """{"providers":{"nous":{"access_token":"token-123"}}}""",
            )
            assertEquals("nous", HermesProviderIds.resolveForLaunch(home))

            File(home, ".hermes/config.yaml").writeText(
                """
                model:
                  provider: 'openrouter'
                """.trimIndent(),
            )
            assertEquals("openrouter", HermesProviderIds.resolveForLaunch(home))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenNoProviderIsConfigured() {
        val home = File.createTempFile("andy-hermes-home-empty", null).also { it.delete(); it.mkdirs() }
        try {
            assertNull(HermesProviderIds.resolveForLaunch(home))
        } finally {
            home.deleteRecursively()
        }
    }
}
