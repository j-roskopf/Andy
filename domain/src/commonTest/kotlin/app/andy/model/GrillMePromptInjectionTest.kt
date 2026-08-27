package app.andy.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrillMePromptInjectionTest {
    private fun task(
        skills: List<AgentSkill> = emptyList(),
        planMode: Boolean = false,
    ) = AgentTask(
        id = "task-grill",
        title = "grill test",
        prompt = "what would it take to add a grocery list feature",
        agent = AgentKind.Cursor,
        planMode = planMode,
        skills = skills,
        createdAtMillis = 0,
    )

    @Test
    fun promptForCliInjectsGrillMeAddendumWhenSkillAttached() {
        val prompt = task(
            skills = listOf(AgentSkill("grill-me", "", "/tmp/grill-me/SKILL.md")),
            planMode = true,
        ).promptForCli()

        assertTrue(prompt.contains("question.json"))
        assertTrue(prompt.contains("`.andy/task-grill/question.json`"))
        assertTrue(prompt.contains("grill-me is in progress"))
        assertTrue(prompt.contains("defer the full implementation plan"))
        assertTrue(prompt.contains("one decision question at a time"))
    }

    @Test
    fun promptForCliOmitsGrillMeAddendumWithoutSkill() {
        val prompt = task(planMode = true).promptForCli()
        assertFalse(prompt.contains("question.json"))
        assertTrue(prompt.contains("return a concrete implementation plan"))
    }

    @Test
    fun followUpCliPayloadInjectsGrillMeAddendumForSelectedSkills() {
        val payload = task(planMode = true).followUpCliPayload(
            text = "continue grilling",
            imagePaths = emptyList(),
            skills = listOf(AgentSkill("grilling", "", "/tmp/grilling/SKILL.md")),
        )
        assertTrue(payload.prompt.contains("question.json"))
        assertTrue(payload.prompt.contains("grill-me is in progress"))
    }
}
