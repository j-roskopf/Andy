package app.andy.desktop.service.agents

import app.andy.model.AgentFileDiff
import app.andy.model.KanbanBoard
import app.andy.store.AgentJsonSql
import app.andy.store.openAndyAgentDatabase
import app.cash.sqldelight.db.QueryResult
import kotlinx.serialization.builtins.ListSerializer
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

    private val handle = openAndyAgentDatabase(dbFile)
    private val db = handle.database
    private val driver = handle.driver

    /** Shared FTS driver for Cmd+K transcript search; caller supplies transcript file resolution. */
    fun transcriptSearchIndex(transcriptFileFor: (String) -> File): DesktopTranscriptSearchIndex =
        DesktopTranscriptSearchIndex(driver, transcriptFileFor)

    fun load(scrollbackFile: (String) -> File): AgentStoreState {
        // The lite projection strips completedChanges.diffs in SQL, so decoded snapshots are
        // summary-only and must be marked unhydrated for the save path to protect them.
        val tasks = selectAllTaskPayloadsLite().mapNotNull { payload ->
            runCatching {
                json.decodeFromString(AgentTaskDto.serializer(), payload)
                    .toModel(scrollbackFile)
                    ?.withUnhydratedDiffs()
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

    /** Task payloads with `completedChanges.diffs` stripped by SQLite; see [AgentJsonSql]. */
    private fun selectAllTaskPayloadsLite(): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = AgentJsonSql.SELECT_ALL_TASKS_LITE,
            parameters = 0,
            mapper = { cursor ->
                val payloads = mutableListOf<String>()
                while (cursor.next().value) {
                    cursor.getString(1)?.let(payloads::add)
                }
                QueryResult.Value(payloads.toList())
            },
        ).value

    /**
     * Reads one task's stored diffs, which [load] deliberately skipped. Returns an empty map when
     * the task has no stored diffs; callers cannot distinguish that from "never captured", which
     * matches the old in-memory behaviour for a chat that changed nothing.
     */
    fun loadTaskDiffs(taskId: String): Map<String, AgentFileDiff> {
        val raw = driver.executeQuery(
            identifier = null,
            sql = AgentJsonSql.SELECT_TASK_DIFFS,
            parameters = 1,
            binders = { bindString(0, taskId) },
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
        ).value?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            json.decodeFromString(ListSerializer(AgentFileDiffDto.serializer()), raw)
                .associate { it.path to it.toModel() }
        }.getOrElse { emptyMap() }
    }

    /** Writes an unhydrated task, splicing its stored diffs back; see [AgentJsonSql]. */
    private fun writeTaskPreservingDiffs(task: AgentTaskDto, payload: String, now: Long) {
        db.agentStoreQueries.insertTaskIfAbsent(
            id = task.id,
            status = task.status,
            agent = task.agent,
            created_at_millis = task.createdAtMillis,
            updated_at_millis = now,
            payload = payload,
        )
        driver.execute(
            identifier = null,
            sql = AgentJsonSql.UPDATE_TASK_PRESERVING_DIFFS,
            parameters = 8,
            binders = {
                bindString(0, task.status)
                bindString(1, task.agent)
                bindLong(2, task.createdAtMillis)
                bindLong(3, now)
                bindString(4, payload)
                bindString(5, payload)
                bindString(6, payload)
                bindString(7, task.id)
            },
        )
    }

    fun loadAllKanbanBoards(): Map<String, KanbanBoard> =
        db.agentStoreQueries.selectAllKanbanBoards().executeAsList().mapNotNull { row ->
            runCatching { json.decodeFromString(KanbanBoard.serializer(), row.payload) }
                .getOrNull()
                ?.let { row.project_id to it }
        }.toMap()

    fun saveKanbanBoard(projectId: String, board: KanbanBoard) {
        db.agentStoreQueries.upsertKanbanBoard(
            project_id = projectId,
            updated_at_millis = System.currentTimeMillis(),
            payload = json.encodeToString(KanbanBoard.serializer(), board),
        )
    }

    fun deleteKanbanBoard(projectId: String) {
        db.agentStoreQueries.deleteKanbanBoard(project_id = projectId)
    }

    fun loadAllAutomations(): List<app.andy.model.Automation> =
        db.agentStoreQueries.selectAllAutomations().executeAsList().mapNotNull { row ->
            runCatching { json.decodeFromString(app.andy.model.Automation.serializer(), row.payload) }
                .getOrNull()
        }

    fun saveAutomation(automation: app.andy.model.Automation) {
        db.agentStoreQueries.upsertAutomation(
            id = automation.id,
            project_id = automation.projectId,
            updated_at_millis = automation.updatedAtMillis,
            payload = json.encodeToString(app.andy.model.Automation.serializer(), automation),
        )
    }

    fun deleteAutomation(id: String) {
        db.agentStoreQueries.deleteAutomation(id = id)
    }

    fun save(state: AgentStoreState, allowEmptyTaskList: Boolean = false) {
        // Guard against failed-load recovery wiping a populated DB; intentional deletes opt in.
        val existingCount = db.agentStoreQueries.countTasks().executeAsOne()
        if (!allowEmptyTaskList && state.tasks.isEmpty() && existingCount > 0L) return

        val now = System.currentTimeMillis()
        val dto = state.toFileDto()
        // Tasks whose diffs were never read off disk must go through the preserving statement, so
        // skip the old delete-everything-then-reinsert: it would drop the diffs we mean to keep.
        val unhydrated = state.tasks
            .filter { it.completedChanges?.diffsHydrated == false }
            .mapTo(mutableSetOf()) { it.id }
        db.agentStoreQueries.transaction {
            db.agentStoreQueries.deleteTasksNotIn(dto.tasks.map { it.id })
            dto.tasks.forEach { task ->
                val payload = json.encodeToString(AgentTaskDto.serializer(), task)
                if (task.id in unhydrated) {
                    writeTaskPreservingDiffs(task, payload, now)
                } else {
                    db.agentStoreQueries.upsertTask(
                        id = task.id,
                        status = task.status,
                        agent = task.agent,
                        created_at_millis = task.createdAtMillis,
                        updated_at_millis = now,
                        payload = payload,
                    )
                }
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
