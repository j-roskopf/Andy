package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAuthRecoveryTest {
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
    fun loginCommandsPreferInteractiveProviderEntryPoints() {
        assertEquals("claude", providerLoginCommand(AgentKind.ClaudeCode))
        assertEquals("codex login", providerLoginCommand(AgentKind.Codex))
        assertEquals("cursor-agent", providerLoginCommand(AgentKind.Cursor))
        assertTrue(providerLoginTerminalCommand(AgentKind.ClaudeCode).contains("claude"))
        assertTrue(providerLoginTerminalCommand(AgentKind.ClaudeCode).contains("/login"))
    }

    @Test
    fun hintMentionsHostSignIn() {
        assertEquals(
            "Not logged in — sign in on the Andy host (`claude`, then /login), then retry",
            providerAuthFailureHint(
                AgentKind.ClaudeCode,
                "Failed to authenticate: OAuth session expired and could not be refreshed",
            ),
        )
        assertEquals(
            "Not logged in — sign in on the Andy host (`codex login`), then retry",
            providerAuthFailureHint(AgentKind.Codex, "Please log in"),
        )
        assertNull(providerAuthFailureHint(AgentKind.ClaudeCode, "connection refused"))
    }

    @Test
    fun remoteInstructionsAdmitPhoneCannotFinishOauth() {
        val remote = providerLoginRemoteInstructions(AgentKind.ClaudeCode)
        assertTrue(remote.contains("can’t finish", ignoreCase = true) || remote.contains("can't finish", ignoreCase = true))
        assertTrue(remote.contains("Mac"))
        assertTrue(providerLoginOpenedMessage(AgentKind.ClaudeCode).contains("Switch to that computer"))
    }

    @Test
    fun taskRecoveryUsesErrorMessageHeuristic() {
        val task = AgentTask(
            id = "t1",
            title = "t",
            prompt = "p",
            agent = AgentKind.ClaudeCode,
            createdAtMillis = 1L,
            errorMessage = "Not logged in — sign in on the Andy host (`claude`, then /login), then retry",
        )
        val recovery = task.providerAuthRecoveryOrNull()
        assertNotNull(recovery)
        assertEquals("claude", recovery.command)
        assertTrue(recovery.remoteInstructions.contains("Mac"))
        assertNull(
            task.copy(errorMessage = "exited with code 1").providerAuthRecoveryOrNull(),
        )
    }
}
