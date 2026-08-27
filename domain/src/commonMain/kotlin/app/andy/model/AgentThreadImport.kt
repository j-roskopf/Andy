package app.andy.model

/**
 * Providers that can reopen a vendor-owned conversation from a pasted thread/session id.
 *
 * Local model backends are omitted: their session lives on OpenCode/Pi/Goose, not on
 * Ollama/LM Studio. Antigravity is omitted because resume only trusts an id Andy can
 * prove belongs to an existing Andy prompt.
 */
val ImportableAgentKinds: List<AgentKind> = listOf(
    AgentKind.Codex,
    AgentKind.ClaudeCode,
    AgentKind.Cursor,
    AgentKind.OpenCode,
    AgentKind.Pi,
    AgentKind.Hermes,
    AgentKind.OpenClaw,
    AgentKind.Goose,
)

val AgentKind.canImportVendorThread: Boolean
    get() = this in ImportableAgentKinds

/** Short tile label so Claude Code fits the mock's "Claude" chip. */
val AgentKind.importTileLabel: String
    get() = when (this) {
        AgentKind.ClaudeCode -> "Claude"
        else -> label
    }

val AgentKind.importIdNoun: String
    get() = when (this) {
        AgentKind.Codex -> "thread"
        AgentKind.Cursor -> "chat"
        else -> "session"
    }

fun AgentKind.importIdPlaceholder(): String =
    "Paste a $importTileLabel $importIdNoun id."

fun AgentKind.importIdHelper(): String = when (this) {
    AgentKind.Codex -> "Codex resumes a persisted thread by thread id."
    AgentKind.ClaudeCode -> "Claude resumes a persisted session by session id."
    AgentKind.Cursor ->
        "Cursor stores chats per workspace folder. Andy looks up this chat id and resumes in that folder so history loads."
    AgentKind.OpenCode -> "OpenCode resumes a persisted session by session id."
    AgentKind.Pi -> "Pi resumes a persisted session by session id."
    AgentKind.Hermes -> "Hermes resumes a checkpoint by session id."
    AgentKind.OpenClaw -> "OpenClaw resumes a persisted session by session id."
    AgentKind.Goose -> "Goose resumes a persisted session by session id (YYYYMMDD_N)."
    else -> "This provider resumes by session id."
}

fun AgentKind.importedThreadTitle(): String = "Imported $importTileLabel thread"

fun AgentTaskDraft.withImportedVendorSession(sessionId: String): AgentTaskDraft {
    val id = sessionId.trim()
    return copy(
        title = title.ifBlank { agent.importedThreadTitle() },
        vendorSessionId = id,
        lane = AgentLaneKind.Terminal,
        useWorktree = false,
        openClawNewSession = false,
        localRuntime = null,
    )
}
