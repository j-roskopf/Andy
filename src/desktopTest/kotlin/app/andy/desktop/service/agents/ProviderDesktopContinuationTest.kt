package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderDesktopContinuationTest {
    @Test
    fun codexSessionOpensItsExactDesktopThreadOnMacOs() {
        val continuation = task(AgentKind.Codex, vendorSessionId = "thread-123")
            .providerDesktopContinuation(macOs = true)

        assertEquals("Codex", continuation?.providerLabel)
        assertEquals("codex://threads/thread-123", continuation?.uri)
    }

    @Test
    fun acpSessionIdIsUsedWhenVendorSessionIdIsNotYetPersisted() {
        val continuation = task(AgentKind.Codex, acpSessionId = "acp-thread")
            .providerDesktopContinuation(macOs = true)

        assertEquals("codex://threads/acp-thread", continuation?.uri)
    }

    @Test
    fun unsupportedProvidersAndPlatformsFallBackToTerminal() {
        assertNull(task(AgentKind.Cursor, vendorSessionId = "chat").providerDesktopContinuation(macOs = true))
        assertNull(task(AgentKind.Codex, vendorSessionId = "thread").providerDesktopContinuation(macOs = false))
        assertNull(task(AgentKind.Codex).providerDesktopContinuation(macOs = true))
    }

    private fun task(
        agent: AgentKind,
        vendorSessionId: String? = null,
        acpSessionId: String? = null,
    ) = AgentTask(
        id = "task",
        title = "Task",
        prompt = "Prompt",
        agent = agent,
        vendorSessionId = vendorSessionId,
        acpSessionId = acpSessionId,
        createdAtMillis = 1L,
    )
}
