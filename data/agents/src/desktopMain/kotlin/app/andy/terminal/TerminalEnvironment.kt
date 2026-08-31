package app.andy.terminal

import app.andy.desktop.service.LoginShellEnvironment

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
    // Cursor/VS Code sandbox XDG so cursor-agent finds ~/.local/state/cursor auth, not an empty
    // IDE store. Restored from the user's login shell / launch overrides after scrub.
    "XDG_STATE_HOME",
    "XDG_CONFIG_HOME",
    "XDG_CACHE_HOME",
    "XDG_DATA_HOME",
)

private val DROP_ENV_PREFIXES = listOf(
    "CURSOR_",
    "VSCODE_",
    "__CURSOR_",
)

/** Login credentials that the `CURSOR_` prefix would otherwise strip. */
private val KEEP_ENV_KEYS = setOf(
    "CURSOR_API_KEY",
    "CURSOR_AUTH_TOKEN",
)

internal val IDE_XDG_KEYS = setOf(
    "XDG_STATE_HOME",
    "XDG_CONFIG_HOME",
    "XDG_CACHE_HOME",
    "XDG_DATA_HOME",
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
        .filter { key ->
            key !in KEEP_ENV_KEYS &&
                DROP_ENV_PREFIXES.any { prefix -> key.startsWith(prefix) }
        }
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

/**
 * Full launch environment: process env, overridden by the user's real login-shell
 * environment (PATH/JAVA_HOME/etc. that a GUI-launched JVM never inherits), overridden by
 * per-launch [overrides], with IDE/proxy vars stripped.
 */
fun buildTerminalLaunchEnvironment(
    overrides: Map<String, String> = emptyMap(),
    loginShellEnv: Map<String, String> = LoginShellEnvironment.current(),
): Map<String, String> {
    val env = HashMap(System.getenv())
    env.putAll(loginShellEnv)
    env.putAll(overrides)
    val xdgFromShell = loginShellEnv.filterKeys { it in IDE_XDG_KEYS }
    val xdgFromOverrides = overrides.filterKeys { it in IDE_XDG_KEYS }
    scrubInheritedTerminalEnvironment(env)
    // Scrub drops IDE-sandbox XDG_*; put back only what the user's shell or caller set.
    env.putAll(xdgFromShell)
    env.putAll(xdgFromOverrides)
    return env
}

/**
 * [ProcessBuilder.environment] starts as a copy of the JVM env. [MutableMap.putAll]
 * cannot drop keys the caller already scrubbed (`NODE_OPTIONS`, `CURSOR_AGENT`, …),
 * so ACP/CLI children would still see Cursor's agent-host markers and report
 * "not logged in". Replace in place so the child sees exactly [desired].
 */
internal fun replaceProcessEnvironment(
    current: MutableMap<String, String>,
    desired: Map<String, String>,
) {
    current.keys
        .filter { key -> desired.keys.none { it.equals(key, ignoreCase = true) } }
        .toList()
        .forEach { current.remove(it) }
    current.putAll(desired)
}
