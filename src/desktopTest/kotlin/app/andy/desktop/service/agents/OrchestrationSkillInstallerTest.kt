package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.hasVendorCli
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestrationSkillInstallerTest {
    @Test
    fun ensureInstalledWritesAllSkillsToAllProviderRoots() {
        val home = File.createTempFile("andy-orch-skills", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            OrchestrationSkillInstaller.ensureInstalled(home)
            AgentKind.entries.filter { it.hasVendorCli }.forEach { kind ->
                val root = skillRootsFor(kind, workspace = null, home = home, codexHome = File(home, ".codex"))
                    .first()
                OrchestrationSkillInstaller.skills.forEach { (name, content) ->
                    val dest = File(root, "$name/SKILL.md")
                    assertTrue(dest.isFile, "missing $dest for $kind")
                    assertEquals(content, dest.readText())
                }
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun reRunningUnchangedContentDoesNotRewrite() {
        val home = File.createTempFile("andy-orch-skills-mtime", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            OrchestrationSkillInstaller.ensureInstalled(home)
            val sample = File(
                skillRootsFor(AgentKind.Codex, null, home, File(home, ".codex")).first(),
                "andy-handoff/SKILL.md",
            )
            val mtimeBefore = sample.lastModified()
            Thread.sleep(20)
            OrchestrationSkillInstaller.ensureInstalled(home)
            assertEquals(mtimeBefore, sample.lastModified(), "unchanged content should not rewrite")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun deletingASkillFileIsRestoredOnNextEnsureInstalled() {
        val home = File.createTempFile("andy-orch-skills-restore", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            OrchestrationSkillInstaller.ensureInstalled(home)
            val dest = File(
                skillRootsFor(AgentKind.ClaudeCode, null, home, File(home, ".codex")).first(),
                "andy-loop/SKILL.md",
            )
            assertTrue(dest.delete())
            OrchestrationSkillInstaller.ensureInstalled(home)
            assertTrue(dest.isFile)
            assertEquals(OrchestrationSkillInstaller.skills.getValue("andy-loop"), dest.readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun changingInstalledContentIsOverwrittenOnNextEnsureInstalled() {
        val home = File.createTempFile("andy-orch-skills-overwrite", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            OrchestrationSkillInstaller.ensureInstalled(home)
            val dest = File(
                skillRootsFor(AgentKind.Cursor, null, home, File(home, ".codex")).first(),
                "andy-advisor/SKILL.md",
            )
            dest.writeText("stale local edit\n")
            OrchestrationSkillInstaller.ensureInstalled(home)
            assertEquals(OrchestrationSkillInstaller.skills.getValue("andy-advisor"), dest.readText())
        } finally {
            home.deleteRecursively()
        }
    }
}
