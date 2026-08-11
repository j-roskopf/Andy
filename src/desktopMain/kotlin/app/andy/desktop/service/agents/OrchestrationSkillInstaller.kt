package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import java.io.File

/**
 * Installs Andy orchestration skills into each provider's first [skillRootsFor] root.
 *
 * Content-hash self-healing: deleted or stale SKILL.md files are rewritten on launch.
 * Skills are Andy-authored; local edits are overwritten on the next app / andyd start.
 */
object OrchestrationSkillInstaller {
    /** Directory name → SKILL.md body (includes frontmatter). */
    val skills: Map<String, String> = mapOf(
        "andy-orchestration" to ANDY_ORCHESTRATION_SKILL,
        "andy-handoff" to ANDY_HANDOFF_SKILL,
        "andy-loop" to ANDY_LOOP_SKILL,
        "andy-advisor" to ANDY_ADVISOR_SKILL,
        "andy-committee" to ANDY_COMMITTEE_SKILL,
    )

    fun ensureInstalled(home: File = File(System.getProperty("user.home"))) {
        val codexHome = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".codex")
        AgentKind.entries.forEach { kind ->
            val root = skillRootsFor(kind, workspace = null, home = home, codexHome = codexHome)
                .firstOrNull() ?: return@forEach
            skills.forEach { (name, content) ->
                val dest = File(root, "$name/SKILL.md")
                val existing = dest.takeIf { it.isFile }?.readText()
                if (existing != content) {
                    dest.parentFile?.mkdirs()
                    runCatching { dest.writeText(content) }
                }
            }
        }
    }

    /** True when at least one provider skill root has every Andy orchestration SKILL.md. */
    fun isInstalled(home: File = File(System.getProperty("user.home"))): Boolean {
        val codexHome = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".codex")
        return AgentKind.entries.any { kind ->
            val root = skillRootsFor(kind, workspace = null, home = home, codexHome = codexHome)
                .firstOrNull() ?: return@any false
            skills.keys.all { name -> File(root, "$name/SKILL.md").isFile }
        }
    }
}
