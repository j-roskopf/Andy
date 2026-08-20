package app.andy.desktop.service

import app.andy.desktop.service.automations.nextScheduleOccurrence
import app.andy.model.AgentKind
import app.andy.model.Automation
import app.andy.model.AutomationDraft
import app.andy.model.AutomationFailurePolicy
import app.andy.model.AutomationIntervalUnit
import app.andy.model.AutomationLaunchSnapshot
import app.andy.model.AutomationMaxIterations
import app.andy.model.AutomationMode
import app.andy.model.AutomationNotify
import app.andy.model.AutomationSchedule
import app.andy.model.resolveAutomationTimeZoneId
import app.andy.service.AgentRunService
import app.andy.service.AutomationService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId

private val AutomationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun Server.registerAutomationTools(
    automations: AutomationService,
    agentRuns: AgentRunService,
    callerTaskId: String?,
) {
    fun register(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        handler: suspend (Map<String, JsonElement>) -> CallToolResult,
    ) {
        addTool(
            name,
            description,
            ToolSchema(
                properties = buildJsonObject { properties.forEach { (k, v) -> put(k, v) } },
                required = required.takeIf { it.isNotEmpty() },
            ),
        ) { request ->
            try {
                handler(request.arguments ?: emptyMap())
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message ?: e.toString()}")),
                    isError = true,
                )
            }
        }
    }

    fun str(args: Map<String, JsonElement>, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    fun textResult(value: String) =
        CallToolResult(content = listOf(TextContent(text = value)))

    fun inheritedProjectId(args: Map<String, JsonElement>): String? {
        str(args, "projectId")?.takeIf { it.isNotBlank() }?.let { return it }
        return inheritedParentTask(agentRuns, str(args, "callerTaskId"), callerTaskId)?.projectId
    }

    register(
        name = "automation.list",
        description = "List Andy automations for a project",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
        ),
    ) { args ->
        val projectId = inheritedProjectId(args)
        val items = automations.automations.value.filter { projectId == null || it.projectId == projectId }
        textResult(AutomationJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Automation.serializer()), items))
    }

    register(
        name = "automation.get",
        description = "Get one automation by id",
        properties = mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id"),
    ) { args ->
        val id = str(args, "id") ?: error("id required")
        val item = automations.automations.value.firstOrNull { it.id == id } ?: error("Automation $id not found")
        textResult(AutomationJson.encodeToString(Automation.serializer(), item))
    }

    val draftProperties = mapOf(
        "title" to buildJsonObject { put("type", "string") },
        "prompt" to buildJsonObject { put("type", "string") },
        "projectId" to buildJsonObject { put("type", "string") },
        "mode" to buildJsonObject {
            put("type", "string")
            put("description", "Standalone | Dedicated | Heartbeat")
        },
        "schedule" to buildJsonObject { put("type", "string") },
        "cron" to buildJsonObject { put("type", "string") },
        "intervalEvery" to buildJsonObject { put("type", "integer") },
        "intervalUnit" to buildJsonObject { put("type", "string") },
        "onceAtMillis" to buildJsonObject {
            put("type", "integer")
            put("description", "Optional epoch millis for Once. Otherwise Once uses runHour/runMinute in timeZone.")
        },
        "weeklyDayOfWeek" to buildJsonObject { put("type", "integer") },
        "timeZone" to buildJsonObject {
            put("type", "string")
            put("description", "IANA timezone id such as America/Chicago. Short names like Central are accepted.")
        },
        "runHour" to buildJsonObject { put("type", "integer") },
        "runMinute" to buildJsonObject { put("type", "integer") },
        "stopWhen" to buildJsonObject { put("type", "string") },
        "failurePolicy" to buildJsonObject { put("type", "string") },
        "maxIterations" to buildJsonObject { put("type", "string") },
        "notify" to buildJsonObject { put("type", "string") },
        "useWorktree" to buildJsonObject { put("type", "boolean") },
        "cleanupWorktree" to buildJsonObject { put("type", "boolean") },
        "heartbeatTaskId" to buildJsonObject { put("type", "string") },
        "agent" to buildJsonObject { put("type", "string") },
        "model" to buildJsonObject { put("type", "string") },
        "reasoningEffort" to buildJsonObject { put("type", "string") },
        "autonomy" to buildJsonObject { put("type", "string") },
        "directory" to buildJsonObject { put("type", "string") },
    )

    register(
        name = "automation.create",
        description = "Create an automation. Starts paused; call automation.resume to arm it.",
        properties = draftProperties,
        required = listOf("prompt"),
    ) { args ->
        val draft = parseAutomationDraft(args, inheritedProjectId(args), agentRuns, callerTaskId)
        val created = automations.create(draft, arm = false)
        textResult(AutomationJson.encodeToString(Automation.serializer(), created))
    }

    register(
        name = "automation.update",
        description = "Update an automation's prompt, schedule, or policy. Does not auto-arm.",
        properties = draftProperties + mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id", "prompt"),
    ) { args ->
        val id = str(args, "id") ?: error("id required")
        val existing = automations.automations.value.firstOrNull { it.id == id } ?: error("Automation $id not found")
        val draft = parseAutomationDraft(args, existing.projectId, agentRuns, callerTaskId, existing)
        val updated = automations.update(id, draft)
        textResult(AutomationJson.encodeToString(Automation.serializer(), updated))
    }

    register(
        name = "automation.pause",
        description = "Pause an automation so it no longer fires",
        properties = mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id"),
    ) { args ->
        automations.pause(str(args, "id") ?: error("id required"), "Paused")
        textResult("""{"ok":true}""")
    }

    register(
        name = "automation.resume",
        description = "Arm a paused automation so it can fire",
        properties = mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id"),
    ) { args ->
        automations.resume(str(args, "id") ?: error("id required"))
        textResult("""{"ok":true}""")
    }

    register(
        name = "automation.delete",
        description = "Delete an automation",
        properties = mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id"),
    ) { args ->
        automations.delete(str(args, "id") ?: error("id required"))
        textResult("""{"ok":true}""")
    }

    register(
        name = "automation.run",
        description = "Run an automation immediately (Manual or run-now). Skips if the target chat is still Working.",
        properties = mapOf("id" to buildJsonObject { put("type", "string") }),
        required = listOf("id"),
    ) { args ->
        val ran = automations.runNow(str(args, "id") ?: error("id required"))
        textResult(AutomationJson.encodeToString(Automation.serializer(), ran))
    }
}

private fun parseAutomationDraft(
    args: Map<String, JsonElement>,
    projectId: String?,
    agentRuns: AgentRunService,
    callerTaskId: String?,
    existing: Automation? = null,
): AutomationDraft {
    fun str(key: String): String? = args[key]?.jsonPrimitive?.contentOrNull
    fun bool(key: String, default: Boolean): Boolean =
        args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: args[key]?.jsonPrimitive?.let { runCatching { it.content.toBoolean() }.getOrNull() }
            ?: default.let { existingDefault ->
                when (key) {
                    "useWorktree" -> existing?.useWorktree ?: existingDefault
                    "cleanupWorktree" -> existing?.cleanupWorktree ?: existingDefault
                    else -> existingDefault
                }
            }
    val parent = inheritedParentTask(agentRuns, str("callerTaskId"), callerTaskId)
    val resolvedProject = projectId ?: existing?.projectId ?: parent?.projectId ?: error("projectId required")
    val mode = AutomationMode.entries.firstOrNull {
        it.name.equals(str("mode"), ignoreCase = true) || it.label.equals(str("mode"), ignoreCase = true)
    } ?: existing?.mode ?: AutomationMode.Standalone
    val timeZone = resolveAutomationTimeZoneId(
        str("timeZone") ?: existing?.timeZone.orEmpty(),
        fallback = ZoneId.systemDefault().id,
    )
    var runHour = args["runHour"]?.jsonPrimitive?.intOrNull ?: existing?.runHour ?: 9
    var runMinute = args["runMinute"]?.jsonPrimitive?.intOrNull ?: existing?.runMinute ?: 0
    val onceAtMillis = args["onceAtMillis"]?.jsonPrimitive?.longOrNull
    if (onceAtMillis != null) {
        val zoned = java.time.Instant.ofEpochMilli(onceAtMillis).atZone(
            runCatching { ZoneId.of(timeZone) }.getOrElse { ZoneId.systemDefault() },
        )
        if (args["runHour"] == null) runHour = zoned.hour
        if (args["runMinute"] == null) runMinute = zoned.minute
    }
        val schedule = automationScheduleFromToolArgs(args, existing, timeZone, runHour, runMinute)
    val agent = str("agent") ?: existing?.launch?.agent ?: parent?.agent?.name ?: AgentKind.Codex.name
    return AutomationDraft(
        id = existing?.id,
        projectId = resolvedProject,
        title = str("title") ?: existing?.title.orEmpty(),
        prompt = str("prompt") ?: existing?.prompt ?: error("prompt required"),
        mode = mode,
        schedule = schedule,
        timeZone = timeZone,
        runHour = runHour,
        runMinute = runMinute,
        stopWhen = str("stopWhen") ?: existing?.stopWhen.orEmpty(),
        failurePolicy = AutomationFailurePolicy.entries.firstOrNull {
            it.name.equals(str("failurePolicy"), ignoreCase = true)
        } ?: existing?.failurePolicy ?: AutomationFailurePolicy.StopAfter3,
        maxIterations = AutomationMaxIterations.entries.firstOrNull {
            it.name.equals(str("maxIterations"), ignoreCase = true)
        } ?: existing?.maxIterations ?: AutomationMaxIterations.Unlimited,
        notify = AutomationNotify.entries.firstOrNull {
            it.name.equals(str("notify"), ignoreCase = true)
        } ?: existing?.notify ?: AutomationNotify.AllRuns,
        useWorktree = args["useWorktree"]?.jsonPrimitive?.let { it.content.toBooleanStrictOrNull() ?: it.content.toBoolean() }
            ?: existing?.useWorktree ?: false,
        cleanupWorktree = args["cleanupWorktree"]?.jsonPrimitive?.let { it.content.toBooleanStrictOrNull() ?: it.content.toBoolean() }
            ?: existing?.cleanupWorktree ?: false,
        heartbeatTaskId = str("heartbeatTaskId") ?: existing?.heartbeatTaskId,
        launch = AutomationLaunchSnapshot(
            agent = agent,
            model = str("model") ?: existing?.launch?.model,
            reasoningEffort = str("reasoningEffort") ?: existing?.launch?.reasoningEffort,
            autonomy = str("autonomy") ?: existing?.launch?.autonomy ?: parent?.autonomy?.name ?: "Standard",
            directory = str("directory") ?: existing?.launch?.directory ?: parent?.originDir ?: parent?.cwd,
        ),
    )
}

internal fun automationScheduleFromToolArgs(
    args: Map<String, JsonElement>,
    existing: Automation? = null,
    timeZone: String = "UTC",
    runHour: Int = 9,
    runMinute: Int = 0,
    nowMillis: Long = System.currentTimeMillis(),
): AutomationSchedule {
    fun str(key: String) = args[key]?.jsonPrimitive?.contentOrNull
    val named = str("schedule")?.lowercase()
    return when {
        named == "manual" -> AutomationSchedule.Manual
        named == "once" || args["onceAtMillis"] != null -> {
            val explicit = args["onceAtMillis"]?.jsonPrimitive?.longOrNull
            val at = explicit ?: nextScheduleOccurrence(
                schedule = AutomationSchedule.Once(0L),
                timeZone = timeZone,
                runHour = runHour,
                runMinute = runMinute,
                fromExclusiveMillis = nowMillis,
                lastFiredAtMillis = null,
            ) ?: nowMillis
            AutomationSchedule.Once(at)
        }
        named == "hourly" -> AutomationSchedule.Hourly
        named == "daily" -> AutomationSchedule.Daily
        named == "weekdays" -> AutomationSchedule.Weekdays
        named == "weekly" -> AutomationSchedule.Weekly(
            args["weeklyDayOfWeek"]?.jsonPrimitive?.intOrNull
                ?: (existing?.schedule as? AutomationSchedule.Weekly)?.dayOfWeek
                ?: 1,
        )
        named == "custom" || named == "interval" || args["intervalEvery"] != null -> AutomationSchedule.Interval(
            every = args["intervalEvery"]?.jsonPrimitive?.intOrNull ?: 1,
            unit = AutomationIntervalUnit.entries.firstOrNull {
                it.name.equals(str("intervalUnit"), ignoreCase = true)
            } ?: AutomationIntervalUnit.Hours,
            startAtMillis = nowMillis,
        )
        named == "cron" || str("cron") != null -> AutomationSchedule.Cron(str("cron") ?: "0 9 * * *")
        else -> existing?.schedule ?: AutomationSchedule.Daily
    }
}

fun automationToolNames(): List<String> = listOf(
    "automation.list",
    "automation.get",
    "automation.create",
    "automation.update",
    "automation.pause",
    "automation.resume",
    "automation.delete",
    "automation.run",
)
