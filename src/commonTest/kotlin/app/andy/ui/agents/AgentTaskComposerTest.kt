package app.andy.ui.agents

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTaskComposerTest {
    @Test
    fun orchestrationSkillsForceMcpAttach() {
        assertTrue(isOrchestrationSkillName("andy-handoff"))
        assertTrue(isOrchestrationSkillName("Andy-Loop"))
        assertTrue(isOrchestrationSkillName("andy-advisor"))
        assertTrue(isOrchestrationSkillName("andy-committee"))
        assertFalse(isOrchestrationSkillName("andy-orchestration"))
        assertFalse(isOrchestrationSkillName("grill-me"))
        assertFalse(isOrchestrationSkillName("babysit"))

        assertTrue(attachMcpAfterSkillSelection("andy-handoff", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("andy-loop", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("andy-advisor", currentAttachMcp = true))
        assertFalse(attachMcpAfterSkillSelection("babysit", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("unrelated", currentAttachMcp = true))
    }

    @Test
    fun parsesOnlyFiniteNonNegativeBudgets() {
        assertEquals(2.5, " 2.50 ".toMaxBudgetUsd())
        assertEquals(0.0, "0".toMaxBudgetUsd())
        assertNull("-1".toMaxBudgetUsd())
        assertNull("NaN".toMaxBudgetUsd())
        assertNull("Infinity".toMaxBudgetUsd())
        assertNull("not a number".toMaxBudgetUsd())
    }

    @Test
    fun tintsRecognizedSkillAndCommandTokens() {
        val skill = Color(0xFF3B82F6)
        val command = Color(0xFF72C5A2)
        val annotated = annotateComposerSlashTokens(
            text = "/babysit /goal keep shipping /unknown and prose",
            skillNames = setOf("babysit"),
            commandNames = setOf("goal"),
            skillColor = skill,
            commandColor = command,
        )

        assertEquals(skill, annotated.spanStyles.single { it.start == 0 }.item.color)
        assertEquals(command, annotated.spanStyles.single { it.start == annotated.text.indexOf("/goal") }.item.color)
        assertTrue(annotated.spanStyles.none { it.start == annotated.text.indexOf("/unknown") })
        assertTrue(annotated.spanStyles.all { it.item.background.alpha > 0f })
    }

    @Test
    fun ignoresPartialSlashTokensUntilComplete() {
        val annotated = annotateComposerSlashTokens(
            text = "/baby",
            skillNames = setOf("babysit"),
            commandNames = emptySet(),
            skillColor = Color.Cyan,
            commandColor = Color.Green,
        )
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
