package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.composerSkillsForSlashMenu
import app.andy.model.isGrillMeSkillName
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillDiscoveryTest {
    @Test
    fun codexDiscoversPortableGrillSkillsFromAgentsDirectory() {
        val home = createTempDirectory("andy-skill-home").toFile()
        val agentsSkills = File(home, ".agents/skills").apply { mkdirs() }
        File(agentsSkills, "grill-me/SKILL.md").apply {
            parentFile.mkdirs()
            writeText("---\nname: grill-me\ndescription: sharpen the plan\n---\n")
        }
        File(agentsSkills, "grilling/SKILL.md").apply {
            parentFile.mkdirs()
            writeText("---\nname: grilling\ndescription: interview the user\n---\n")
        }

        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            val discovered = discoverAgentSkills(AgentKind.Codex, directory = null)
            assertTrue(discovered.any { isGrillMeSkillName(it.name) })
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", previousHome)
            }
            home.deleteRecursively()
        }
    }

    @Test
    fun honorsUserInvocableFalseInFrontmatter() {
        val home = createTempDirectory("andy-skill-invocable").toFile()
        val skillsRoot = File(home, ".codex/skills").apply { mkdirs() }
        File(skillsRoot, "andy-orchestration/SKILL.md").apply {
            parentFile.mkdirs()
            writeText(
                """
                ---
                name: andy-orchestration
                description: reference only
                user-invocable: false
                ---
                # ref
                """.trimIndent(),
            )
        }
        File(skillsRoot, "andy-handoff/SKILL.md").apply {
            parentFile.mkdirs()
            writeText(
                """
                ---
                name: andy-handoff
                description: handoff
                user-invocable: true
                ---
                # handoff
                """.trimIndent(),
            )
        }

        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            val discovered = discoverAgentSkills(AgentKind.Codex, directory = null)
            val orchestration = discovered.single { it.name == "andy-orchestration" }
            val handoff = discovered.single { it.name == "andy-handoff" }
            assertFalse(orchestration.userInvocable)
            assertTrue(handoff.userInvocable)
            assertEquals(
                listOf("andy-handoff"),
                composerSkillsForSlashMenu(discovered, commands = emptyList()).map { it.name },
            )
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", previousHome)
            }
            home.deleteRecursively()
        }
    }
}
