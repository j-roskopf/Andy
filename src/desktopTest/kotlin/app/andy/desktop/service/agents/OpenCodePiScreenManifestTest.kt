package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCodePiScreenManifestTest {
    @Test
    fun openCodeDetectsWorkingAndBlocked() {
        val working = evaluateScreenManifest(
            AgentKind.OpenCode,
            DetectionInput(screen = "Working on the change\nesc interrupt\n"),
        )
        assertEquals(ScreenState.Working, working.state)
        assertTrue(working.visibleWorking)

        val blocked = evaluateScreenManifest(
            AgentKind.OpenCode,
            DetectionInput(screen = "Allow this action?\n(y) yes  (n) no\n"),
        )
        assertEquals(ScreenState.Blocked, blocked.state)
        assertTrue(blocked.visibleBlocker)
    }

    @Test
    fun openCodeIdleRequiresRestChromeWithoutInterrupt() {
        val idle = evaluateScreenManifest(
            AgentKind.OpenCode,
            DetectionInput(screen = "build · anthropic/claude-sonnet-5\nctrl+x for more\n>\n"),
        )
        assertEquals(ScreenState.Idle, idle.state)
        assertTrue(idle.visibleIdle)

        val busy = evaluateScreenManifest(
            AgentKind.OpenCode,
            DetectionInput(screen = "build · anthropic/claude-sonnet-5\nesc again to interrupt\nctrl+x\n"),
        )
        assertEquals(ScreenState.Working, busy.state)
    }

    @Test
    fun openCodeChromeLooksIdleHelper() {
        assertTrue(openCodeChromeLooksIdle("ctrl+x for more\n>\n"))
        assertFalse(openCodeChromeLooksIdle("esc interrupt\nctrl+x\n"))
    }

    @Test
    fun piDetectsTrustPromptAndIdle() {
        val blocked = evaluateScreenManifest(
            AgentKind.Pi,
            DetectionInput(screen = "Trust this project before loading extensions?\n"),
        )
        assertEquals(ScreenState.Blocked, blocked.state)

        val idle = evaluateScreenManifest(
            AgentKind.Pi,
            DetectionInput(screen = "Ready\n/hotkeys for shortcuts\n>\n"),
        )
        assertEquals(ScreenState.Idle, idle.state)
    }
}
