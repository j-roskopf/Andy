package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus

/**
 * Herdr-style screen detection: priority rules over buffer regions + OSC title/progress.
 *
 * Ported from https://github.com/ogulcancelik/herdr (Apache-2.0) manifests for
 * Claude / Codex / Cursor / Antigravity, with Andy-specific working/idle extras.
 *
 * Known agent + no match → idle fallback (strict blocked: only explicit blocker rules).
 */
internal enum class ScreenState {
    Idle,
    Working,
    Blocked,
    Unknown,
}

internal sealed class ScreenRegion {
    data object WholeRecent : ScreenRegion()
    data object OscTitle : ScreenRegion()
    data object OscProgress : ScreenRegion()
    data object AfterLastHorizontalRule : ScreenRegion()
    data object AfterLastPromptMarker : ScreenRegion()
    data object PromptBoxBody : ScreenRegion()
    data class BottomNonEmpty(val count: Int) : ScreenRegion()
    data class BottomLines(val count: Int) : ScreenRegion()
}

internal data class ScreenGate(
    val contains: List<String> = emptyList(),
    val regex: List<Regex> = emptyList(),
    val lineRegex: List<Regex> = emptyList(),
    val all: List<ScreenGate> = emptyList(),
    val any: List<ScreenGate> = emptyList(),
    val not: List<ScreenGate> = emptyList(),
)

internal data class ScreenRule(
    val id: String,
    val state: ScreenState,
    val priority: Int,
    val region: ScreenRegion,
    val gate: ScreenGate,
    val visibleBlocker: Boolean = false,
    val visibleWorking: Boolean = false,
    val visibleIdle: Boolean = false,
    val skipStateUpdate: Boolean = false,
)

internal data class DetectionInput(
    val screen: String,
    val oscTitle: String = "",
    val oscProgress: String = "",
)

internal data class ManifestMatch(
    val state: ScreenState,
    val ruleId: String? = null,
    val visibleBlocker: Boolean = false,
    val visibleWorking: Boolean = false,
    val visibleIdle: Boolean = false,
    val skipStateUpdate: Boolean = false,
    /** True when no rule matched and the agent fell back to idle. */
    val idleFallback: Boolean = false,
)

internal fun evaluateScreenManifest(agent: AgentKind, input: DetectionInput): ManifestMatch {
    val rules = screenManifestFor(agent)
    var best: ScreenRule? = null
    for (rule in rules) {
        val regionText = extractRegion(input, rule.region)
        if (!gateMatches(rule.gate, regionText)) continue
        val prev = best
        if (prev == null || rule.priority > prev.priority) {
            best = rule
        }
    }
    val matched = best ?: return ManifestMatch(
        state = ScreenState.Idle,
        idleFallback = true,
    )
    return ManifestMatch(
        state = matched.state,
        ruleId = matched.id,
        visibleBlocker = matched.visibleBlocker && matched.state == ScreenState.Blocked,
        visibleWorking = matched.visibleWorking && matched.state == ScreenState.Working,
        visibleIdle = matched.visibleIdle && matched.state == ScreenState.Idle,
        skipStateUpdate = matched.skipStateUpdate,
    )
}

internal fun ManifestMatch.toAgentStatusOrNull(): AgentStatus? = when {
    skipStateUpdate -> null
    state == ScreenState.Blocked -> AgentStatus.Blocked
    state == ScreenState.Working -> AgentStatus.Working
    state == ScreenState.Idle || idleFallback -> AgentStatus.Done
    else -> null
}

internal fun extractRegion(input: DetectionInput, region: ScreenRegion): String = when (region) {
    ScreenRegion.WholeRecent -> input.screen
    ScreenRegion.OscTitle -> input.oscTitle
    ScreenRegion.OscProgress -> input.oscProgress
    ScreenRegion.AfterLastHorizontalRule -> afterLastHorizontalRule(input.screen)
    ScreenRegion.AfterLastPromptMarker -> afterLastPromptMarker(input.screen)
    ScreenRegion.PromptBoxBody -> promptBoxBody(input.screen).orEmpty()
    is ScreenRegion.BottomNonEmpty -> bottomNonEmptyLines(input.screen, region.count)
    is ScreenRegion.BottomLines -> bottomLines(input.screen, region.count)
}

internal fun gateMatches(gate: ScreenGate, text: String): Boolean {
    val lower = text.lowercase()
    if (gate.contains.any { !lower.contains(it.lowercase()) }) return false
    if (gate.regex.any { !it.containsMatchIn(text) }) return false
    if (gate.lineRegex.any { regex -> text.lineSequence().none { regex.containsMatchIn(it) } }) return false
    if (gate.all.any { !gateMatches(it, text) }) return false
    if (gate.any.isNotEmpty() && gate.any.none { gateMatches(it, text) }) return false
    if (gate.not.any { gateMatches(it, text) }) return false
    return true
}

internal fun bottomLines(content: String, count: Int): String {
    if (count <= 0 || content.isEmpty()) return ""
    val lines = content.lines()
    val start = (lines.size - count).coerceAtLeast(0)
    return lines.drop(start).joinToString("\n")
}

internal fun bottomNonEmptyLines(content: String, count: Int): String {
    if (count <= 0 || content.isEmpty()) return ""
    val lines = content.lines()
    var seen = 0
    var start = -1
    for (i in lines.indices.reversed()) {
        if (lines[i].isNotBlank()) {
            start = i
            seen++
            if (seen >= count) break
        }
    }
    if (start < 0) return ""
    return lines.drop(start).joinToString("\n")
}

internal fun afterLastHorizontalRule(content: String): String {
    val lines = content.lines()
    var lastRuleEnd = 0
    var offset = 0
    for (line in lines) {
        val next = offset + line.length + 1
        if (isHorizontalRule(line)) {
            lastRuleEnd = next.coerceAtMost(content.length)
        }
        offset = next
    }
    return if (lastRuleEnd >= content.length) "" else content.substring(lastRuleEnd)
}

internal fun afterLastPromptMarker(content: String): String {
    val lines = content.lines()
    val index = lines.indexOfLast { isCodexPromptLine(it) }
    if (index < 0) return content
    return lines.drop(index + 1).joinToString("\n")
}

internal fun promptBoxBody(content: String): String? {
    val lines = content.lines()
    val top = promptBoxTopBorderIndex(lines) ?: return null
    val endRelative = lines.drop(top + 1).indexOfFirst { isHorizontalRule(it) }
    val endIndex = if (endRelative >= 0) top + 1 + endRelative else lines.size
    return lines.subList(top + 1, endIndex).joinToString("\n")
}

private fun promptBoxTopBorderIndex(lines: List<String>): Int? {
    var borderCount = 0
    for (index in lines.indices.reversed()) {
        if (isHorizontalRule(lines[index])) {
            borderCount++
            if (borderCount == 2) return index
        }
    }
    return null
}

internal fun isHorizontalRule(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    var ruleChars = 0
    for (ch in trimmed) {
        if (ch == '─') ruleChars++ else break
    }
    if (ruleChars == 0) return false
    val suffix = trimmed.drop(ruleChars).trimStart()
    return suffix.isEmpty() || ruleChars >= 3
}

private fun isCodexPromptLine(line: String): Boolean =
    line == "›" || line.startsWith("› ")

internal fun screenManifestFor(agent: AgentKind): List<ScreenRule> = when (agent) {
    AgentKind.ClaudeCode -> ClaudeScreenManifest
    AgentKind.Codex -> CodexScreenManifest
    AgentKind.Cursor -> CursorScreenManifest
    AgentKind.Antigravity -> AntigravityScreenManifest
}

// region Ported Herdr manifests (+ Andy extras)

private val ClaudeScreenManifest: List<ScreenRule> = listOf(
    ScreenRule(
        id = "osc_title_working",
        state = ScreenState.Working,
        priority = 1100,
        region = ScreenRegion.OscTitle,
        visibleWorking = true,
        gate = ScreenGate(regex = listOf(Regex("""^[\u2800-\u28FF] """))),
    ),
    ScreenRule(
        id = "btw_overlay_working",
        state = ScreenState.Working,
        priority = 975,
        region = ScreenRegion.BottomNonEmpty(5),
        visibleWorking = true,
        gate = ScreenGate(
            lineRegex = listOf(
                Regex("""^\s*/btw(?:\s|$)"""),
                Regex("""(?i)esc to close\s*$"""),
            ),
        ),
    ),
    ScreenRule(
        id = "transcript_viewer",
        state = ScreenState.Unknown,
        priority = 1000,
        region = ScreenRegion.BottomNonEmpty(3),
        skipStateUpdate = true,
        gate = ScreenGate(
            contains = listOf("showing detailed transcript"),
            any = listOf(
                ScreenGate(contains = listOf("ctrl+o", "to toggle")),
                ScreenGate(contains = listOf("ctrl+e", "show all")),
                ScreenGate(contains = listOf("ctrl+e", "collapse")),
                ScreenGate(contains = listOf("↑↓ scroll")),
                ScreenGate(contains = listOf("? for shortcuts")),
            ),
        ),
    ),
    ScreenRule(
        id = "live_blocked_form",
        state = ScreenState.Blocked,
        priority = 980,
        region = ScreenRegion.AfterLastHorizontalRule,
        visibleBlocker = true,
        gate = ScreenGate(
            contains = listOf("enter to select", "esc to cancel"),
            any = listOf(
                ScreenGate(contains = listOf("tab/arrow keys to navigate")),
                ScreenGate(contains = listOf("arrow keys to navigate")),
                ScreenGate(contains = listOf("arrows to navigate")),
                ScreenGate(contains = listOf("↑/↓ to navigate")),
                ScreenGate(contains = listOf("↑↓ to navigate")),
            ),
        ),
    ),
    ScreenRule(
        id = "dynamic_workflow_prompt",
        state = ScreenState.Blocked,
        priority = 980,
        region = ScreenRegion.WholeRecent,
        visibleBlocker = true,
        gate = ScreenGate(contains = listOf("run a dynamic workflow?", "esc to cancel")),
    ),
    ScreenRule(
        id = "live_prompt_box",
        state = ScreenState.Idle,
        priority = 950,
        region = ScreenRegion.PromptBoxBody,
        visibleIdle = true,
        gate = ScreenGate(
            lineRegex = listOf(Regex("""^\s*❯""")),
            not = listOf(
                ScreenGate(contains = listOf("enter to select")),
                ScreenGate(contains = listOf("esc to cancel")),
                ScreenGate(contains = listOf("tab/arrow keys")),
                ScreenGate(contains = listOf("arrow keys to navigate")),
                ScreenGate(contains = listOf("↑/↓ to navigate")),
            ),
        ),
    ),
    ScreenRule(
        id = "bash_permission_prompt",
        state = ScreenState.Blocked,
        priority = 850,
        region = ScreenRegion.WholeRecent,
        visibleBlocker = true,
        gate = ScreenGate(
            contains = listOf("do you want to proceed?"),
            any = listOf(
                ScreenGate(contains = listOf("bash command")),
                ScreenGate(contains = listOf("bash(")),
                ScreenGate(contains = listOf("contains expansion")),
                ScreenGate(contains = listOf("tab to amend")),
                ScreenGate(contains = listOf("ctrl+e to explain")),
            ),
            all = listOf(
                ScreenGate(
                    any = listOf(
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*❯?\s*yes\b"""))),
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*1\.\s*yes\b"""))),
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*2\.\s*no\b"""))),
                    ),
                ),
            ),
        ),
    ),
    ScreenRule(
        id = "generic_permission_prompt",
        state = ScreenState.Blocked,
        priority = 840,
        region = ScreenRegion.AfterLastHorizontalRule,
        visibleBlocker = true,
        gate = ScreenGate(
            contains = listOf("do you want to proceed?", "esc to cancel"),
            all = listOf(
                ScreenGate(
                    any = listOf(
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*❯?\s*1\.\s*yes\b"""))),
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*2\.\s*yes\b"""))),
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*2\.\s*no\b"""))),
                        ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*3\.\s*no\b"""))),
                    ),
                ),
            ),
        ),
    ),
    ScreenRule(
        id = "legacy_no_prompt_blocker",
        state = ScreenState.Blocked,
        priority = 300,
        region = ScreenRegion.WholeRecent,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(
                    contains = listOf("do you want to"),
                    any = listOf(
                        ScreenGate(contains = listOf("yes")),
                        ScreenGate(contains = listOf("❯")),
                    ),
                ),
                ScreenGate(
                    contains = listOf("would you like to"),
                    any = listOf(
                        ScreenGate(contains = listOf("yes")),
                        ScreenGate(contains = listOf("❯")),
                    ),
                ),
                ScreenGate(contains = listOf("waiting for permission")),
                ScreenGate(contains = listOf("do you want to allow this connection?")),
                ScreenGate(contains = listOf("tab to amend")),
                ScreenGate(contains = listOf("ctrl+e to explain")),
                ScreenGate(contains = listOf("do you want to proceed?", "esc to cancel")),
                ScreenGate(contains = listOf("review your answers")),
                ScreenGate(contains = listOf("skip interview and plan immediately")),
                // Andy extras formerly in ScrapeRules
                ScreenGate(contains = listOf("allow this action")),
                ScreenGate(contains = listOf("trust this folder")),
                ScreenGate(contains = listOf("quick safety check")),
                ScreenGate(contains = listOf("yes, i accept")),
                ScreenGate(contains = listOf("no, exit")),
            ),
            not = listOf(ScreenGate(regex = listOf(Regex("""(?m)^\s*❯\s*$""")))),
        ),
    ),
    ScreenRule(
        id = "osc_title_idle",
        state = ScreenState.Idle,
        priority = 250,
        region = ScreenRegion.OscTitle,
        visibleIdle = true,
        gate = ScreenGate(regex = listOf(Regex("""^\u2733 """))),
    ),
    ScreenRule(
        id = "osc_progress_idle",
        state = ScreenState.Idle,
        priority = 250,
        region = ScreenRegion.OscProgress,
        gate = ScreenGate(regex = listOf(Regex("""^4;0"""))),
    ),
    // Andy status-line working cues (not in Herdr Claude manifest).
    ScreenRule(
        id = "andy_status_line_working",
        state = ScreenState.Working,
        priority = 200,
        region = ScreenRegion.BottomNonEmpty(6),
        visibleWorking = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(contains = listOf("perambulat")),
                ScreenGate(contains = listOf("thinking more")),
                ScreenGate(regex = listOf(Regex("""✻\s+\w+ing\b"""))),
                ScreenGate(regex = listOf(Regex("""(?i)↓\s*\d+\s*tokens"""))),
            ),
        ),
    ),
    ScreenRule(
        id = "andy_plain_prompt_idle",
        state = ScreenState.Idle,
        priority = 100,
        region = ScreenRegion.BottomNonEmpty(3),
        visibleIdle = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(lineRegex = listOf(Regex(""">\s*$"""))),
                ScreenGate(lineRegex = listOf(Regex("""╭─"""))),
            ),
            not = listOf(
                ScreenGate(contains = listOf("perambulat")),
                ScreenGate(contains = listOf("thinking more")),
                ScreenGate(contains = listOf("do you want to proceed")),
            ),
        ),
    ),
)

private val CodexScreenManifest: List<ScreenRule> = listOf(
    ScreenRule(
        id = "osc_title_blocked",
        state = ScreenState.Blocked,
        priority = 1100,
        region = ScreenRegion.OscTitle,
        visibleBlocker = true,
        gate = ScreenGate(contains = listOf("Action Required")),
    ),
    ScreenRule(
        id = "osc_title_working",
        state = ScreenState.Working,
        priority = 1050,
        region = ScreenRegion.OscTitle,
        visibleWorking = true,
        gate = ScreenGate(regex = listOf(Regex("""(?:^| )[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏](?: |$)"""))),
    ),
    ScreenRule(
        id = "transcript_viewer",
        state = ScreenState.Unknown,
        priority = 1000,
        region = ScreenRegion.AfterLastPromptMarker,
        skipStateUpdate = true,
        gate = ScreenGate(
            contains = listOf("↑/↓ to scroll", "pgup/pgdn to", "home/end to jump", "q to quit"),
            any = listOf(
                ScreenGate(contains = listOf("esc to edit prev")),
                ScreenGate(contains = listOf("esc/← to edit prev")),
            ),
        ),
    ),
    ScreenRule(
        id = "live_strong_blocker",
        state = ScreenState.Blocked,
        priority = 900,
        region = ScreenRegion.AfterLastPromptMarker,
        visibleBlocker = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(contains = listOf("press enter to confirm or esc to cancel")),
                ScreenGate(contains = listOf("enter to submit answer")),
                ScreenGate(contains = listOf("enter to submit all")),
                ScreenGate(contains = listOf("allow command?")),
            ),
        ),
    ),
    ScreenRule(
        id = "weak_blocker",
        state = ScreenState.Blocked,
        priority = 600,
        region = ScreenRegion.WholeRecent,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(contains = listOf("[y/n]")),
                ScreenGate(contains = listOf("yes (y)")),
                ScreenGate(
                    contains = listOf("do you want to"),
                    any = listOf(
                        ScreenGate(contains = listOf("yes")),
                        ScreenGate(contains = listOf("❯")),
                    ),
                ),
                ScreenGate(
                    contains = listOf("would you like to"),
                    any = listOf(
                        ScreenGate(contains = listOf("yes")),
                        ScreenGate(contains = listOf("❯")),
                    ),
                ),
                ScreenGate(contains = listOf("approve this command")),
                ScreenGate(contains = listOf("allow command")),
            ),
        ),
    ),
    ScreenRule(
        id = "screen_working_fallback",
        state = ScreenState.Working,
        priority = 500,
        region = ScreenRegion.BottomNonEmpty(3),
        visibleWorking = true,
        gate = ScreenGate(
            lineRegex = listOf(Regex("""^[•◦]\s+Working \([^)]*esc to interrupt\)(?: · .*)?$""")),
            not = listOf(ScreenGate(contains = listOf("■ Conversation interrupted"))),
        ),
    ),
    ScreenRule(
        id = "osc_title_idle",
        state = ScreenState.Idle,
        priority = 100,
        region = ScreenRegion.OscTitle,
        visibleIdle = true,
        gate = ScreenGate(
            regex = listOf(Regex("""\S""")),
            not = listOf(
                ScreenGate(regex = listOf(Regex("""(?:^| )[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏](?: |$)"""))),
                ScreenGate(contains = listOf("Action Required")),
            ),
        ),
    ),
    ScreenRule(
        id = "andy_plain_prompt_idle",
        state = ScreenState.Idle,
        priority = 80,
        region = ScreenRegion.BottomNonEmpty(3),
        visibleIdle = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(lineRegex = listOf(Regex("""›\s*$"""))),
                ScreenGate(lineRegex = listOf(Regex(""">\s*$"""))),
            ),
        ),
    ),
)

private val CursorScreenManifest: List<ScreenRule> = listOf(
    ScreenRule(
        id = "write_file_approval",
        state = ScreenState.Blocked,
        priority = 320,
        region = ScreenRegion.BottomNonEmpty(8),
        visibleBlocker = true,
        gate = ScreenGate(
            contains = listOf("write to this file?", "proceed (y)"),
            any = listOf(
                ScreenGate(contains = listOf("reject & propose changes")),
                ScreenGate(contains = listOf("esc or n or p")),
                ScreenGate(contains = listOf("add write(")),
            ),
        ),
    ),
    ScreenRule(
        id = "approval_prompt",
        state = ScreenState.Blocked,
        priority = 300,
        region = ScreenRegion.WholeRecent,
        visibleBlocker = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(
                    contains = listOf("waiting for approval", "run this command?"),
                    any = listOf(
                        ScreenGate(contains = listOf("run (once) (y)")),
                        ScreenGate(contains = listOf("skip (esc or n)")),
                    ),
                ),
                ScreenGate(contains = listOf("(y) (enter)")),
                ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*allow .*\(y\)"""))),
                ScreenGate(contains = listOf("keep (n)")),
                ScreenGate(contains = listOf("skip (esc or n)")),
                ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*(run |.*\(y\).*(allow|run \(once\)|→ run))"""))),
            ),
        ),
    ),
    ScreenRule(
        id = "stop_hint_working",
        state = ScreenState.Working,
        priority = 100,
        region = ScreenRegion.BottomNonEmpty(6),
        visibleWorking = true,
        gate = ScreenGate(contains = listOf("ctrl+c to stop")),
    ),
    ScreenRule(
        id = "background_task_status_working",
        state = ScreenState.Working,
        priority = 95,
        region = ScreenRegion.BottomNonEmpty(5),
        visibleWorking = true,
        gate = ScreenGate(lineRegex = listOf(Regex("""(?i)\b[1-9][0-9]*\s+background\s+tasks?\b"""))),
    ),
    ScreenRule(
        id = "spinner_working",
        state = ScreenState.Working,
        priority = 90,
        region = ScreenRegion.BottomNonEmpty(8),
        visibleWorking = true,
        gate = ScreenGate(
            lineRegex = listOf(Regex("""^\s*(⬡|⬢|[\u2800-\u28FF]+)\s+\p{L}+\w*ing\b""")),
        ),
    ),
    // Andy wrapper exit hint (not in Herdr Cursor manifest).
    ScreenRule(
        id = "andy_exit_hint_working",
        state = ScreenState.Working,
        priority = 85,
        region = ScreenRegion.BottomNonEmpty(6),
        visibleWorking = true,
        gate = ScreenGate(contains = listOf("press ctrl+c again to exit")),
    ),
    // No Cursor idle rules — Herdr uses known-agent idle fallback when no
    // working/blocked rule matches (avoids alt-screen chrome flip-flopping).
)

private val AntigravityScreenManifest: List<ScreenRule> = listOf(
    ScreenRule(
        id = "permission_prompt",
        state = ScreenState.Blocked,
        priority = 300,
        region = ScreenRegion.WholeRecent,
        visibleBlocker = true,
        gate = ScreenGate(
            contains = listOf("requesting permission for:"),
            any = listOf(
                ScreenGate(contains = listOf("do you want to proceed?")),
                ScreenGate(contains = listOf("tab amend", "edit command")),
            ),
        ),
    ),
    ScreenRule(
        id = "andy_generic_blocker",
        state = ScreenState.Blocked,
        priority = 280,
        region = ScreenRegion.WholeRecent,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(contains = listOf("allow this action")),
                ScreenGate(contains = listOf("do you want to proceed?")),
                ScreenGate(
                    contains = listOf("approve"),
                    any = listOf(
                        ScreenGate(contains = listOf("(y")),
                        ScreenGate(contains = listOf("yes")),
                    ),
                ),
            ),
        ),
    ),
    ScreenRule(
        id = "spinner_working",
        state = ScreenState.Working,
        priority = 100,
        region = ScreenRegion.WholeRecent,
        visibleWorking = true,
        gate = ScreenGate(
            lineRegex = listOf(Regex("""^\s*[\u2800-\u28FF]+\s+\p{L}+\w*ing\b""")),
        ),
    ),
    ScreenRule(
        id = "background_tasks_working",
        state = ScreenState.Working,
        priority = 90,
        region = ScreenRegion.BottomNonEmpty(5),
        visibleWorking = true,
        gate = ScreenGate(lineRegex = listOf(Regex("""(?i)·\s*[1-9][0-9]*\s+task"""))),
    ),
    ScreenRule(
        id = "andy_status_spinner_working",
        state = ScreenState.Working,
        priority = 80,
        region = ScreenRegion.BottomNonEmpty(4),
        visibleWorking = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(lineRegex = listOf(Regex("""✻\s+\w+ing\b"""))),
                ScreenGate(lineRegex = listOf(Regex("""(?i)^\s*(Thinking|Executing|Running|Analyzing)\b"""))),
            ),
        ),
    ),
    ScreenRule(
        id = "andy_prompt_idle",
        state = ScreenState.Idle,
        priority = 50,
        region = ScreenRegion.BottomNonEmpty(3),
        visibleIdle = true,
        gate = ScreenGate(
            any = listOf(
                ScreenGate(lineRegex = listOf(Regex(""">\s*$"""))),
                ScreenGate(lineRegex = listOf(Regex("""›\s*$"""))),
                ScreenGate(lineRegex = listOf(Regex("""❯\s*$"""))),
            ),
        ),
    ),
)

// endregion
