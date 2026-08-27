package app.andy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MinAutomationIntervalMillis = 15L * 60L * 1000L

@Serializable
enum class AutomationMode(val label: String) {
    Standalone("Standalone"),
    Dedicated("Dedicated thread"),
    Heartbeat("Heartbeat"),
}

@Serializable
enum class AutomationNotify(val label: String) {
    AllRuns("All runs"),
    FailedOnly("Failed runs only"),
}

@Serializable
enum class AutomationFailurePolicy(val label: String, val consecutiveLimit: Int?) {
    StopAfter1("Stop after 1 failure", 1),
    StopAfter3("Stop after 3 failures", 3),
    StopAfter5("Stop after 5 failures", 5),
    KeepRunning("Keep running", null),
}

@Serializable
enum class AutomationMaxIterations(val label: String, val limit: Int?) {
    Unlimited("Unlimited", null),
    Runs10("10 runs", 10),
    Runs25("25 runs", 25),
    Runs50("50 runs", 50),
    Runs100("100 runs", 100),
    Runs250("250 runs", 250),
}

@Serializable
enum class AutomationIntervalUnit(val label: String) {
    Minutes("minutes"),
    Hours("hours"),
    Days("days"),
}

@Serializable
sealed interface AutomationSchedule {
    @Serializable
    @SerialName("manual")
    data object Manual : AutomationSchedule

    @Serializable
    @SerialName("once")
    data class Once(val atMillis: Long) : AutomationSchedule

    @Serializable
    @SerialName("hourly")
    data object Hourly : AutomationSchedule

    @Serializable
    @SerialName("daily")
    data object Daily : AutomationSchedule

    @Serializable
    @SerialName("weekdays")
    data object Weekdays : AutomationSchedule

    @Serializable
    @SerialName("weekly")
    data class Weekly(val dayOfWeek: Int) : AutomationSchedule

    @Serializable
    @SerialName("interval")
    data class Interval(
        val every: Int,
        val unit: AutomationIntervalUnit,
        val startAtMillis: Long,
    ) : AutomationSchedule

    @Serializable
    @SerialName("cron")
    data class Cron(val expression: String) : AutomationSchedule
}

@Serializable
data class AutomationLaunchSnapshot(
    val agent: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val autonomy: String = AgentAutonomy.Standard.name,
    val directory: String? = null,
) {
    fun agentKind(): AgentKind =
        AgentKind.entries.firstOrNull { it.name.equals(agent, ignoreCase = true) } ?: AgentKind.Codex

    fun effort(): AgentReasoningEffort? =
        AgentReasoningEffort.entries.firstOrNull { it.name.equals(reasoningEffort, ignoreCase = true) }

    fun autonomyLevel(): AgentAutonomy =
        AgentAutonomy.entries.firstOrNull { it.name.equals(autonomy, ignoreCase = true) }
            ?: AgentAutonomy.Standard
}

@Serializable
data class AutomationRunRecord(
    val id: String,
    val taskId: String? = null,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val outcome: String,
    val detail: String? = null,
)

@Serializable
data class Automation(
    val id: String,
    val projectId: String,
    val title: String,
    val prompt: String,
    val mode: AutomationMode = AutomationMode.Standalone,
    val schedule: AutomationSchedule = AutomationSchedule.Daily,
    val timeZone: String,
    val runHour: Int = 9,
    val runMinute: Int = 0,
    val stopWhen: String = "",
    val failurePolicy: AutomationFailurePolicy = AutomationFailurePolicy.StopAfter3,
    val maxIterations: AutomationMaxIterations = AutomationMaxIterations.Unlimited,
    val notify: AutomationNotify = AutomationNotify.AllRuns,
    val useWorktree: Boolean = false,
    val cleanupWorktree: Boolean = false,
    val heartbeatTaskId: String? = null,
    val boundTaskId: String? = null,
    val lastTaskId: String? = null,
    val launch: AutomationLaunchSnapshot,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val consecutiveFailures: Int = 0,
    val fireCount: Int = 0,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastFiredAtMillis: Long? = null,
    val nextRunAtMillis: Long? = null,
    val runs: List<AutomationRunRecord> = emptyList(),
)

fun Automation.cadenceLabel(): String {
    val time = "${runHour.toString().padStart(2, '0')}:${runMinute.toString().padStart(2, '0')}"
    return when (val item = schedule) {
        AutomationSchedule.Manual -> "Manual"
        is AutomationSchedule.Once -> "Once at $time"
        AutomationSchedule.Hourly -> "Hourly at :${runMinute.toString().padStart(2, '0')}"
        AutomationSchedule.Daily -> "Daily at $time"
        AutomationSchedule.Weekdays -> "Weekdays at $time"
        is AutomationSchedule.Weekly -> "Weekly at $time"
        is AutomationSchedule.Interval -> "Every ${item.every} ${item.unit.label}"
        is AutomationSchedule.Cron -> "Cron ${item.expression}"
    }
}

data class AutomationDraft(
    val id: String? = null,
    val projectId: String,
    val title: String,
    val prompt: String,
    val mode: AutomationMode = AutomationMode.Standalone,
    val schedule: AutomationSchedule = AutomationSchedule.Daily,
    val timeZone: String,
    val runHour: Int = 9,
    val runMinute: Int = 0,
    val stopWhen: String = "",
    val failurePolicy: AutomationFailurePolicy = AutomationFailurePolicy.StopAfter3,
    val maxIterations: AutomationMaxIterations = AutomationMaxIterations.Unlimited,
    val notify: AutomationNotify = AutomationNotify.AllRuns,
    val useWorktree: Boolean = false,
    val cleanupWorktree: Boolean = false,
    val heartbeatTaskId: String? = null,
    val launch: AutomationLaunchSnapshot,
    val paused: Boolean = false,
)

fun parseAndyStopTag(text: String): Boolean? {
    val matches = Regex("""ANDY_STOP\s*=\s*(YES|NO)""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .toList()
    val last = matches.lastOrNull() ?: return null
    return last.groupValues[1].equals("YES", ignoreCase = true)
}

fun evaluatorStopWhenPrompt(condition: String): String = buildString {
    appendLine("The scheduled work turn finished. Evaluate whether this stop condition holds:")
    appendLine()
    appendLine(condition.trim())
    appendLine()
    appendLine("Reply with a brief justification, then end with exactly one of these tags on its own line:")
    appendLine("ANDY_STOP=YES")
    appendLine("ANDY_STOP=NO")
}

data class AutomationPolicyResult(
    val paused: Boolean,
    val pauseReason: String?,
    val consecutiveFailures: Int,
    val fireCount: Int,
)

fun applyAutomationWorkOutcome(
    automation: Automation,
    workFailed: Boolean,
    stopWhenYes: Boolean,
): AutomationPolicyResult {
    val fireCount = automation.fireCount + 1
    val consecutiveFailures = if (workFailed) automation.consecutiveFailures + 1 else 0
    val failureLimit = automation.failurePolicy.consecutiveLimit
    val iterationLimit = automation.maxIterations.limit
    val (paused, reason) = when {
        stopWhenYes -> true to "Stop condition met"
        failureLimit != null && consecutiveFailures >= failureLimit ->
            true to "Stopped after $consecutiveFailures consecutive failure${if (consecutiveFailures == 1) "" else "s"}"
        iterationLimit != null && fireCount >= iterationLimit ->
            true to "Reached $iterationLimit run${if (iterationLimit == 1) "" else "s"}"
        else -> false to null
    }
    return AutomationPolicyResult(
        paused = paused,
        pauseReason = reason,
        consecutiveFailures = consecutiveFailures,
        fireCount = fireCount,
    )
}
