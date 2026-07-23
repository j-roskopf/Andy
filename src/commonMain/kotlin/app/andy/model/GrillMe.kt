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
 * Andy runs provider CLIs headlessly. Portable grill-me skills expect an interactive
 * `/grilling` slash command, so specs inject this addendum and use [andyUserInputPromptHint].
 */
internal fun grillMeHeadlessPromptAddendum(): String = buildString {
    append(
        """
        Run a relentless grill-me interview before writing the spec. Ask one decision question at a time, include your recommended answer in the visible text, and wait for the user's reply before continuing.
        If a fact can be found by exploring the workspace, look it up instead of asking.
        Do not use slash commands such as /grill-me or /grilling — they are unavailable in this headless run.
        """.trimIndent(),
    )
    append('\n')
    append(andyUserInputPromptHint())
    append(
        """

        Continue grilling until you reach shared understanding, then return the complete implementation specification as the final response without another decision checkpoint.
        """.trimIndent(),
    )
}

/** Wire format consumed by Andy's provider-neutral decision checkpoint UI. */
internal fun andyUserInputPromptHint(): String = """
When you need a user decision, emit one checkpoint in this exact format (2-3 options; only the question id is snake_case):

<andy_user_input>{"questions":[{"id":"platform_scope","question":"Which platforms should v1 ship on?","options":[{"label":"Desktop only (Recommended)"},{"label":"Desktop and web"}]}]}</andy_user_input>

Each option must use a short, human-readable label (not snake_case). Put your recommendation in the question text and mark the recommended option's label with "(Recommended)".

Use the exact closing tag </andy_user_input>. Stop the turn after the checkpoint so Andy can collect the answer and resume the run.
""".trimIndent()

/** Removes decision-checkpoint wire format from text shown in chat bubbles. */
fun stripDecisionCheckpointMarkup(text: String): String =
    text.replace(Regex("""<andy_user_input>[\s\S]*?</andy_user(?:_input)?>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<andy_user_input>[\s\S]*$""", RegexOption.IGNORE_CASE), "")
        .trim()
