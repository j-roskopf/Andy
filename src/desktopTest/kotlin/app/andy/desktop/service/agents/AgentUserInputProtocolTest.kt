package app.andy.desktop.service.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentUserInputProtocolTest {
    @Test
    fun acceptsTruncatedClosingTagFromCodex() {
        val text =
            """<andy_user_input>{"questions":[{"id":"fps_acceptance_gate","question":"What performance gate should define successful 120-FPS support?","options":[{"label":"114 FPS GPU device gate (Recommended)"},{"label":"120 FPS exact gate"},{"label":"Telemetry only, no gate"}]}]}</andy_user>"""

        val parsed = assertNotNull(parseAgentUserInput(text))
        assertEquals("fps_acceptance_gate", parsed.request.questions.single().id)
        assertEquals(3, parsed.request.questions.single().options.size)
        assertTrue(parsed.visibleText.isBlank())
    }

    @Test
    fun acceptsWhitespaceAfterOpeningTag() {
        val text =
            """<andy_user_input>
{"questions":[{"id":"choice","question":"Pick one","options":[{"label":"A"},{"label":"B"}]}]}
</andy_user_input>"""

        val parsed = assertNotNull(parseAgentUserInput(text))
        assertEquals("choice", parsed.request.questions.single().id)
    }

    @Test
    fun parsesNestedOptionObjectsFromCursor() {
        val text = """
            Resolved so far:
            - 114 FPS GPU acceptance gate

            <andy_user_input>{"questions":[{"id":"bitrate_scaling_policy","question":"When auto-detect raises maxFps to 120, how should video bitrate be chosen?","options":[{"label":"Scale Mbps proportionally with maxFps (Recommended)"},{"label":"Keep Mbps unchanged; prefer stability over bitrate"},{"label":"Apply fixed 120fps tier presets (e.g. 6 / 8 / 12 Mbps)"}]}]}</andy_user_input>
        """.trimIndent()

        val parsed = assertNotNull(parseAgentUserInput(text))
        assertEquals("bitrate_scaling_policy", parsed.request.questions.single().id)
        assertTrue(parsed.visibleText.contains("114 FPS GPU acceptance gate"))
    }

    @Test
    fun planCandidateRejectsDecisionCheckpointPayload() {
        val checkpoint =
            """<andy_user_input>{"questions":[{"id":"choice","question":"Pick one","options":[{"label":"A"},{"label":"B"}]}]}</andy_user_input>"""

        assertNull(agentPlanTextCandidate(checkpoint))
    }

    @Test
    fun planCandidateKeepsRecommendationBeforeCheckpoint() {
        val text =
            """Ship the GPU gate first.

<andy_user_input>{"questions":[{"id":"choice","question":"Pick one","options":[{"label":"A"},{"label":"B"}]}]}</andy_user_input>"""

        assertEquals("Ship the GPU gate first.", agentPlanTextCandidate(text))
    }
}
