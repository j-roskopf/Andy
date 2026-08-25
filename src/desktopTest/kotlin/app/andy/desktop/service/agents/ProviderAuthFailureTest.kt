package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAuthFailureTest {
    @Test
    fun detectsClaudeLoginAndOauthMessages() {
        assertTrue(looksLikeProviderAuthFailure("Please run /login"))
        assertTrue(looksLikeProviderAuthFailure("Not logged in"))
        assertTrue(
            looksLikeProviderAuthFailure(
                "Internal error: Failed to authenticate: OAuth session expired and could not be refreshed",
            ),
        )
        assertFalse(looksLikeProviderAuthFailure("ACP preflight timed out"))
        assertFalse(looksLikeProviderAuthFailure(null))
        assertFalse(looksLikeProviderAuthFailure(""))
    }

    @Test
    fun claudeHintMentionsLoginSlashCommand() {
        assertEquals(
            "Not logged in — run `claude` in a terminal and sign in (`/login`), then retry",
            providerAuthFailureHint(
                AgentKind.ClaudeCode,
                "Failed to authenticate: OAuth session expired and could not be refreshed",
            ),
        )
        assertEquals(
            "Not logged in — run `codex` in a terminal and sign in, then retry",
            providerAuthFailureHint(AgentKind.Codex, "Please log in"),
        )
        assertNull(providerAuthFailureHint(AgentKind.ClaudeCode, "connection refused"))
    }

    @Test
    fun friendlyAcpMessagePrefersAuthHintOverRawTransportError() {
        assertEquals(
            "Not logged in — run `claude` in a terminal and sign in (`/login`), then retry",
            friendlyAcpFailureMessage(
                agent = AgentKind.ClaudeCode,
                phase = AcpFailurePhase.Prompt,
                raw = "Internal error: Failed to authenticate: OAuth session expired and could not be refreshed",
            ),
        )
        assertEquals(
            "ACP failed to start: boom",
            friendlyAcpFailureMessage(
                agent = AgentKind.ClaudeCode,
                phase = AcpFailurePhase.Start,
                raw = "boom",
            ),
        )
        assertEquals(
            "ACP prompt failed",
            friendlyAcpFailureMessage(
                agent = AgentKind.ClaudeCode,
                phase = AcpFailurePhase.Prompt,
                raw = null,
            ),
        )
    }
}
