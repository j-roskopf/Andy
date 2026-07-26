package app.andy.terminal

private val DROP_ENV_KEYS = setOf(
    "ANTHROPIC_BASE_URL",
    "NODE_OPTIONS",
    "VSCODE_INSPECTOR_OPTIONS",
    "ELECTRON_RUN_AS_NODE",
    // Cursor/VS Code agent-host markers — interactive CLIs exit or misbehave under these.
    "CURSOR_AGENT",
    "CURSOR_AGENT_STORE_FILES_DIR",
    "CURSOR_AGENT_STORE_SHARED_PATHS",
    "CURSOR_CONVERSATION_ID",
    "CURSOR_EXTENSION_HOST_ROLE",
    "CURSOR_LAYOUT",
    "CURSOR_RIPGREP_PATH",
    "CURSOR_WORKSPACE_LABEL",
    "AGENT_TRANSCRIPTS",
    "FORCE_COLOR",
    "NO_COLOR",
    "CI",
)

private val DROP_ENV_PREFIXES = listOf(
    "CURSOR_",
    "VSCODE_",
    "__CURSOR_",
)

/**
 * IDE/proxy env that breaks vendor CLIs (especially Node-based ones like Claude Code)
 * when Andy is launched from Cursor/VS Code.
 *
 * Also forces a real [TERM] — Cursor agent hosts often set `TERM=dumb`, which makes
 * interactive TUIs (agy/claude/codex) exit immediately inside `tmux -L andy`.
 */
fun scrubInheritedTerminalEnvironment(env: MutableMap<String, String>) {
    DROP_ENV_KEYS.forEach { env.remove(it) }
    env.keys
        .filter { key -> DROP_ENV_PREFIXES.any { prefix -> key.startsWith(prefix) } }
        .toList()
        .forEach { env.remove(it) }

    val term = env["TERM"]
    if (term.isNullOrBlank() || term.equals("dumb", ignoreCase = true) ||
        term.equals("unknown", ignoreCase = true)
    ) {
        env["TERM"] = "xterm-256color"
    }
}

/** Directory for a new PTY/tmux client — never inherit a deleted JVM cwd. */
fun resolveTerminalWorkingDirectory(cwd: String?): String {
    val trimmed = cwd?.takeIf { it.isNotBlank() }
    if (trimmed != null) {
        val dir = java.io.File(trimmed).absoluteFile.normalize()
        if (dir.isDirectory) return dir.absolutePath
    }
    return java.io.File(System.getProperty("user.home")).absolutePath
}

/** Full launch environment: process env + overrides, with IDE/proxy vars stripped. */
fun buildTerminalLaunchEnvironment(overrides: Map<String, String> = emptyMap()): Map<String, String> {
    val env = HashMap(System.getenv())
    env.putAll(overrides)
    scrubInheritedTerminalEnvironment(env)
    return env
}
