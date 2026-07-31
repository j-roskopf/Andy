package app.andy.desktop.service.agents

import kotlin.test.Test
import kotlin.test.assertEquals

class HermesOpenClawSessionIdsTest {
    @Test
    fun parsesHermesJsonAndBannerFallback() {
        assertEquals(listOf("20260731_120000_abcdef"), HermesSessionIds.parseSessionListOutput("[{\"id\":\"20260731_120000_abcdef\"}]"))
        assertEquals(listOf("20260731_120000_abcdef"), HermesSessionIds.parseSessionListOutput("Session: 20260731_120000_abcdef"))
    }

    @Test
    fun parsesOpenClawBareAndAgentSessionKeys() {
        val output = "[{\"key\":\"incident-42\"},{\"sessionKey\":\"agent:main:deploy\"}]"
        assertEquals(listOf("incident-42", "agent:main:deploy"), OpenClawSessionIds.parseSessionListOutput(output))
    }
}
