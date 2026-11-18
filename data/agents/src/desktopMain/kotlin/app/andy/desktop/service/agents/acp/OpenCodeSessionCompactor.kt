package app.andy.desktop.service.agents.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.DriverManager

/**
 * Compacts oversized completed `tool` parts in OpenCode's SQLite database (`opencode.db`).
 *
 * OpenCode's built-in `SessionCompaction.prune` only estimates tokens from `state.output`
 * (e.g. `"Image read successfully"` = 6 tokens) while ignoring base64 `state.attachments`,
 * and it never runs when a turn errors out or before 2 user turns complete. A single long
 * turn that reads dozens of screenshots can accumulate 30–50 MB of uncompacted base64 tool
 * attachments in `opencode.db`, causing every subsequent prompt or resume in that session
 * to fail with `Cannot connect to API: ECONNRESET`.
 *
 * Setting `state.time.compacted` on older completed tool parts uses OpenCode's native
 * compaction marker so `MessageV2.toModelMessages` strips old `state.attachments` and
 * replaces old tool outputs with `"[Old tool result content cleared]"`.
 */
internal object OpenCodeSessionCompactor {
    /** Trigger compaction once uncompacted completed tool parts in a session exceed 512 KB. */
    internal const val SESSION_COMPACT_TRIGGER_BYTES = 512 * 1024

    /** Keep up to 256 KB of the most recent non-attachment completed tool outputs uncompacted. */
    internal const val RECENT_TEXT_TOOL_BUDGET_BYTES = 256 * 1024

    /** Keep attachments on the newest tool message only when they total at most 512 KB. */
    internal const val MAX_UNCOMPACTED_ATTACHMENT_BYTES = 512 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private data class CandidateToolPart(
        val id: String,
        val messageId: String,
        val rawData: String,
        val root: JsonObject,
        val hasAttachments: Boolean,
    )

    fun compactOversizedToolPartsIfNeeded(
        sessionId: String?,
        env: Map<String, String> = emptyMap(),
        dbFile: File? = resolveDatabaseFile(env),
        nowMillis: Long = System.currentTimeMillis(),
        onDiagnostics: (String) -> Unit = {},
    ): Int {
        val targetSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        val file = dbFile?.takeIf { it.isFile } ?: return 0
        return runCatching {
            runCatching { Class.forName("org.sqlite.JDBC") }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA busy_timeout = 3000")
                }
                val candidates = mutableListOf<CandidateToolPart>()
                conn.prepareStatement(
                    "SELECT id, message_id, data FROM part WHERE session_id = ? ORDER BY time_created ASC, id ASC",
                ).use { ps ->
                    ps.setString(1, targetSessionId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val id = rs.getString(1) ?: continue
                            val messageId = rs.getString(2).orEmpty()
                            val rawData = rs.getString(3) ?: continue
                            val root = runCatching { json.parseToJsonElement(rawData).jsonObject }.getOrNull() ?: continue
                            if (root["type"]?.jsonPrimitive?.contentOrNull != "tool") continue
                            val state = root["state"] as? JsonObject ?: continue
                            if (state["status"]?.jsonPrimitive?.contentOrNull != "completed") continue
                            val time = state["time"] as? JsonObject
                            if (time?.get("compacted") != null) continue
                            val attachments = state["attachments"]?.let { runCatching { it.jsonArray }.getOrNull() }
                            candidates += CandidateToolPart(
                                id = id,
                                messageId = messageId,
                                rawData = rawData,
                                root = root,
                                hasAttachments = !attachments.isNullOrEmpty(),
                            )
                        }
                    }
                }

                val totalBytes = candidates.sumOf { it.rawData.length }
                if (totalBytes <= SESSION_COMPACT_TRIGGER_BYTES) return@use 0

                val newestMessageId = candidates.lastOrNull()?.messageId
                val newestMessageAttachmentBytes = candidates
                    .filter { it.messageId == newestMessageId && it.hasAttachments }
                    .sumOf { it.rawData.length }

                val keepIds = linkedSetOf<String>()
                var keptTextBytes = 0
                for (candidate in candidates.asReversed()) {
                    if (candidate.hasAttachments) {
                        if (
                            candidate.messageId == newestMessageId &&
                            newestMessageAttachmentBytes <= MAX_UNCOMPACTED_ATTACHMENT_BYTES
                        ) {
                            keepIds += candidate.id
                        }
                    } else if (keptTextBytes + candidate.rawData.length <= RECENT_TEXT_TOOL_BUDGET_BYTES) {
                        keepIds += candidate.id
                        keptTextBytes += candidate.rawData.length
                    }
                }

                val toCompact = candidates.filterNot { it.id in keepIds }
                if (toCompact.isEmpty()) return@use 0

                val autoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        "UPDATE part SET data = ?, time_updated = ? WHERE id = ?",
                    ).use { update ->
                        for (candidate in toCompact) {
                            val compactedJson = markToolPartCompacted(candidate.root, nowMillis).toString()
                            update.setString(1, compactedJson)
                            update.setLong(2, nowMillis)
                            update.setString(3, candidate.id)
                            update.addBatch()
                        }
                        update.executeBatch()
                    }
                    conn.commit()
                } catch (error: Throwable) {
                    runCatching { conn.rollback() }
                    throw error
                } finally {
                    conn.autoCommit = autoCommit
                }

                logCompaction(toCompact, targetSessionId, totalBytes, onDiagnostics)
                toCompact.size
            }
        }.onFailure { error ->
            onDiagnostics("OpenCode session compaction skipped: ${error.message}\n")
        }.getOrDefault(0)
    }

    private fun logCompaction(
        compacted: List<CandidateToolPart>,
        sessionId: String,
        totalBytes: Int,
        onDiagnostics: (String) -> Unit,
    ) {
        val attachmentParts = compacted.count { it.hasAttachments }
        onDiagnostics(
            "Compacted ${compacted.size} OpenCode tool parts ($attachmentParts with attachments, " +
                "${totalBytes / 1024} KB uncompacted) in session $sessionId\n",
        )
    }

    internal fun markToolPartCompacted(root: JsonObject, compactedAtMillis: Long): JsonObject {
        val state = root["state"]?.jsonObject ?: return root
        val existingTime = state["time"] as? JsonObject
        val updatedTime = buildJsonObject {
            existingTime?.forEach { (k, v) -> put(k, v) }
            put("compacted", JsonPrimitive(compactedAtMillis))
        }
        val updatedState = buildJsonObject {
            state.forEach { (k, v) ->
                if (k == "time") put("time", updatedTime) else put(k, v)
            }
            if ("time" !in state) put("time", updatedTime)
        }
        return buildJsonObject {
            root.forEach { (k, v) ->
                if (k == "state") put("state", updatedState) else put(k, v)
            }
        }
    }

    internal fun resolveDatabaseFile(env: Map<String, String> = emptyMap()): File? {
        val xdgDataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
            ?: System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val home = env["HOME"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (xdgDataHome != null) {
                add(File(xdgDataHome, "opencode/opencode.db"))
            }
            if (home != null) {
                add(File(home, ".local/share/opencode/opencode.db"))
                add(File(home, ".opencode/opencode.db"))
            }
        }
        return candidates.firstOrNull { it.isFile }
    }
}
