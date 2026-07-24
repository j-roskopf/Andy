package app.andy.model

/** Command for installing the portable grill-me workflow into this provider's global skill directory. */
fun AgentKind.grillMeInstallCommand(): String =
    "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent ${grillMeSkillsAgent()}"

private fun AgentKind.grillMeSkillsAgent(): String = when (this) {
    AgentKind.ClaudeCode -> "claude-code"
    AgentKind.Codex -> "codex"
    AgentKind.Cursor -> "cursor"
    AgentKind.Antigravity -> "antigravity-cli"
}

internal fun isGrillMeSkillName(name: String): Boolean =
    name.equals("grill-me", ignoreCase = true) || name.equals("grilling", ignoreCase = true)

/**
 * Spec phase runs in an interactive terminal. Grill-me happens natively in the TUI;
 * when the agent needs an Andy-mediated decision during an *automated* phase it writes
 * `.andy/<taskId>/question.json` instead.
 */
internal fun grillMeInteractivePromptAddendum(artifactRelPath: String): String = buildString {
    append(
        """
        Run a relentless grill-me interview before writing the spec. Ask one decision question at a time in the interactive CLI, include your recommended answer, and wait for the user's reply before continuing.
        If a fact can be found by exploring the workspace, look it up instead of asking.
        """.trimIndent(),
    )
    append('\n')
    append(
        """

        Continue grilling until you reach shared understanding, then write the complete implementation specification to `$artifactRelPath/plan.md` and stop (exit the session). Do not implement the plan.
        """.trimIndent(),
    )
}

@Deprecated("Use grillMeInteractivePromptAddendum", ReplaceWith("grillMeInteractivePromptAddendum(artifactRelPath)"))
internal fun grillMeHeadlessPromptAddendum(): String = grillMeInteractivePromptAddendum(".andy/<taskId>")

/** Instruct agents to emit mid-run questions as artifact files during automated phases. */
internal fun andyQuestionArtifactHint(artifactRelPath: String): String = """
When you need a user decision during an automated Andy workflow phase, write exactly one JSON file to `$artifactRelPath/question.json` with this shape (2-3 options; only the question id is snake_case):

{"questions":[{"id":"platform_scope","question":"Which platforms should v1 ship on?","options":[{"label":"Desktop only (Recommended)"},{"label":"Desktop and web"}]}]}

Each option must use a short, human-readable label (not snake_case). Put your recommendation in the question text and mark the recommended option's label with "(Recommended)".

Then stop and wait. Andy will collect the answer and write `$artifactRelPath/answer.json` (and/or paste the answer into this terminal).
""".trimIndent()

@Deprecated("Stdout markup checkpoints are replaced by question.json artifacts")
internal fun andyUserInputPromptHint(): String = andyQuestionArtifactHint(".andy/<taskId>")

/** Removes legacy decision-checkpoint wire format from text shown in chat bubbles. */
fun stripDecisionCheckpointMarkup(text: String): String =
    text.replace(Regex("""<andy_user_input>[\s\S]*?</andy_user(?:_input)?>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<andy_user_input>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        .trim()
