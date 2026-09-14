package app.andy.model

/**
 * Pure projection of an ACP [AgentEvent] transcript into a turn-aware timeline.
 * No Compose; safe to unit-test and to rebuild on a debounce while a session streams.
 */

enum class TimelineAxis {
    Turns,
    Calls,
}

enum class TimelineLane {
    Input,
    Model,
    Tools,
}

enum class TimelineRowKind {
    System,
    User,
    Context,
    Assistant,
    Thinking,
    Tool,
    Result,
    Permission,
    FileChanges,
    Error,
}

data class TimelineRow(
    val key: String,
    val turnNumber: Int,
    val stepNumber: Int,
    val kind: TimelineRowKind,
    val badge: String,
    val title: String,
    val resultPreview: String?,
    val startMillis: Long,
    val endMillis: Long,
    val timingApproximate: Boolean,
    val lane: TimelineLane,
    /** Index into the coalesced event list used to build this model; -1 for synthetic CONTEXT rows. */
    val eventIndex: Int,
    /** Stable index among [TimelineLane.Tools] rows (0-based); -1 when not a tool row. */
    val callIndex: Int = -1,
    /**
     * Axis used by Duration lane painting: zero-width events expand to the next step so cells
     * tile the active span (DeepSeek-style), while measured tool intervals stay intact.
     */
    val displayStartMillis: Long = startMillis,
    val displayEndMillis: Long = endMillis,
    /** 0-based index among [TimelineModel.rows] — equal-width Turns/Calls cells. */
    val stepIndex: Int = 0,
)

data class TimelineTurn(
    val number: Int,
    val rowKeys: List<String>,
    val stepCount: Int,
    val toolCallCount: Int,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

data class TimelineModel(
    val rows: List<TimelineRow>,
    val turns: List<TimelineTurn>,
    val spanStartMillis: Long,
    val spanEndMillis: Long,
    val totalCalls: Int,
)

data class TimelineTiming(
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
    val durationMs: Long?,
    val approximate: Boolean,
    val sourceLabel: String,
)

data class TimelineDetail(
    val header: String,
    val summary: String,
    val payload: String?,
    val result: String?,
    val timing: TimelineTiming?,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/**
 * Same turn-boundary rule as `AgentFileChangesEnrichment.isTranscriptTurnBoundary`,
 * kept in domain so the timeline model has no desktop dependency.
 */
fun AgentEvent.isTimelineTurnBoundary(): Boolean = when (this) {
    is AgentEvent.UserMessage -> true
    is AgentEvent.TaskResult, is AgentEvent.TaskError -> true
    else -> false
}

fun buildChatTimeline(
    events: List<AgentEvent>,
    task: AgentTask? = null,
    axis: TimelineAxis = TimelineAxis.Turns,
): TimelineModel {
    // Axis is accepted for call-site stability; row construction is axis-independent.
    // Turns-axis collapse is a view concern over [TimelineTurn] metadata.
    @Suppress("UNUSED_PARAMETER")
    val unusedAxis = axis

    val coalesced = coalesceAcpTranscriptEvents(events)
    if (coalesced.isEmpty() && task == null) {
        return TimelineModel(
            rows = emptyList(),
            turns = emptyList(),
            spanStartMillis = 0L,
            spanEndMillis = 0L,
            totalCalls = 0,
        )
    }

    val rows = ArrayList<TimelineRow>()
    val turnAcc = LinkedHashMap<Int, TurnAcc>()
    var turnNumber = 0
    var stepInTurn = 0
    var callIndex = 0
    var seenUserInCurrentTurn = false
    var previousEventAtMillis = task?.createdAtMillis ?: coalesced.firstOrNull()?.atMillis ?: 0L
    // Tool calls not yet paired with a ToolResult, as (tool name, row index), so the fallback
    // result row is only added for orphan results instead of double-counting a stored call.
    val unmatchedToolCalls = ArrayList<Pair<String?, Int>>()
    // Latest TaskResult token counts, applied when the turn closes / next user starts.
    var pendingInputTokens: Long? = null
    var pendingOutputTokens: Long? = null

    fun ensureTurn(): Int {
        if (turnNumber == 0) turnNumber = 1
        return turnNumber
    }

    fun beginUserTurn() {
        if (seenUserInCurrentTurn) {
            turnAcc.getOrPut(turnNumber) { TurnAcc() }.apply {
                inputTokens = inputTokens ?: pendingInputTokens
                outputTokens = outputTokens ?: pendingOutputTokens
            }
            pendingInputTokens = null
            pendingOutputTokens = null
            turnNumber++
            stepInTurn = 0
            // Pairing is per-turn: an unpaired call from a finished turn must not suppress a
            // later turn's orphan result that happens to share a tool name.
            unmatchedToolCalls.clear()
        } else if (turnNumber == 0) {
            turnNumber = 1
        }
        // else: preamble (e.g. SessionStarted) already opened this turn — first user joins it.
        seenUserInCurrentTurn = true
    }

    fun nextStep(): Int {
        stepInTurn++
        return stepInTurn
    }

    fun addRow(row: TimelineRow) {
        rows += row
        val acc = turnAcc.getOrPut(row.turnNumber) { TurnAcc() }
        acc.rowKeys += row.key
        acc.stepCount = maxOf(acc.stepCount, row.stepNumber)
        if (row.kind == TimelineRowKind.Tool) acc.toolCallCount++
    }

    coalesced.forEachIndexed { eventIndex, event ->
        when (event) {
            is AgentEvent.UserMessage -> {
                beginUserTurn()
                val turn = turnNumber
                val userStart = event.atMillis
                val userEnd = event.atMillis
                addRow(
                    TimelineRow(
                        key = "user-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.User,
                        badge = "USER",
                        title = event.text.trim().ifBlank { "(empty message)" }.singleLine(120),
                        resultPreview = null,
                        startMillis = userStart,
                        endMillis = userEnd,
                        timingApproximate = false,
                        lane = TimelineLane.Input,
                        eventIndex = eventIndex,
                    ),
                )
                event.skills.forEachIndexed { skillIndex, skill ->
                    addRow(
                        TimelineRow(
                            key = "ctx-skill-$eventIndex-$skillIndex",
                            turnNumber = turn,
                            stepNumber = nextStep(),
                            kind = TimelineRowKind.Context,
                            badge = "CONTEXT",
                            title = "Skill · ${skill.name}".singleLine(120),
                            resultPreview = skill.description.trim().takeIf { it.isNotBlank() }?.singleLine(80),
                            startMillis = userStart,
                            endMillis = userEnd,
                            timingApproximate = false,
                            lane = TimelineLane.Input,
                            eventIndex = eventIndex,
                        ),
                    )
                }
                event.attachments.forEachIndexed { attachmentIndex, attachment ->
                    addRow(
                        TimelineRow(
                            key = "ctx-attach-$eventIndex-$attachmentIndex",
                            turnNumber = turn,
                            stepNumber = nextStep(),
                            kind = TimelineRowKind.Context,
                            badge = "CONTEXT",
                            title = "Attachment · ${attachment.displayName}".singleLine(120),
                            resultPreview = attachment.metadataLabel(),
                            startMillis = userStart,
                            endMillis = userEnd,
                            timingApproximate = false,
                            lane = TimelineLane.Input,
                            eventIndex = eventIndex,
                        ),
                    )
                }
                event.imagePaths.forEachIndexed { imageIndex, path ->
                    val name = path.substringAfterLast('/').ifBlank { path }
                    addRow(
                        TimelineRow(
                            key = "ctx-image-$eventIndex-$imageIndex",
                            turnNumber = turn,
                            stepNumber = nextStep(),
                            kind = TimelineRowKind.Context,
                            badge = "CONTEXT",
                            title = "Image · $name".singleLine(120),
                            resultPreview = null,
                            startMillis = userStart,
                            endMillis = userEnd,
                            timingApproximate = false,
                            lane = TimelineLane.Input,
                            eventIndex = eventIndex,
                        ),
                    )
                }
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.SessionStarted -> {
                val turn = ensureTurn()
                val title = buildString {
                    append("Session started")
                    event.model?.trim()?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                }
                addRow(
                    TimelineRow(
                        key = "system-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.System,
                        badge = "SYSTEM",
                        title = title.singleLine(120),
                        resultPreview = event.sessionId?.takeIf { it.isNotBlank() },
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Input,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.AssistantText -> {
                val text = event.text.trim()
                if (text.isBlank()) {
                    previousEventAtMillis = event.atMillis
                    return@forEachIndexed
                }
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "assistant-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Assistant,
                        badge = "ASSISTANT",
                        title = text.singleLine(120),
                        resultPreview = null,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Model,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.Thinking -> {
                val text = event.text.trim()
                if (text.isBlank()) {
                    previousEventAtMillis = event.atMillis
                    return@forEachIndexed
                }
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "thinking-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Thinking,
                        badge = "THINKING",
                        title = text.singleLine(120),
                        resultPreview = null,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Model,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.ToolCall -> {
                val turn = ensureTurn()
                val measuredStart = event.startedAtMillis
                val measuredEnd = event.endedAtMillis
                val start = measuredStart ?: previousEventAtMillis
                val end = measuredEnd ?: event.atMillis.coerceAtLeast(start)
                val approximate = measuredStart == null || measuredEnd == null
                val payload = AcpToolCallPresentation.extractLikelyInput(event.detail)
                val output = AcpToolCallPresentation.extractLikelyOutput(event.detail)
                val title = buildString {
                    append(event.toolName.trim().ifBlank { "tool" })
                    val summary = event.summary.trim()
                    if (summary.isNotBlank() && !summary.equals(event.toolName, ignoreCase = true)) {
                        append(' ')
                        append(summary.singleLine(100))
                    } else if (!payload.isNullOrBlank()) {
                        append(' ')
                        append(payload.singleLine(100))
                    }
                }
                addRow(
                    TimelineRow(
                        key = "tool-${event.toolCallId ?: eventIndex}",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Tool,
                        badge = "TOOL",
                        title = title.singleLine(140),
                        resultPreview = output?.singleLine(80),
                        startMillis = start,
                        endMillis = end,
                        timingApproximate = approximate,
                        lane = TimelineLane.Tools,
                        eventIndex = eventIndex,
                        callIndex = callIndex++,
                    ),
                )
                unmatchedToolCalls += event.toolName.trim().ifBlank { null } to rows.lastIndex
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.ToolResult -> {
                // Prefer ToolCall rows; orphan results still appear when no matching call was stored.
                val resultName = event.toolName?.trim()?.ifBlank { null }
                val matchIndex = when {
                    unmatchedToolCalls.isEmpty() -> -1
                    resultName != null -> unmatchedToolCalls.indexOfLast { it.first == resultName }
                    else -> unmatchedToolCalls.lastIndex
                }
                if (matchIndex >= 0) {
                    val (_, callRowIndex) = unmatchedToolCalls.removeAt(matchIndex)
                    val callRow = rows[callRowIndex]
                    val resultPreview = event.detail.trim()
                        .takeIf { it.isNotBlank() && it != event.summary }
                        ?.singleLine(80)
                    if (event.isError) {
                        // Keep the failure visible as an error row, but it belongs to the stored
                        // call: reuse its call ordinal so the invocation is counted once.
                        addRow(
                            TimelineRow(
                                key = "tool-result-$eventIndex",
                                turnNumber = callRow.turnNumber,
                                stepNumber = nextStep(),
                                kind = TimelineRowKind.Error,
                                badge = "ERROR",
                                title = (resultName ?: callRow.title).singleLine(140),
                                resultPreview = resultPreview,
                                startMillis = event.atMillis,
                                endMillis = event.atMillis,
                                timingApproximate = true,
                                lane = TimelineLane.Tools,
                                eventIndex = eventIndex,
                                callIndex = callRow.callIndex,
                            ),
                        )
                    } else if (resultPreview != null && callRow.resultPreview.isNullOrBlank()) {
                        // Fold the stored call's output preview in so it stays visible/searchable.
                        rows[callRowIndex] = callRow.copy(resultPreview = resultPreview)
                    }
                    previousEventAtMillis = event.atMillis
                    return@forEachIndexed
                }
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "tool-result-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = if (event.isError) TimelineRowKind.Error else TimelineRowKind.Tool,
                        badge = if (event.isError) "ERROR" else "TOOL",
                        title = (event.toolName?.trim().orEmpty().ifBlank { "tool result" } +
                            " " + event.summary.trim()).trim().singleLine(140),
                        resultPreview = event.detail.trim().takeIf { it.isNotBlank() && it != event.summary }?.singleLine(80),
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = true,
                        lane = TimelineLane.Tools,
                        eventIndex = eventIndex,
                        callIndex = callIndex++,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.TaskResult -> {
                val turn = ensureTurn()
                pendingInputTokens = event.inputTokens
                pendingOutputTokens = event.outputTokens
                turnAcc.getOrPut(turn) { TurnAcc() }.apply {
                    inputTokens = event.inputTokens
                    outputTokens = event.outputTokens
                }
                val preview = buildList {
                    event.durationMs?.let { add("${it} ms") }
                    val inTok = event.inputTokens
                    val outTok = event.outputTokens
                    if (inTok != null || outTok != null) {
                        add("↑${inTok ?: 0} ↓${outTok ?: 0}")
                    }
                }.joinToString(" · ").ifBlank { null }
                addRow(
                    TimelineRow(
                        key = "result-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Result,
                        badge = "RESULT",
                        title = when {
                            event.success -> event.finalText?.trim()?.takeIf { it.isNotBlank() }?.singleLine(120)
                                ?: "Completed"
                            else -> event.finalText?.trim()?.takeIf { it.isNotBlank() }?.singleLine(120)
                                ?: "Failed"
                        },
                        resultPreview = preview,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Model,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.TaskError -> {
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "error-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Error,
                        badge = "ERROR",
                        title = event.message.trim().ifBlank { "Error" }.singleLine(120),
                        resultPreview = null,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Model,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.PermissionRequest -> {
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "perm-req-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Permission,
                        badge = "PERMISSION",
                        title = "${event.toolName}: ${event.question}".singleLine(120),
                        resultPreview = null,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Tools,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.PermissionResolved -> {
                val turn = ensureTurn()
                addRow(
                    TimelineRow(
                        key = "perm-res-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.Permission,
                        badge = "PERMISSION",
                        title = (if (event.allowed) "Allowed" else "Denied") +
                            (event.note?.trim()?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        resultPreview = event.optionId,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Tools,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            is AgentEvent.FileChanges -> {
                val turn = ensureTurn()
                val files = event.snapshot.summary.files
                val fileCount = files.size
                val add = event.snapshot.summary.additions
                val del = event.snapshot.summary.deletions
                val title = when {
                    event.undone && fileCount == 1 -> "Undone · ${files.first().path}"
                    event.undone -> "File changes (undone)"
                    fileCount == 1 -> files.first().path
                    fileCount == 0 -> "File changes"
                    else -> "File changes"
                }
                val preview = when {
                    fileCount == 0 -> null
                    fileCount == 1 -> "+$add / -$del"
                    else -> "$fileCount files · +$add / -$del"
                }
                addRow(
                    TimelineRow(
                        key = "files-$eventIndex",
                        turnNumber = turn,
                        stepNumber = nextStep(),
                        kind = TimelineRowKind.FileChanges,
                        badge = "FILES",
                        title = title.singleLine(140),
                        resultPreview = preview,
                        startMillis = event.atMillis,
                        endMillis = event.atMillis,
                        timingApproximate = false,
                        lane = TimelineLane.Tools,
                        eventIndex = eventIndex,
                    ),
                )
                previousEventAtMillis = event.atMillis
            }

            // Bookkeeping / live metrics — not ledger rows.
            is AgentEvent.ContextUsage,
            is AgentEvent.PlanUpdate,
            is AgentEvent.ModeChanged,
            is AgentEvent.AvailableCommands,
            is AgentEvent.AvailableModes,
            is AgentEvent.SessionInfo,
            is AgentEvent.Raw,
            -> {
                previousEventAtMillis = event.atMillis
            }
        }
    }

    if (rows.isEmpty()) {
        val start = task?.createdAtMillis ?: 0L
        val end = task?.finishedAtMillis ?: task?.startedAtMillis ?: start
        return TimelineModel(emptyList(), emptyList(), start, end.coerceAtLeast(start), 0)
    }

    val displayRows = withDisplayIntervals(rows)
    var spanStart = displayRows.minOf { it.displayStartMillis }
    var spanEnd = displayRows.maxOf { it.displayEndMillis }
    // Prefer the active event span so idle task created/finished padding does not crush cells
    // into a thin cluster at one edge of the lane.
    if (spanEnd <= spanStart) spanEnd = spanStart + 1

    val turns = turnAcc.entries
        .sortedBy { it.key }
        .map { (number, acc) ->
            TimelineTurn(
                number = number,
                rowKeys = acc.rowKeys,
                stepCount = acc.stepCount,
                toolCallCount = acc.toolCallCount,
                inputTokens = acc.inputTokens,
                outputTokens = acc.outputTokens,
            )
        }

    return TimelineModel(
        rows = displayRows,
        turns = turns,
        spanStartMillis = spanStart,
        spanEndMillis = spanEnd,
        totalCalls = callIndex,
    )
}

/**
 * Inclusive brush over the projected axis. Fractions are 0..1 along the lane width.
 * A row matches when its projected interval overlaps [startFraction, endFraction].
 *
 * [durationMode] true → time-proportional cells; false → equal-width step cells.
 */
fun timelineBrushSelection(
    model: TimelineModel,
    axis: TimelineAxis,
    startFraction: Float,
    endFraction: Float,
    durationMode: Boolean = false,
): Set<String> {
    if (model.rows.isEmpty()) return emptySet()
    val lo = minOf(startFraction, endFraction).coerceIn(0f, 1f)
    val hi = maxOf(startFraction, endFraction).coerceIn(0f, 1f)
    if (hi <= lo) return emptySet()
    return model.rows.mapNotNullTo(linkedSetOf()) { row ->
        val (a, b) = row.projectedInterval(model, axis, durationMode)
        if (intervalsOverlap(a, b, lo, hi)) row.key else null
    }
}

/** Case-insensitive literal filter over badge/title/result preview. Empty query → all keys. */
fun timelineFilterRows(model: TimelineModel, query: String): Set<String> {
    val needle = query.trim()
    if (needle.isEmpty()) return model.rows.mapTo(linkedSetOf()) { it.key }
    return model.rows.mapNotNullTo(linkedSetOf()) { row ->
        val haystack = buildString {
            append(row.badge)
            append(' ')
            append(row.title)
            row.resultPreview?.let { append(' '); append(it) }
        }
        if (timelineLiteralMatches(haystack, needle)) row.key else null
    }
}

fun timelineRowDetail(
    row: TimelineRow,
    event: AgentEvent?,
    turn: TimelineTurn? = null,
): TimelineDetail {
    val header = "${row.badge} · Turn ${row.turnNumber} · Step ${row.stepNumber}"
    val timing = TimelineTiming(
        startedAtMillis = row.startMillis,
        endedAtMillis = row.endMillis,
        durationMs = (row.endMillis - row.startMillis).coerceAtLeast(0L),
        approximate = row.timingApproximate,
        sourceLabel = if (row.timingApproximate) "Approximated" else "Session timestamps",
    )

    // Synthetic CONTEXT rows point at the parent UserMessage — expand the matching skill/attachment/image.
    if (row.kind == TimelineRowKind.Context && event is AgentEvent.UserMessage) {
        return contextRowDetail(header, timing, row, event, turn)
    }

    return when (event) {
        is AgentEvent.ToolCall -> toolCallDetail(header, timing, event, turn)
        is AgentEvent.UserMessage -> userMessageDetail(header, timing, event, turn)
        is AgentEvent.AssistantText -> TimelineDetail(
            header = header,
            summary = event.text.trim().ifBlank { "_Empty assistant message._" },
            payload = null,
            result = null,
            timing = timing,
            inputTokens = turn?.inputTokens,
            outputTokens = turn?.outputTokens,
        )
        is AgentEvent.Thinking -> TimelineDetail(
            header = header,
            summary = event.text.trim().ifBlank { "_Empty thinking block._" },
            payload = null,
            result = null,
            timing = timing,
        )
        is AgentEvent.SessionStarted -> TimelineDetail(
            header = header,
            summary = buildString {
                append("**Session started**")
                event.model?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n**Model:** `").append(it).append('`')
                }
                event.sessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n**Session id:** `").append(it).append('`')
                }
            },
            payload = event.sessionId?.takeIf { it.isNotBlank() },
            result = event.model?.takeIf { it.isNotBlank() },
            timing = timing,
        )
        is AgentEvent.TaskResult -> taskResultDetail(header, timing, event, turn)
        is AgentEvent.TaskError -> TimelineDetail(
            header = header,
            summary = buildString {
                append("**Error**\n\n")
                append(event.message.trim().ifBlank { "_No error message._" })
            },
            payload = null,
            result = event.message.trim().takeIf { it.isNotBlank() },
            timing = timing,
        )
        is AgentEvent.ToolResult -> toolResultDetail(header, timing, event)
        is AgentEvent.PermissionRequest -> TimelineDetail(
            header = header,
            summary = buildString {
                append("**Permission request**\n\n")
                append("**Tool:** `").append(event.toolName).append("`\n\n")
                append(event.question.trim())
                if (event.options.isNotEmpty()) {
                    append("\n\n**Options:**\n")
                    event.options.forEach { opt ->
                        append("- **").append(opt.label).append("**")
                        if (opt.description.isNotBlank()) append(" — ").append(opt.description)
                        append('\n')
                    }
                }
                append("\nRequest id: `").append(event.requestId).append('`')
            },
            payload = event.options.joinToString("\n") { opt ->
                buildString {
                    append(opt.label)
                    if (opt.description.isNotBlank()) append(": ").append(opt.description)
                }
            }.ifBlank { null },
            result = event.requestId,
            timing = timing,
        )
        is AgentEvent.PermissionResolved -> TimelineDetail(
            header = header,
            summary = buildString {
                append(if (event.allowed) "**Allowed**" else "**Denied**")
                append(" · option `").append(event.optionId).append('`')
                event.note?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n").append(it)
                }
                append("\n\nRequest id: `").append(event.requestId).append('`')
            },
            payload = event.requestId,
            result = buildString {
                append(if (event.allowed) "allowed" else "denied")
                append(" / ").append(event.optionId)
                event.note?.let { append(" / ").append(it) }
            },
            timing = timing,
        )
        is AgentEvent.FileChanges -> fileChangesDetail(header, timing, event)
        null -> TimelineDetail(
            header = header,
            summary = row.title,
            payload = null,
            result = row.resultPreview,
            timing = timing,
            inputTokens = turn?.inputTokens,
            outputTokens = turn?.outputTokens,
        )
        else -> TimelineDetail(
            header = header,
            summary = buildString {
                append(row.title)
                append("\n\n_")
                append(event::class.simpleName ?: "event")
                append(" · no specialized detail formatter_")
            },
            payload = null,
            result = row.resultPreview,
            timing = timing,
            inputTokens = turn?.inputTokens,
            outputTokens = turn?.outputTokens,
        )
    }
}

private fun toolCallDetail(
    header: String,
    timing: TimelineTiming,
    event: AgentEvent.ToolCall,
    turn: TimelineTurn?,
): TimelineDetail {
    val input = AcpToolCallPresentation.extractLikelyInput(event.detail)
    val output = AcpToolCallPresentation.extractLikelyOutput(event.detail)
    val fullDetail = event.detail.trim()
    val framed = AcpToolCallPresentation.DetailSeparator in fullDetail
    val summaryMd = buildString {
        append("**").append(event.toolName.trim().ifBlank { "tool" }).append("**")
        event.kind?.let { append(" · `").append(it.name).append('`') }
        if (event.state != AgentToolState.Completed) {
            append(" · status `").append(event.state.name).append('`')
        }
        event.toolCallId?.trim()?.takeIf { it.isNotBlank() }?.let {
            append("\n\n**Call id:** `").append(it).append('`')
        }
        if (event.locations.isNotEmpty()) {
            append("\n\n**Locations:**\n")
            event.locations.forEach { append("- `").append(it).append("`\n") }
        }
        if (event.images.isNotEmpty()) {
            append("\n\n_").append(event.images.size).append(" inline image")
            if (event.images.size != 1) append('s')
            append('_')
        }
        val body = when {
            // Framed ACP detail is split into Payload/Result tabs — don't dump the wire format here.
            framed -> event.summary.trim().takeIf {
                it.isNotBlank() && !it.equals(event.toolName.trim(), ignoreCase = true)
            }
            fullDetail.isNotBlank() -> fullDetail
            event.summary.isNotBlank() -> event.summary.trim()
            else -> null
        }
        if (!body.isNullOrBlank()) {
            append("\n\n")
            append(body)
        }
        if (framed) {
            val bits = buildList {
                if (!input.isNullOrBlank()) add("Payload")
                if (!output.isNullOrBlank()) add("Result")
            }
            if (bits.isNotEmpty()) {
                append("\n\n_See ").append(bits.joinToString(" / ")).append(" tab")
                if (bits.size != 1) append('s')
                append(" for full tool I/O._")
            }
        }
        event.startedAtMillis?.let {
            append("\n\nStarted: `").append(it).append('`')
        }
        event.endedAtMillis?.let {
            append("  ·  Ended: `").append(it).append('`')
        }
    }.trim()
    return TimelineDetail(
        header = header,
        summary = summaryMd.ifBlank { "_No tool details._" },
        payload = input ?: fullDetail.takeIf { it.isNotBlank() && !framed && output == null },
        result = output,
        timing = timing.copy(
            startedAtMillis = event.startedAtMillis ?: timing.startedAtMillis,
            endedAtMillis = event.endedAtMillis ?: timing.endedAtMillis,
            durationMs = run {
                val start = event.startedAtMillis
                val end = event.endedAtMillis
                if (start != null && end != null) (end - start).coerceAtLeast(0L) else timing.durationMs
            },
            approximate = event.startedAtMillis == null || event.endedAtMillis == null,
            sourceLabel = when {
                event.startedAtMillis != null && event.endedAtMillis != null -> "Tool call timestamps"
                else -> timing.sourceLabel
            },
        ),
        inputTokens = turn?.inputTokens,
        outputTokens = turn?.outputTokens,
    )
}

private fun toolResultDetail(
    header: String,
    timing: TimelineTiming,
    event: AgentEvent.ToolResult,
): TimelineDetail {
    val summaryMd = buildString {
        if (event.isError) append("**Tool error**") else append("**Tool result**")
        event.toolName?.trim()?.takeIf { it.isNotBlank() }?.let {
            append(" · `").append(it).append('`')
        }
        if (event.summary.isNotBlank()) {
            append("\n\n")
            append(event.summary.trim())
        }
        if (event.quotaWindows.isNotEmpty()) {
            append("\n\n**Quota:**\n")
            event.quotaWindows.forEach { window ->
                append("- **").append(window.label).append("**")
                window.remainingFraction?.let { append(" · remaining ").append((it * 100).toInt()).append('%') }
                window.resetAtMillis?.let { append(" · reset `").append(it).append('`') }
                window.detail?.trim()?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                append('\n')
            }
        }
    }.trim()
    return TimelineDetail(
        header = header,
        summary = summaryMd.ifBlank { "_No tool result._" },
        payload = event.toolName,
        result = event.detail.trim().takeIf { it.isNotBlank() && it != event.summary.trim() }
            ?: event.detail.trim().takeIf { it.isNotBlank() },
        timing = timing,
    )
}

private fun userMessageDetail(
    header: String,
    timing: TimelineTiming,
    event: AgentEvent.UserMessage,
    turn: TimelineTurn?,
): TimelineDetail {
    val summaryMd = buildString {
        append(event.text.trim().ifBlank { "_Empty message._" })
        if (event.skills.isNotEmpty()) {
            append("\n\n**Skills:**\n")
            event.skills.forEach { skill ->
                append("- **`").append(skill.name).append("`**")
                if (skill.description.isNotBlank()) append(" — ").append(skill.description.trim())
                if (skill.path.isNotBlank()) append("\n  `").append(skill.path).append('`')
                if (!skill.userInvocable) append("\n  _Not user-invocable_")
                append('\n')
            }
        }
        if (event.attachments.isNotEmpty()) {
            append("\n\n**Attachments:**\n")
            event.attachments.forEach { attachment ->
                append("- **").append(attachment.displayName).append("**")
                append(" · ").append(attachment.metadataLabel())
                append(" · `").append(attachment.id).append('`')
                attachment.relativePath?.takeIf { it.isNotBlank() }?.let {
                    append("\n  `").append(it).append('`')
                }
                append("\n  sha256 `").append(attachment.sha256.take(12)).append("…`")
                append(" · ").append(attachment.mediaType)
                append('\n')
            }
        }
        if (event.imagePaths.isNotEmpty()) {
            append("\n\n**Images:**\n")
            event.imagePaths.forEach { path ->
                append("- `").append(path).append("`\n")
            }
        }
    }.trim()
    val payload = buildList {
        event.skills.forEach { add("skill:${it.name}") }
        event.attachments.forEach { add("attachment:${it.displayName}") }
        event.imagePaths.forEach { add("image:$it") }
    }.joinToString("\n").ifBlank { null }
    return TimelineDetail(
        header = header,
        summary = summaryMd,
        payload = payload,
        result = null,
        timing = timing,
        inputTokens = turn?.inputTokens,
        outputTokens = turn?.outputTokens,
    )
}

private fun contextRowDetail(
    header: String,
    timing: TimelineTiming,
    row: TimelineRow,
    event: AgentEvent.UserMessage,
    turn: TimelineTurn?,
): TimelineDetail {
    val skillMatch = Regex("""^ctx-skill-\d+-(\d+)$""").matchEntire(row.key)
    val attachMatch = Regex("""^ctx-attach-\d+-(\d+)$""").matchEntire(row.key)
    val imageMatch = Regex("""^ctx-image-\d+-(\d+)$""").matchEntire(row.key)
    return when {
        skillMatch != null -> {
            val skill = event.skills.getOrNull(skillMatch.groupValues[1].toInt())
            TimelineDetail(
                header = header,
                summary = buildString {
                    append("**Skill**")
                    if (skill == null) {
                        append("\n\n").append(row.title)
                        return@buildString
                    }
                    append(" · `").append(skill.name).append('`')
                    if (skill.description.isNotBlank()) {
                        append("\n\n").append(skill.description.trim())
                    }
                    if (skill.path.isNotBlank()) {
                        append("\n\n**Path:** `").append(skill.path).append('`')
                    }
                    append("\n\nUser-invocable: `").append(skill.userInvocable).append('`')
                },
                payload = skill?.path,
                result = skill?.description?.takeIf { it.isNotBlank() },
                timing = timing,
                inputTokens = turn?.inputTokens,
                outputTokens = turn?.outputTokens,
            )
        }
        attachMatch != null -> {
            val attachment = event.attachments.getOrNull(attachMatch.groupValues[1].toInt())
            TimelineDetail(
                header = header,
                summary = buildString {
                    append("**Attachment**")
                    if (attachment == null) {
                        append("\n\n").append(row.title)
                        return@buildString
                    }
                    append(" · **").append(attachment.displayName).append("**\n\n")
                    append("- Kind: `").append(attachment.kind.name).append("`\n")
                    append("- Media: `").append(attachment.mediaType).append("`\n")
                    append("- Size: ").append(attachment.metadataLabel()).append('\n')
                    append("- Id: `").append(attachment.id).append("`\n")
                    append("- SHA-256: `").append(attachment.sha256).append("`\n")
                    attachment.relativePath?.takeIf { it.isNotBlank() }?.let {
                        append("- Path: `").append(it).append("`\n")
                    }
                },
                payload = attachment?.relativePath ?: attachment?.id,
                result = attachment?.sha256,
                timing = timing,
                inputTokens = turn?.inputTokens,
                outputTokens = turn?.outputTokens,
            )
        }
        imageMatch != null -> {
            val path = event.imagePaths.getOrNull(imageMatch.groupValues[1].toInt())
            TimelineDetail(
                header = header,
                summary = buildString {
                    append("**Image**\n\n")
                    append('`').append(path ?: row.title).append('`')
                },
                payload = path,
                result = null,
                timing = timing,
                inputTokens = turn?.inputTokens,
                outputTokens = turn?.outputTokens,
            )
        }
        else -> TimelineDetail(
            header = header,
            summary = row.title,
            payload = null,
            result = row.resultPreview,
            timing = timing,
            inputTokens = turn?.inputTokens,
            outputTokens = turn?.outputTokens,
        )
    }
}

private fun taskResultDetail(
    header: String,
    timing: TimelineTiming,
    event: AgentEvent.TaskResult,
    turn: TimelineTurn?,
): TimelineDetail {
    val summaryMd = buildString {
        append(if (event.success) "**Completed**" else "**Failed**")
        event.durationMs?.let { append(" · `").append(it).append(" ms`") }
        event.costUsd?.let { cost ->
            append("\n\n**Cost:** `$")
            append(cost)
            append('`')
            if (event.costIsEstimated) append(" _(estimated)_")
        }
        val inTok = event.inputTokens ?: turn?.inputTokens
        val outTok = event.outputTokens ?: turn?.outputTokens
        if (inTok != null || outTok != null) {
            append("\n\n**Tokens:**")
            inTok?.let { append(" input `").append(it).append('`') }
            outTok?.let { append(" · output `").append(it).append('`') }
        }
        event.finalText?.trim()?.takeIf { it.isNotBlank() }?.let {
            append("\n\n")
            append(it)
        }
    }.trim()
    return TimelineDetail(
        header = header,
        summary = summaryMd,
        payload = buildList {
            event.costUsd?.let { add("costUsd=$it estimated=${event.costIsEstimated}") }
            event.inputTokens?.let { add("inputTokens=$it") }
            event.outputTokens?.let { add("outputTokens=$it") }
            event.durationMs?.let { add("durationMs=$it") }
        }.joinToString("\n").ifBlank { null },
        result = event.finalText,
        timing = timing.copy(
            durationMs = event.durationMs ?: timing.durationMs,
            approximate = event.durationMs == null && timing.approximate,
            sourceLabel = if (event.durationMs != null) "Task result duration" else timing.sourceLabel,
        ),
        inputTokens = event.inputTokens ?: turn?.inputTokens,
        outputTokens = event.outputTokens ?: turn?.outputTokens,
    )
}

private fun fileChangesDetail(
    header: String,
    timing: TimelineTiming,
    event: AgentEvent.FileChanges,
): TimelineDetail {
    val files = event.snapshot.summary.files
    val summaryMd = buildString {
        append(if (event.undone) "**Undone** file changes" else "**File changes**")
        if (files.isEmpty()) {
            append("\n\n_No files recorded in this batch._")
        } else {
            val add = event.snapshot.summary.additions
            val del = event.snapshot.summary.deletions
            append(" · **").append(files.size).append("** file")
            if (files.size != 1) append('s')
            append(" · `+").append(add).append("` / `-").append(del).append("`\n\n")
            files.forEach { file ->
                append("- `").append(file.path).append("` · +")
                    .append(file.additions).append(" / -").append(file.deletions).append('\n')
            }
        }
        if (event.batchId.isNotBlank()) {
            append("\n**Batch:** `").append(event.batchId).append('`')
        }
        if (event.groupedBatchIds.size > 1) {
            append("\n**Grouped batches:** ")
            append(event.groupedBatchIds.joinToString { "`$it`" })
        }
        if (event.baselineTree.isNotBlank()) {
            append("\n**Baseline tree:** `").append(event.baselineTree).append('`')
        }
        if (!event.snapshot.diffsHydrated) {
            append("\n\n_Diffs not loaded into memory yet — open Review from the chat card to hydrate._")
        }
    }
    val pathsPayload = files.joinToString("\n") { it.path }.ifBlank { null }
    val diffResult = formatFileChangeDiffs(event.snapshot)
    return TimelineDetail(
        header = header,
        summary = summaryMd.trim(),
        payload = pathsPayload,
        result = diffResult,
        timing = timing,
    )
}

private fun formatFileChangeDiffs(snapshot: AgentThreadChangeSnapshot): String? {
    if (!snapshot.diffsHydrated || snapshot.diffs.isEmpty()) return null
    return snapshot.diffs.entries.joinToString("\n\n") { (path, diff) ->
        buildString {
            append("### `").append(path).append("`")
            when {
                diff.isBinary -> append("\n\n_Binary file_")
                diff.lines.isEmpty() -> append("\n\n_No diff lines_")
                else -> {
                    append("\n\n```diff\n")
                    val limit = 240
                    diff.lines.take(limit).forEach { line ->
                        when (line.kind) {
                            DiffLineKind.Addition -> append('+')
                            DiffLineKind.Deletion -> append('-')
                            DiffLineKind.Context -> append(' ')
                        }
                        append(line.text)
                        append('\n')
                    }
                    if (diff.lines.size > limit) {
                        append("… truncated (").append(diff.lines.size - limit).append(" more lines)\n")
                    }
                    append("```")
                }
            }
        }
    }
}

/**
 * Project a row onto the 0..1 lane axis used by the brush and bar canvas.
 *
 * [durationMode] is independent of [axis]:
 * - off → equal-width step cells that together fill the lane
 * - on → cells sized by each step's duration **relative to the others**, packed
 *   end-to-end with no idle gaps (still fills 0..1). A blank stretch in one lane
 *   while another lane has a bar means that other lane owned that step — not dead time.
 */
fun TimelineRow.projectedInterval(
    model: TimelineModel,
    axis: TimelineAxis,
    durationMode: Boolean = false,
): Pair<Float, Float> {
    @Suppress("UNUSED_PARAMETER")
    val unusedAxis = axis
    return if (durationMode) {
        model.durationPackedInterval(stepIndex)
    } else {
        val total = model.rows.size.coerceAtLeast(1)
        val index = stepIndex.coerceIn(0, total - 1)
        val start = index.toFloat() / total
        val end = (index + 1).toFloat() / total
        start to end
    }
}

/**
 * Pack steps by relative display duration so Duration mode fills the axis without
 * wall-clock idle holes between events.
 */
fun TimelineModel.durationPackedInterval(index: Int): Pair<Float, Float> {
    if (rows.isEmpty()) return 0f to 0f
    val i = index.coerceIn(0, rows.lastIndex)
    val weights = LongArray(rows.size) { idx ->
        (rows[idx].displayEndMillis - rows[idx].displayStartMillis).coerceAtLeast(1L)
    }
    val total = weights.sum().coerceAtLeast(1L).toFloat()
    var acc = 0L
    for (j in 0 until i) acc += weights[j]
    val start = acc / total
    val end = (acc + weights[i]) / total
    return start to end
}

/**
 * Expand zero-duration rows so Duration mode paints contiguous cells proportional to time until
 * the next step. Measured [startMillis, endMillis] intervals (tool calls) are preserved.
 */
internal fun withDisplayIntervals(rows: List<TimelineRow>): List<TimelineRow> {
    if (rows.isEmpty()) return emptyList()
    val n = rows.size
    val displayStart = LongArray(n) { rows[it].startMillis }
    val displayEnd = LongArray(n) { rows[it].endMillis }

    var i = 0
    while (i < n) {
        val t = rows[i].startMillis
        var j = i + 1
        while (j < n && rows[j].startMillis == t) j++
        val nextStart = rows.getOrNull(j)?.startMillis
        val run = i until j
        val measured = run.filter { rows[it].endMillis > rows[it].startMillis }
        val points = run.filter { rows[it].endMillis <= rows[it].startMillis }

        for (idx in measured) {
            displayStart[idx] = rows[idx].startMillis
            displayEnd[idx] = rows[idx].endMillis
        }
        if (points.isNotEmpty()) {
            if (nextStart != null && nextStart > t) {
                val slice = (nextStart - t).toDouble() / points.size
                points.forEachIndexed { k, idx ->
                    val start = t + (slice * k).toLong()
                    val end = t + (slice * (k + 1)).toLong()
                    displayStart[idx] = start
                    displayEnd[idx] = end.coerceAtLeast(start + 1)
                }
            } else {
                // No later event — give each point a 1ms sequential slice so it still paints.
                points.forEachIndexed { k, idx ->
                    displayStart[idx] = t + k
                    displayEnd[idx] = t + k + 1
                }
            }
        }
        i = j
    }

    return rows.mapIndexed { idx, row ->
        row.copy(
            displayStartMillis = displayStart[idx],
            displayEndMillis = displayEnd[idx].coerceAtLeast(displayStart[idx] + 1),
            stepIndex = idx,
        )
    }
}

private data class TurnAcc(
    val rowKeys: MutableList<String> = mutableListOf(),
    var stepCount: Int = 0,
    var toolCallCount: Int = 0,
    var inputTokens: Long? = null,
    var outputTokens: Long? = null,
)

private fun intervalsOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Boolean {
    val left = maxOf(a0, b0)
    val right = minOf(a1, b1)
    // Point intervals (zero-width bars) still match when inside the brush.
    return left < right || (left == right && left in b0..b1 && a0 == a1)
}

private fun timelineLiteralMatches(haystack: String, needle: String): Boolean {
    if (needle.isEmpty() || haystack.isEmpty()) return false
    return haystack.indexOf(needle, ignoreCase = true) >= 0
}

private fun String.singleLine(max: Int): String {
    val flat = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
