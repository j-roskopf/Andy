package app.andy.desktop.service.agents

import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.ProjectReviewFinding
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectReviewVerdict
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectVerificationVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches `.andy/<taskId>/` for workflow artifact files.
 *
 * Contract:
 * - `plan.md` — spec phase output
 * - `review.json` — same schema as the former `<andy_review>` block
 * - `verification.json` — same schema as the former `<andy_verification>` block
 * - `question.json` — blocked automated phase → Andy decision card
 */
class AgentWorkflowArtifacts(
    private val scope: CoroutineScope,
    val taskId: String,
    val root: File,
) {
    sealed interface Event {
        data class PlanReady(val text: String) : Event
        data class ReviewReady(val json: String) : Event
        data class VerificationReady(val json: String) : Event
        data class QuestionReady(val request: AgentUserInputRequest) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val closed = AtomicBoolean(false)
    private var job: Job? = null
    private val seen = mutableSetOf<String>()

    val planFile get() = File(root, "plan.md")
    val reviewFile get() = File(root, "review.json")
    val verificationFile get() = File(root, "verification.json")
    val questionFile get() = File(root, "question.json")
    val answerFile get() = File(root, "answer.json")
    val statusFile get() = File(root, "status.json")

    fun start() {
        root.mkdirs()
        job = scope.launch {
            while (isActive && !closed.get()) {
                pollOnce()
                delay(350)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
    }

    fun writeAnswer(text: String) {
        root.mkdirs()
        answerFile.writeText(
            """{"answer":${JsonPrimitive(text)}}""",
        )
    }

    private suspend fun pollOnce() {
        maybeEmit("plan", planFile) { text ->
            text.trim().takeIf { it.isNotBlank() }?.let { Event.PlanReady(it) }
        }
        maybeEmit("review", reviewFile) { text ->
            text.trim().takeIf { it.isNotBlank() }?.let { Event.ReviewReady(it) }
        }
        maybeEmit("verification", verificationFile) { text ->
            text.trim().takeIf { it.isNotBlank() }?.let { Event.VerificationReady(it) }
        }
        maybeEmit("question", questionFile) { text ->
            parseQuestionJson(text)?.let { Event.QuestionReady(it) }
        }
    }

    private suspend fun maybeEmit(key: String, file: File, parse: (String) -> Event?) {
        if (!file.isFile) return
        val stamp = "$key:${file.lastModified()}:${file.length()}"
        if (stamp in seen) return
        val event = runCatching { parse(file.readText()) }.getOrNull() ?: return
        seen += stamp
        _events.emit(event)
    }

    companion object {
        fun dirFor(cwd: File?, taskId: String): File {
            val base = cwd?.takeIf { it.path.isNotBlank() } ?: AgentScratchWorkspace.path()
            return File(base, ".andy/$taskId")
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun parseReviewJson(
            raw: String,
            runId: String,
            reviewedBuildRunId: String,
            reviewGeneration: Int,
            atMillis: Long,
        ): ProjectReviewVerdict? = runCatching {
            val value = json.parseToJsonElement(raw.trim()).jsonObject
            val status = when (value["status"]?.jsonPrimitive?.content?.lowercase()) {
                "approved" -> ProjectReviewStatus.Approved
                "changes_requested" -> ProjectReviewStatus.ChangesRequested
                else -> return null
            }
            val summary = value["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (summary.isBlank()) return null
            val findings = value["findings"]?.jsonArray?.map { element ->
                val finding = element.jsonObject
                val severity = when (finding["severity"]?.jsonPrimitive?.content?.lowercase()) {
                    "blocking" -> ProjectReviewFindingSeverity.Blocking
                    "warning" -> ProjectReviewFindingSeverity.Warning
                    "nit" -> ProjectReviewFindingSeverity.Nit
                    else -> return null
                }
                val title = finding["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                val details = finding["details"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isBlank() || details.isBlank()) return null
                val file = finding["file"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val line = finding["line"]?.jsonPrimitive?.content?.toIntOrNull()
                if (line != null && line <= 0) return null
                ProjectReviewFinding(severity, title, details, file, line)
            }.orEmpty()
            val blocking = findings.count { it.severity == ProjectReviewFindingSeverity.Blocking }
            if (status == ProjectReviewStatus.Approved && blocking > 0) return null
            if (status == ProjectReviewStatus.ChangesRequested && blocking == 0) return null
            ProjectReviewVerdict(status, summary, findings, runId, reviewedBuildRunId, reviewGeneration, atMillis)
        }.getOrNull()

        fun parseVerificationJson(
            raw: String,
            runId: String,
            atMillis: Long,
            reviewedBuildRunId: String?,
            reviewGeneration: Int,
        ): ProjectVerificationVerdict? = runCatching {
            val value = json.parseToJsonElement(raw.trim()).jsonObject
            val statusText = value["status"]?.jsonPrimitive?.content ?: return null
            val status = when (statusText.lowercase()) {
                "passed" -> ProjectVerificationStatus.Passed
                "failed" -> ProjectVerificationStatus.Failed
                else -> return null
            }
            val summary = value["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
            val evidence = value["evidence"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val failures = value["failures"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotBlank() }.orEmpty()
            if (summary.isBlank()) return null
            if (status == ProjectVerificationStatus.Passed && (evidence.isEmpty() || failures.isNotEmpty())) return null
            if (status == ProjectVerificationStatus.Failed && failures.isEmpty()) return null
            ProjectVerificationVerdict(status, summary, evidence, failures, runId, atMillis, reviewedBuildRunId, reviewGeneration)
        }.getOrNull()

        fun parseQuestionJson(raw: String): AgentUserInputRequest? = runCatching {
            val root = json.parseToJsonElement(raw.trim()).jsonObject
            val questions = root["questions"] as? JsonArray ?: return null
            if (questions.size !in 1..3) return null
            val parsed = questions.mapNotNull { element ->
                val q = element as? JsonObject ?: return@mapNotNull null
                val id = (q["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
                    it.matches(Regex("[a-z][a-z0-9_]{0,63}"))
                } ?: return@mapNotNull null
                val text = (q["question"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val options = (q["options"] as? JsonArray)?.mapNotNull { opt ->
                    when (opt) {
                        is JsonPrimitive -> opt.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let {
                            AgentUserInputOption(it)
                        }
                        is JsonObject -> {
                            val label = listOf("label", "title", "text")
                                .firstNotNullOfOrNull { key -> (opt[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }
                                ?: return@mapNotNull null
                            AgentUserInputOption(label, (opt["description"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                        }
                        else -> null
                    }
                }.orEmpty()
                if (options.size !in 2..3) return@mapNotNull null
                AgentUserInputQuestion(
                    id = id,
                    header = (q["header"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                    question = text,
                    options = options,
                )
            }
            if (parsed.size != questions.size) return null
            AgentUserInputRequest(
                id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: UUID.randomUUID().toString(),
                questions = parsed,
            )
        }.getOrNull()
    }
}
