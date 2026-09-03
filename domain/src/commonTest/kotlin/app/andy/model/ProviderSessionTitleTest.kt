package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSessionTitleTest {
    @Test
    fun promptDerivedMatchesTruncatedFallback() {
        val prompt = "Please investigate why the login button is not responding on Android TV"
        val title = truncateAgentTitle(prompt, 60)
        assertTrue(isPromptDerivedAgentTitle(title, prompt))
        assertTrue(isPromptDerivedAgentTitle("", prompt))
        assertFalse(isPromptDerivedAgentTitle("Advisor", prompt))
        assertFalse(isPromptDerivedAgentTitle("Build: login", prompt))
    }

    @Test
    fun adoptRespectsSettingAndProtectsExplicitTitles() {
        val prompt = "Fix the flaky screenshot test on macOS CI"
        val truncated = truncateAgentTitle(prompt)

        assertFalse(
            shouldAdoptProviderSessionTitle(
                enabled = false,
                currentTitle = truncated,
                prompt = prompt,
                latestPrompt = null,
                providerTitle = "Fix macOS screenshot flakiness",
            ),
        )
        assertTrue(
            shouldAdoptProviderSessionTitle(
                enabled = true,
                currentTitle = truncated,
                prompt = prompt,
                latestPrompt = null,
                providerTitle = "Fix macOS screenshot flakiness",
            ),
        )
        assertFalse(
            shouldAdoptProviderSessionTitle(
                enabled = true,
                currentTitle = "Advisor",
                prompt = prompt,
                latestPrompt = null,
                providerTitle = "Fix macOS screenshot flakiness",
            ),
        )
    }

    @Test
    fun adoptAllowsRefiningPreviousProviderTitle() {
        assertTrue(
            shouldAdoptProviderSessionTitle(
                enabled = true,
                currentTitle = "Fix login",
                prompt = "do the thing",
                latestPrompt = null,
                providerTitle = "Repair login auth flow",
                previousProviderTitle = "Fix login",
            ),
        )
        assertFalse(
            shouldAdoptProviderSessionTitle(
                enabled = true,
                currentTitle = "Fix login",
                prompt = "do the thing",
                latestPrompt = null,
                providerTitle = "Repair login auth flow",
                previousProviderTitle = "Something else",
            ),
        )
        assertFalse(
            shouldAdoptProviderSessionTitle(
                enabled = true,
                currentTitle = "Fix login",
                prompt = "do the thing",
                latestPrompt = null,
                providerTitle = "Fix login",
                previousProviderTitle = "Fix login",
            ),
        )
    }

    @Test
    fun truncateAgentTitleMatchesChatFallbackShape() {
        assertEquals("short", truncateAgentTitle("short"))
        val long = "x".repeat(80)
        val truncated = truncateAgentTitle(long, 60)
        assertEquals(60, truncated.length)
        assertTrue(truncated.endsWith("…"))
    }
}
