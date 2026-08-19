package app.andy.model

data class AutomationTemplate(
    val title: String,
    val prompt: String,
    val schedule: AutomationSchedule = AutomationSchedule.Daily,
    val runHour: Int = 9,
    val runMinute: Int = 0,
)

val AutomationTemplates: List<AutomationTemplate> = listOf(
    AutomationTemplate(
        title = "Triage new crashes",
        prompt = """
            Look for new crashes and ANRs in this Andy project using Andy's crash inspector and logcat tools (list_crashes / get_crash, not a third-party tracker).

            For each new or repeating crash:
            1. Summarize the stack and the most likely root cause.
            2. Say whether it is new since the last look, a regression, or a duplicate.
            3. Propose the smallest safe fix, or file a Kanban card if you cannot fix it now.

            Do not change unrelated code. If there are no new crashes, say so and stop.
        """.trimIndent(),
    ),
    AutomationTemplate(
        title = "Update dependencies",
        prompt = """
            Review this repository's dependency files (Gradle, npm, CocoaPods, or the repo's equivalent).

            1. List outdated direct dependencies with current vs latest versions.
            2. Apply safe, non-major bumps that look low-risk.
            3. Run the project's usual compile/test command if one is obvious.
            4. Open or update a PR if the repo uses git remotes; otherwise leave a clear summary of commits.

            Skip major/breaking upgrades unless they are required to compile. Do not force-push.
        """.trimIndent(),
        schedule = AutomationSchedule.Weekly(dayOfWeek = 1),
        runHour = 9,
        runMinute = 0,
    ),
    AutomationTemplate(
        title = "Daily standup summary",
        prompt = """
            Write a standup summary for this Andy project using recent chats, Kanban, git history, and any linked PRs.

            Include:
            - What landed since yesterday
            - What is in progress (Kanban Doing / active chats)
            - Blockers (failed runs, crashes, review comments)
            - Suggested focus for today

            Keep it short and concrete.
        """.trimIndent(),
    ),
)
