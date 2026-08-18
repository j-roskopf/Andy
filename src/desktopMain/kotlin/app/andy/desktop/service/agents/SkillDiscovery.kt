package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSkill
import java.io.File

/** Discovers each CLI's native roots plus explicitly supported compatibility roots. */
internal fun discoverAgentSkills(agent: AgentKind, directory: String?): List<AgentSkill> {
    val home = System.getProperty("user.home") ?: return emptyList()
    val workspace = directory?.let(::File)?.takeIf(File::isDirectory)
    val codexHome = System.getenv("CODEX_HOME")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(home, ".codex")
    val roots = skillRootsFor(agent, workspace, File(home), codexHome)
    val discovered = linkedMapOf<String, AgentSkill>()
    roots.forEach { root ->
        if (!root.isDirectory) return@forEach
        root.walkTopDown()
            .maxDepth(8)
            .filter { file -> file.name == "SKILL.md" && file.isFile }
            .take(200)
            .forEach { file ->
                val header = runCatching { file.useLines { lines -> lines.take(24).toList() } }.getOrDefault(emptyList())
                val name = header.firstOrNull { it.startsWith("name:") }
                    ?.substringAfter(':')?.trim()?.trim('"', '\'')
                    ?.takeIf { it.isNotBlank() }
                    ?: file.parentFile.name
                val description = header.firstOrNull { it.startsWith("description:") }
                    ?.substringAfter(':')?.trim()?.trim('"', '\'')
                    .orEmpty()
                val userInvocable = header
                    .firstOrNull { it.startsWith("user-invocable:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.trim('"', '\'')
                    ?.lowercase()
                    ?.let { it != "false" }
                    ?: true
                discovered.putIfAbsent(
                    name.lowercase(),
                    AgentSkill(name, description, file.absolutePath, userInvocable = userInvocable),
                )
            }
    }
    return discovered.values.sortedBy { it.name.lowercase() }
}

/**
 * Skill roots are ordered from the provider's native locations to compatible
 * locations. Earlier roots win when two skills use the same name.
 */
internal fun skillRootsFor(
    agent: AgentKind,
    workspace: File?,
    home: File,
    codexHome: File = File(home, ".codex"),
): List<File> = when (agent) {
    // Codex desktop also exposes portable Agent Skills installed under
    // ~/.agents/skills (for example, skills installed by `npx skills`).
    AgentKind.Codex -> listOf(
        File(codexHome, "skills"),
        File(home, ".agents/skills"),
        File(codexHome, "plugins/cache"),
    )
    // Claude gives personal skills precedence over the project directory and also sees
    // portable skills that its ACP runtime advertises from the shared Agent Skills root.
    AgentKind.ClaudeCode -> listOfNotNull(
        File(home, ".claude/skills"),
        workspace?.let { File(it, ".claude/skills") },
        File(home, ".agents/skills"),
    )
    // Cursor discovers its own and portable Agent Skills at workspace and user
    // scope. It also recognizes compatible Codex skills, so include that root
    // after Cursor's native locations rather than hiding installed workflows.
    AgentKind.Cursor -> listOfNotNull(
        workspace?.let { File(it, ".cursor/skills") },
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".cursor/skills"),
        File(home, ".cursor/skills-cursor"),
        File(home, ".agents/skills"),
        File(codexHome, "skills"),
    )
    // Antigravity CLI loads workspace Agent Skills and its own global root.
    AgentKind.Antigravity -> listOfNotNull(
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".gemini/antigravity-cli/skills"),
    )
    AgentKind.OpenCode -> listOfNotNull(
        workspace?.let { File(it, ".opencode/skills") },
        File(home, ".config/opencode/skills"),
        File(home, ".opencode/skills"),
    )
    AgentKind.Pi -> listOfNotNull(
        workspace?.let { File(it, ".pi/skills") },
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".pi/agent/skills"),
        File(home, ".agents/skills"),
    )
    AgentKind.Hermes -> listOfNotNull(workspace?.let { File(it, ".hermes/skills") }, File(home, ".hermes/skills"))
    AgentKind.OpenClaw -> listOfNotNull(
        workspace?.let { File(it, ".openclaw/skills") },
        workspace?.let { File(it, "skills") },
        File(home, ".openclaw/skills"),
    )
    AgentKind.Goose -> listOfNotNull(
        workspace?.let { File(it, ".goose/skills") },
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".config/goose/skills"),
        File(home, ".goose/skills"),
        File(home, ".agents/skills"),
    )
    AgentKind.Ollama, AgentKind.LMStudio -> emptyList()
}

/** Names of locally installed skills that an ACP provider may accidentally advertise globally. */
internal fun discoverKnownAgentSkillNames(directory: String?): Set<String> = AgentKind.entries
    .flatMap { discoverAgentSkills(it, directory) }
    .mapTo(linkedSetOf()) { it.name.normalizedAgentCommandName() }

internal fun String.normalizedAgentCommandName(): String = trim().trimStart('/', '$').lowercase()
