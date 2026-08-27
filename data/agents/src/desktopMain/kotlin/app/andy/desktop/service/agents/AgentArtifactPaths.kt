package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.defaultLane
import java.io.File

/** Shared on-disk agent artifacts under `~/.andy/agents` (owned by `andyd`). */
fun defaultAndyAgentArtifactsDir(): File =
    File(System.getProperty("user.home"), ".andy/agents")

internal fun agentTranscriptFile(agentsDir: File, taskId: String): File =
    File(File(agentsDir, taskId), "transcript.jsonl")

internal fun agentScrollbackFile(agentsDir: File, taskId: String): File =
    File(File(agentsDir, taskId), "scrollback.ansi")

/**
 * Recover the transport lane after older daemons persisted tasks without `lane`,
 * or when the GUI attach bridge used a separate DB but shares artifact files with `andyd`.
 */
internal fun inferAgentLaneFromArtifacts(
    taskId: String,
    declaredLane: AgentLaneKind?,
    agent: AgentKind?,
    agentsDir: File = defaultAndyAgentArtifactsDir(),
): AgentLaneKind {
    val transcript = agentTranscriptFile(agentsDir, taskId)
    if (transcript.isFile && transcript.length() > 0L) return AgentLaneKind.Acp
    val scrollback = agentScrollbackFile(agentsDir, taskId)
    if (scrollback.isFile && scrollback.length() > 0L) return AgentLaneKind.Terminal
    return declaredLane ?: agent?.defaultLane() ?: AgentLaneKind.Terminal
}
