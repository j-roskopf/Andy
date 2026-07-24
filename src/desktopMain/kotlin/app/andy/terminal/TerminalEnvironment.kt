package app.andy.terminal

/**
 * IDE/proxy env that breaks vendor CLIs (especially Node-based ones like Claude Code)
 * when Andy is launched from Cursor/VS Code.
 */
fun scrubInheritedTerminalEnvironment(env: MutableMap<String, String>) {
    env.remove("ANTHROPIC_BASE_URL")
    env.remove("NODE_OPTIONS")
    env.remove("VSCODE_INSPECTOR_OPTIONS")
    env.remove("ELECTRON_RUN_AS_NODE")
}

/** Full launch environment: process env + overrides, with IDE/proxy vars stripped. */
fun buildTerminalLaunchEnvironment(overrides: Map<String, String> = emptyMap()): Map<String, String> {
    val env = HashMap(System.getenv())
    env.putAll(overrides)
    scrubInheritedTerminalEnvironment(env)
    return env
}
