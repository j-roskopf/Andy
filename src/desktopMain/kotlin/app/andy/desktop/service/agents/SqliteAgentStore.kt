package app.andy.desktop.service.agents

import app.andy.store.openAndyAgentDatabase
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SQLDelight-backed persistence for [AgentStoreState].
 *
 * Task / workflow payloads are stored as JSON (same DTOs as the legacy TOML file).
 * On first open, imports `~/.andy/agents.toml` when present and the DB is empty.
 */
internal class SqliteAgentStore(
    private val dbFile: File = File(System.getProperty("user.home"), ".andy/agents.db"),
    private val tomlFile: File = File(System.getProperty("user.home"), ".andy/agents.toml"),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val db = openAndyAgentDatabase(dbFile)

    fun load(scrollbackFile: (String) -> File): AgentStoreState {
        maybeImportToml(scrollbackFile)
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

    fun save(state: AgentStoreState) {
        // Empty-store guard: never wipe a populated DB with an accidental empty save.
        val existingCount = db.agentStoreQueries.countTasks().executeAsOne()
        if (state.tasks.isEmpty() && existingCount > 0L) return

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

    private fun maybeImportToml(scrollbackFile: (String) -> File) {
        val count = db.agentStoreQueries.countTasks().executeAsOne()
        val imported = db.agentStoreQueries.getMeta(KEY_TOML_IMPORTED).executeAsOneOrNull() == "1"
        if (imported || count > 0L) return
        if (!tomlFile.isFile || tomlFile.length() == 0L) {
            db.agentStoreQueries.setMeta(KEY_TOML_IMPORTED, "1")
            return
        }
        val state = runCatching {
            net.peanuuutz.tomlkt.Toml { ignoreUnknownKeys = true }
                .decodeFromString(AgentsFileDto.serializer(), tomlFile.readText())
                .toModel(scrollbackFile)
        }.getOrNull() ?: return
        save(state)
        db.agentStoreQueries.setMeta(KEY_TOML_IMPORTED, "1")
        val migrated = File(tomlFile.absolutePath + ".migrated")
        runCatching { tomlFile.copyTo(migrated, overwrite = true) }
        runCatching { tomlFile.renameTo(migrated) }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_LAST_USED_AGENT = "last_used_agent"
        private const val KEY_MAX_CONCURRENT = "max_concurrent"
        private const val KEY_LEGACY_ARCHIVED = "legacy_transcript_chats_archived"
        private const val KEY_TOML_IMPORTED = "toml_imported"
    }
}
