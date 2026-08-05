package app.andy.desktop.service.agents

import app.andy.model.KanbanBoard
import app.andy.store.openAndyAgentDatabase
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SQLDelight-backed persistence for [AgentStoreState].
 *
 * Task / workflow payloads are stored as JSON (same DTOs used for encode/decode).
 */
internal class SqliteAgentStore(
    private val dbFile: File = File(System.getProperty("user.home"), ".andy/agents.db"),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val db = openAndyAgentDatabase(dbFile)

    fun load(scrollbackFile: (String) -> File): AgentStoreState {
        val tasks = db.agentStoreQueries.selectAllTasks().executeAsList().mapNotNull { row ->
            runCatching {
                json.decodeFromString(AgentTaskDto.serializer(), row.payload)
                    .toModel(scrollbackFile)
            }.getOrNull()
        }
        val workflows = db.agentStoreQueries.selectAllWorkflows().executeAsList().mapNotNull { row ->
            runCatching {
                json.decodeFromString(ProjectWorkflowDto.serializer(), row.payload).toModel()
            }.getOrNull()
        }.associateBy { it.projectId }
        val binaries = db.agentStoreQueries.selectBinaryOverrides().executeAsList()
            .associate { it.name to it.path }
        val providerDefaults = db.agentStoreQueries.selectProviderDefaults().executeAsList().mapNotNull { row ->
            runCatching {
                json.decodeFromString(AgentProviderDefaultsDto.serializer(), row.payload).toModel()
            }.getOrNull()
        }.toMap()
        val quota = db.agentStoreQueries.selectQuotaAccess().executeAsList().firstOrNull()
        val lastUsed = db.agentStoreQueries.getMeta(KEY_LAST_USED_AGENT).executeAsOneOrNull()
        val maxConcurrent = db.agentStoreQueries.getMeta(KEY_MAX_CONCURRENT).executeAsOneOrNull()
            ?.toIntOrNull() ?: 8
        val legacyArchived = db.agentStoreQueries.getMeta(KEY_LEGACY_ARCHIVED).executeAsOneOrNull() == "1"
        return AgentStoreState(
            tasks = tasks,
            binaryOverrides = binaries,
            providerDefaults = providerDefaults,
            quotaAccess = app.andy.model.AgentQuotaAccess(
                claudeAccountAccess = (quota?.claude_account_access ?: 0L) != 0L,
                cursorAccountAccess = (quota?.cursor_account_access ?: 0L) != 0L,
                antigravityAccountAccess = (quota?.antigravity_account_access ?: 0L) != 0L,
            ),
            lastUsedAgent = app.andy.model.AgentKind.entries.firstOrNull { it.name == lastUsed },
            maxConcurrent = maxConcurrent.coerceIn(1, 64),
            projectWorkflows = workflows,
            legacyTranscriptChatsArchived = legacyArchived,
        )
    }

    fun loadKanbanBoard(): KanbanBoard? =
        db.agentStoreQueries.selectKanbanBoard().executeAsOneOrNull()?.let { row ->
            runCatching { json.decodeFromString(KanbanBoard.serializer(), row) }.getOrNull()
        }

    fun saveKanbanBoard(board: KanbanBoard) {
        db.agentStoreQueries.upsertKanbanBoard(
            updated_at_millis = System.currentTimeMillis(),
            payload = json.encodeToString(KanbanBoard.serializer(), board),
        )
    }

    fun save(state: AgentStoreState, allowEmptyTaskList: Boolean = false) {
        // Guard against failed-load recovery wiping a populated DB; intentional deletes opt in.
        val existingCount = db.agentStoreQueries.countTasks().executeAsOne()
        if (!allowEmptyTaskList && state.tasks.isEmpty() && existingCount > 0L) return

        val now = System.currentTimeMillis()
        val dto = state.toFileDto()
        db.agentStoreQueries.transaction {
            db.agentStoreQueries.deleteAllTasks()
            dto.tasks.forEach { task ->
                db.agentStoreQueries.upsertTask(
                    id = task.id,
                    status = task.status,
                    agent = task.agent,
                    created_at_millis = task.createdAtMillis,
                    updated_at_millis = now,
                    payload = json.encodeToString(AgentTaskDto.serializer(), task),
                )
            }
            db.agentStoreQueries.deleteAllWorkflows()
            dto.projectWorkflows.forEach { workflow ->
                db.agentStoreQueries.upsertWorkflow(
                    project_id = workflow.projectId,
                    updated_at_millis = now,
                    payload = json.encodeToString(ProjectWorkflowDto.serializer(), workflow),
                )
            }
            db.agentStoreQueries.deleteAllBinaryOverrides()
            dto.binaries.forEach { (name, path) ->
                db.agentStoreQueries.upsertBinaryOverride(name, path)
            }
            db.agentStoreQueries.deleteAllProviderDefaults()
            dto.providerDefaults.forEach { defaults ->
                db.agentStoreQueries.upsertProviderDefaults(
                    agent = defaults.agent,
                    payload = json.encodeToString(AgentProviderDefaultsDto.serializer(), defaults),
                )
            }
            db.agentStoreQueries.upsertQuotaAccess(
                claude_account_access = if (dto.quotaAccess.claudeAccountAccess) 1L else 0L,
                cursor_account_access = if (dto.quotaAccess.cursorAccountAccess) 1L else 0L,
                antigravity_account_access = if (dto.quotaAccess.antigravityAccountAccess) 1L else 0L,
            )
            db.agentStoreQueries.setMeta(KEY_LAST_USED_AGENT, dto.lastUsedAgent)
            db.agentStoreQueries.setMeta(KEY_MAX_CONCURRENT, dto.maxConcurrent.toString())
            db.agentStoreQueries.setMeta(
                KEY_LEGACY_ARCHIVED,
                if (dto.legacyTranscriptChatsArchived) "1" else "0",
            )
            db.agentStoreQueries.setMeta(KEY_SCHEMA_VERSION, SCHEMA_VERSION.toString())
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_LAST_USED_AGENT = "last_used_agent"
        private const val KEY_MAX_CONCURRENT = "max_concurrent"
        private const val KEY_LEGACY_ARCHIVED = "legacy_transcript_chats_archived"
    }
}
