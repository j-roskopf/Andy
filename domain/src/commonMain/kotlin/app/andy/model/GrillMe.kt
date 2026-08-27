package app.andy.model

/** Command for installing the portable grill-me workflow into this provider's global skill directory. */
fun AgentKind.grillMeInstallCommand(): String =
    "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent ${grillMeSkillsAgent()}"

private fun AgentKind.grillMeSkillsAgent(): String = when (this) {
    AgentKind.ClaudeCode -> "claude-code"
    AgentKind.Codex -> "codex"
    AgentKind.Cursor -> "cursor"
    AgentKind.Antigravity -> "antigravity-cli"
    AgentKind.OpenCode -> "opencode"
    AgentKind.Pi -> "pi"
    AgentKind.Hermes -> "hermes"
    AgentKind.OpenClaw -> "openclaw"
    AgentKind.Goose -> "goose"
    AgentKind.Ollama, AgentKind.LMStudio -> "opencode"
}

fun isGrillMeSkillName(name: String): Boolean =
    name.equals("grill-me", ignoreCase = true) || name.equals("grilling", ignoreCase = true)

fun hasGrillMeSkills(skills: List<AgentSkill>): Boolean =
    skills.any { isGrillMeSkillName(it.name) }

/**
 * Where Spec runs must leave `plan.md`. Plan mode is always on for Spec, and provider
 * plan UIs otherwise stash plans elsewhere — say the path explicitly in the same message.
 */
fun specPlanWriteInstruction(
    artifactRelPath: String,
    including: String = "",
): String {
    val detail = including.trim().takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
    return "Even though plan mode is active, write the complete implementation specification " +
        "to `$artifactRelPath/plan.md`$detail, then stop (exit the session). " +
        "Do not use the provider's default plan path or plan UI. Do not implement the plan."
}

internal fun grillMeDecisionDiscipline(): String = """
During the grill-me interview:
- Ask one decision question at a time and wait for the user's answer before continuing.
- If a fact can be found by exploring the workspace, look it up instead of asking.
- Do not output a full implementation plan, phase table, or task breakdown until grilling is complete.
- You may include at most five bullets of "decisions so far" in chat; keep them brief.
- End each chat message with the single question you are asking; any summary must appear above the question, never below it.
""".trimIndent()

/** Routes each grill-me decision through Andy's decision card instead of burying it in chat prose. */
internal fun grillMeQuestionArtifactAddendum(artifactRelPath: String): String = buildString {
    append(grillMeDecisionDiscipline())
    append("\n\n")
    append(
        """
        For each decision during the grill-me interview, write exactly one JSON file to `$artifactRelPath/question.json` with this shape (2-3 options; only the question id is snake_case):

        {"questions":[{"id":"platform_scope","question":"Which platforms should v1 ship on?","options":[{"label":"Desktop only (Recommended)"},{"label":"Desktop and web"}]}]}

        Each option must use a short, human-readable label (not snake_case). Put your recommendation in the question text and mark the recommended option's label with "(Recommended)".

        Then stop and wait. Andy will collect the answer and write `$artifactRelPath/answer.json` (and/or paste the answer into this terminal).
        """.trimIndent(),
    )
}

/** Injected when grill-me skills are attached to an interactive agent chat. */
fun grillMeChatPromptAddendum(artifactRelPath: String): String =
    grillMeQuestionArtifactAddendum(artifactRelPath)

/**
 * Spec phase runs in an interactive terminal. Each grill-me decision goes through
 * `question.json` so Andy can surface a decision card above the transcript.
 */
fun grillMeInteractivePromptAddendum(artifactRelPath: String): String = buildString {
    append("Run a relentless grill-me interview before writing the spec.\n\n")
    append(grillMeQuestionArtifactAddendum(artifactRelPath))
    append("\n\nContinue grilling until you reach shared understanding. ")
    append(
        specPlanWriteInstruction(
            artifactRelPath,
            including = "including interfaces, edge cases, and verification steps",
        ),
    )
}

@Deprecated("Use grillMeInteractivePromptAddendum", ReplaceWith("grillMeInteractivePromptAddendum(artifactRelPath)"))
fun grillMeHeadlessPromptAddendum(): String = grillMeInteractivePromptAddendum(".andy/<taskId>")

/** Instruct agents to emit mid-run questions as artifact files during automated phases. */
fun andyQuestionArtifactHint(artifactRelPath: String): String = """
When you need a user decision during an automated Andy workflow phase, write exactly one JSON file to `$artifactRelPath/question.json` with this shape (2-3 options; only the question id is snake_case):

{"questions":[{"id":"platform_scope","question":"Which platforms should v1 ship on?","options":[{"label":"Desktop only (Recommended)"},{"label":"Desktop and web"}]}]}

Each option must use a short, human-readable label (not snake_case). Put your recommendation in the question text and mark the recommended option's label with "(Recommended)".

Then stop and wait. Andy will collect the answer and write `$artifactRelPath/answer.json` (and/or paste the answer into this terminal).
""".trimIndent()

@Deprecated("Stdout markup checkpoints are replaced by question.json artifacts")
fun andyUserInputPromptHint(): String = andyQuestionArtifactHint(".andy/<taskId>")

/** Removes legacy decision-checkpoint wire format from text shown in chat bubbles. */
fun stripDecisionCheckpointMarkup(text: String): String =
    text.replace(Regex("""<andy_user_input>[\s\S]*?</andy_user(?:_input)?>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<andy_user_input>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        .trim()
