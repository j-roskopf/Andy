package app.andy.desktop.service

import app.andy.model.AccessibilityNode
import app.andy.model.AndroidAppDetails
import app.andy.model.AppIdentity
import app.andy.model.CrashKind
import app.andy.model.CrashRecord
import app.andy.model.HierarchySnapshot
import app.andy.model.HostIdentity
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationPayloadRef
import app.andy.model.NetworkExchange
import app.andy.model.PerformanceSample
import app.andy.model.ProjectIdentity
import app.andy.model.ProxyWarning
import app.andy.service.ActionConfigStore
import app.andy.service.AppService
import app.andy.service.WorkspaceStore
import app.andy.updates.AndyBuildInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

// Ring caps shared by DesktopBugService's investigation capture rings — kept alongside the
// builder functions below so cap sizes stay next to the code that enforces them.
internal const val InvestigationMaxNetworkEvents = 80
internal const val InvestigationMaxProxyWarnings = 40
internal const val InvestigationMaxMetricSamples = 60
internal const val InvestigationMaxCrashEvents = 20
internal const val InvestigationMaxHierarchyEvents = 8
internal const val InvestigationMaxScreenshots = 6
internal const val InvestigationMaxScreenshotBytes = 2L * 1024L * 1024L
internal const val InvestigationMaxLogLinesInTimeline = 200
/** Hard cap on the in-memory log ring even when follow-only streaming is unavailable. */
internal const val InvestigationMaxLogLinesInRing = 8_000
internal const val InvestigationHierarchyMaxNodes = 400
internal const val InvestigationHierarchyMaxDepth = 12
internal const val InvestigationMetricsRefreshIntervalMillis = 15_000L
internal const val InvestigationCrashPollIntervalMillis = 5_000L
private const val LogcatFatalPrefix = "logcat-fatal-"

private val HierarchyTreeJsonFormat = Json {
    encodeDefaults = false
    explicitNulls = false
}

// ---------------------------------------------------------------------------
// Network / proxy warnings
// ---------------------------------------------------------------------------

/** Builds a compact timeline event + full sidecar for a proxy-observed exchange. Never claims package ownership. */
internal fun networkEventAndSidecar(exchange: NetworkExchange): Pair<InvestigationEvent, NetworkEventSidecarDto> {
    val id = "network-${exchange.id}"
    val severity = when {
        exchange.error != null || (exchange.statusCode ?: 0) >= 500 -> InvestigationEventSeverity.Error
        (exchange.statusCode ?: 0) >= 400 -> InvestigationEventSeverity.Warning
        else -> InvestigationEventSeverity.Info
    }
    val event = InvestigationEvent(
        id = id,
        atMillis = exchange.startedAtMillis,
        kind = InvestigationEventKind.NetworkExchange,
        summary = "${exchange.method} ${shortenUrl(exchange.url)}",
        severity = severity,
        correlationIds = mapOf("flowId" to exchange.flowId, "exchangeId" to exchange.id),
        inline = InvestigationInlinePayload(
            method = exchange.method,
            url = exchange.url,
            statusCode = exchange.statusCode,
            durationMillis = exchange.durationMillis,
            error = exchange.error,
            tlsStatus = exchange.tlsStatus,
            matchedRuleId = exchange.matchedRuleId,
            flowId = exchange.flowId,
            proxySessionScoped = true,
        ),
        payloadRef = InvestigationPayloadRef(
            relativePath = "${InvestigationJson.EventsNetworkDir}/$id.json",
            kind = "network",
        ),
    )
    val sidecar = NetworkEventSidecarDto(
        exchangeId = exchange.id,
        method = exchange.method,
        url = exchange.url,
        statusCode = exchange.statusCode,
        durationMillis = exchange.durationMillis,
        error = exchange.error,
        tlsStatus = exchange.tlsStatus,
        matchedRuleId = exchange.matchedRuleId,
        requestHeaders = exchange.requestHeaders,
        responseHeaders = exchange.responseHeaders,
        requestBodyPreview = exchange.requestBodyPreview?.take(4_000),
        responseBodyPreview = exchange.responseBodyPreview?.take(4_000),
        proxySessionScoped = true,
    )
    return event to sidecar
}

private fun shortenUrl(url: String): String = url.take(160)

/** Proxy warnings never carry app/package correlation — they describe the proxy session itself. */
internal fun proxyWarningEvent(warning: ProxyWarning): InvestigationEvent = InvestigationEvent(
    id = "proxy-warning-${warning.id}",
    atMillis = warning.atMillis,
    kind = InvestigationEventKind.ProxyWarning,
    summary = warning.message.take(160),
    severity = InvestigationEventSeverity.Warning,
    detail = warning.message,
    correlationIds = buildMap {
        put("warningId", warning.id)
        put("kind", warning.kind.name)
        warning.sni?.let { put("sni", it) }
    },
    inline = InvestigationInlinePayload(text = warning.message, proxySessionScoped = true),
)

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

internal fun metricSampleEvent(sample: PerformanceSample): InvestigationEvent = InvestigationEvent(
    id = "metric-${sample.timestampMillis}",
    atMillis = sample.timestampMillis,
    kind = InvestigationEventKind.MetricSample,
    summary = metricSummary(sample),
    inline = InvestigationInlinePayload(
        cpuPercent = sample.cpuPercent,
        memoryMb = sample.memoryMb,
        fps = sample.fps,
        batteryPercent = sample.batteryPercent,
        thermalStatus = sample.thermalStatus,
        networkRxKbps = sample.networkRxKbps,
        networkTxKbps = sample.networkTxKbps,
    ),
)

private fun metricSummary(sample: PerformanceSample): String = buildList {
    sample.cpuPercent?.let { add("CPU ${roundToOneDecimal(it)}%") }
    sample.memoryMb?.let { add("${roundToOneDecimal(it)}MB") }
    sample.fps?.let { add("${roundToOneDecimal(it)}fps") }
}.joinToString(" · ").ifBlank { "Metric sample" }

private fun roundToOneDecimal(value: Float): String = "%.1f".format(value)

// ---------------------------------------------------------------------------
// Crashes — lightweight event now, sidecar (with full text) lazily written at save.
// ---------------------------------------------------------------------------

internal fun crashEvent(record: CrashRecord, hasSidecar: Boolean): InvestigationEvent {
    val id = "crash-${record.id}"
    return InvestigationEvent(
        id = id,
        atMillis = record.timestampMillis,
        kind = if (record.kind == CrashKind.Anr) InvestigationEventKind.Anr else InvestigationEventKind.Crash,
        summary = record.summary.take(160),
        severity = InvestigationEventSeverity.Error,
        detail = record.summary,
        correlationIds = buildMap {
            put("crashId", record.id)
            record.packageName?.let { put("packageName", it) }
        },
        inline = InvestigationInlinePayload(
            packageName = record.packageName,
            crashKind = record.kind.name,
        ),
        payloadRef = if (hasSidecar) {
            InvestigationPayloadRef(relativePath = "${InvestigationJson.EventsCrashesDir}/$id.json", kind = "crash")
        } else {
            null
        },
    )
}

internal fun crashSidecar(record: CrashRecord, fullText: String): CrashEventSidecarDto = CrashEventSidecarDto(
    crashId = record.id,
    kind = record.kind.name,
    packageName = record.packageName,
    summary = record.summary,
    stackTrace = fullText,
)

// ---------------------------------------------------------------------------
// Hierarchy — captured synchronously at investigation start, on screen transitions, and on crash.
// ---------------------------------------------------------------------------

@Serializable
internal data class HierarchyTreeNodeDto(
    val className: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val children: List<HierarchyTreeNodeDto> = emptyList(),
)

private fun AccessibilityNode.toHierarchyTreeNodeDto(): HierarchyTreeNodeDto = HierarchyTreeNodeDto(
    className = className,
    resourceId = resourceId,
    text = text,
    contentDescription = contentDescription,
    bounds = bounds,
    clickable = clickable,
    enabled = enabled,
    password = password,
    children = children.map { it.toHierarchyTreeNodeDto() },
)

internal fun encodeHierarchyTree(node: AccessibilityNode): String =
    HierarchyTreeJsonFormat.encodeToString(node.toHierarchyTreeNodeDto())

internal data class HierarchyTruncation(val node: AccessibilityNode, val nodeCount: Int, val truncated: Boolean)

/** Caps total node count and depth so a pathological tree cannot bloat the sidecar. */
internal fun truncateHierarchy(root: AccessibilityNode, maxNodes: Int, maxDepth: Int): HierarchyTruncation {
    var remaining = maxNodes
    var truncated = false

    fun walk(node: AccessibilityNode, depth: Int): AccessibilityNode? {
        if (remaining <= 0) {
            truncated = true
            return null
        }
        remaining--
        if (depth >= maxDepth) {
            if (node.children.isNotEmpty()) truncated = true
            return node.copy(children = emptyList())
        }
        val children = node.children.mapNotNull { walk(it, depth + 1) }
        if (children.size < node.children.size) truncated = true
        return node.copy(children = children)
    }

    val truncatedRoot = walk(root, 0) ?: root.copy(children = emptyList())
    val nodeCount = (maxNodes - remaining).coerceAtLeast(1)
    return HierarchyTruncation(truncatedRoot, nodeCount, truncated)
}

internal fun hierarchySuccessEventAndSidecar(
    snapshot: HierarchySnapshot,
    atMillis: Long,
    reason: String,
): Pair<InvestigationEvent, HierarchyEventSidecarDto> {
    val truncation = truncateHierarchy(snapshot.root, InvestigationHierarchyMaxNodes, InvestigationHierarchyMaxDepth)
    val packageName = snapshot.windows.firstOrNull { it.isVisible }?.packageName ?: snapshot.root.packageName
    val id = "hierarchy-$atMillis"
    val sidecar = HierarchyEventSidecarDto(
        source = snapshot.source.name,
        packageName = packageName,
        activity = null,
        displayWidth = snapshot.displayWidth,
        displayHeight = snapshot.displayHeight,
        nodeCount = truncation.nodeCount,
        truncated = truncation.truncated,
        treeJson = encodeHierarchyTree(truncation.node),
    )
    val event = InvestigationEvent(
        id = id,
        atMillis = atMillis,
        kind = InvestigationEventKind.HierarchySnapshot,
        summary = "Hierarchy captured ($reason)",
        detail = reason,
        correlationIds = mapOf("reason" to reason),
        inline = InvestigationInlinePayload(
            hierarchySource = snapshot.source.name,
            displayWidth = snapshot.displayWidth,
            displayHeight = snapshot.displayHeight,
            nodeCount = truncation.nodeCount,
            packageName = packageName,
        ),
        payloadRef = InvestigationPayloadRef(
            relativePath = "${InvestigationJson.EventsHierarchyDir}/$id.json",
            kind = "hierarchy",
        ),
    )
    return event to sidecar
}

/** On capture failure, still surface a timeline entry (no sidecar) so the gap is diagnosable. */
internal fun hierarchyErrorEvent(atMillis: Long, reason: String, error: String): InvestigationEvent = InvestigationEvent(
    id = "hierarchy-error-$atMillis-${reason.hashCode()}",
    atMillis = atMillis,
    kind = InvestigationEventKind.HierarchySnapshot,
    summary = "Hierarchy capture failed ($reason)",
    severity = InvestigationEventSeverity.Error,
    detail = error,
    correlationIds = mapOf("reason" to reason),
    inline = InvestigationInlinePayload(error = error),
)

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------

internal fun screenshotEvent(idSuffix: String, atMillis: Long, label: String, detail: String?, sizeBytes: Int): InvestigationEvent {
    val id = "screenshot-$idSuffix"
    return InvestigationEvent(
        id = id,
        atMillis = atMillis,
        kind = InvestigationEventKind.Screenshot,
        summary = label,
        detail = detail,
        inline = InvestigationInlinePayload(text = detail),
        payloadRef = InvestigationPayloadRef(
            relativePath = "${InvestigationJson.EventsScreenshotsDir}/$id.png",
            kind = "screenshot",
            sizeBytes = sizeBytes.toLong(),
        ),
    )
}

// ---------------------------------------------------------------------------
// Log lines — logcat.txt stays the source of truth; the timeline index keeps a capped view.
// ---------------------------------------------------------------------------

internal data class TimestampedLogLine(
    val timestampMillis: Long,
    val line: String,
)

/**
 * Prefer Error/Fatal (and AndroidRuntime) lines when capping the timeline index so a noisy
 * device buffer can't bury the crash the user just triggered.
 */
internal fun selectLogLinesForTimeline(
    logs: List<TimestampedLogLine>,
    maxLines: Int = InvestigationMaxLogLinesInTimeline,
): List<TimestampedLogLine> {
    if (logs.size <= maxLines) return logs
    val priority = logs.filter { severityForLogLine(it.line) == InvestigationEventSeverity.Error }
    if (priority.size >= maxLines) return priority.takeLast(maxLines)
    val remaining = maxLines - priority.size
    val prioritySet = priority.toSet()
    val filler = logs.filterNot { it in prioritySet }.takeLast(remaining)
    return (priority + filler).sortedBy { it.timestampMillis }
}

/**
 * Turn `AndroidRuntime: FATAL EXCEPTION` stacks in the captured log ring into Crash timeline
 * events. Dropbox often lags (or is empty) on production devices; logcat is the reliable signal.
 */
internal fun extractFatalExceptionsFromLogs(
    logs: List<TimestampedLogLine>,
): List<Pair<CrashRecord, CrashEventSidecarDto>> {
    val results = ArrayList<Pair<CrashRecord, CrashEventSidecarDto>>()
    var index = 0
    while (index < logs.size) {
        val line = logs[index].line
        if (!line.contains("FATAL EXCEPTION", ignoreCase = true) ||
            !line.contains("AndroidRuntime", ignoreCase = true)
        ) {
            index++
            continue
        }
        val atMillis = logs[index].timestampMillis
        val block = ArrayList<String>()
        var cursor = index
        val fatalPid = Regex("""\b(\d+)\s+\d+\s+E\s+AndroidRuntime:""").find(line)?.groupValues?.getOrNull(1)
        while (cursor < logs.size) {
            val candidate = logs[cursor].line
            val isRuntime = candidate.contains("AndroidRuntime", ignoreCase = true)
            val samePid = fatalPid == null || candidate.contains(" $fatalPid ")
            if (cursor > index && (!isRuntime || !samePid)) break
            // Keep walking the stack as long as AndroidRuntime keeps emitting for this PID.
            if (isRuntime) block += candidate.substringAfter("AndroidRuntime:", candidate).trim()
            cursor++
            // Bound runaway blocks (huge tombstone-like dumps).
            if (block.size >= 80) break
        }
        val processLine = block.firstOrNull { it.startsWith("Process:", ignoreCase = true) }
        val packageName = processLine
            ?.substringAfter("Process:", "")
            ?.substringBefore(",")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val exceptionLine = block.firstOrNull {
            it.contains("Exception") || it.contains("Error:") || it.startsWith("java.") || it.startsWith("kotlin.")
        }
        val summary = buildString {
            append(packageName ?: "app")
            append(": ")
            append(exceptionLine?.take(120) ?: "FATAL EXCEPTION")
        }
        val id = "$LogcatFatalPrefix${packageName ?: "unknown"}-$atMillis-$index"
        val record = CrashRecord(
            id = id,
            kind = CrashKind.JavaCrash,
            packageName = packageName,
            timestampMillis = atMillis,
            summary = summary,
        )
        val sidecar = CrashEventSidecarDto(
            crashId = id,
            kind = CrashKind.JavaCrash.name,
            packageName = packageName,
            summary = summary,
            stackTrace = block.joinToString("\n"),
        )
        results += record to sidecar
        index = cursor.coerceAtLeast(index + 1)
    }
    return results
}

internal fun logLineEvent(index: Int, timestampMillis: Long, line: String): InvestigationEvent = InvestigationEvent(
    id = "legacy-log-$index-$timestampMillis",
    atMillis = timestampMillis,
    kind = InvestigationEventKind.LogLine,
    summary = line.take(160),
    severity = severityForLogLine(line),
    detail = line,
    inline = InvestigationInlinePayload(text = line),
)

internal fun severityForLogLine(line: String): InvestigationEventSeverity {
    val upper = line.uppercase()
    return when {
        "FATAL EXCEPTION" in upper ||
            Regex("""\bE\s+ANDROIDRUNTIME\b""").containsMatchIn(upper) ||
            Regex("""\bF\s+\S+:""").containsMatchIn(upper) ||
            Regex("""\bE\s+\S+:""").containsMatchIn(upper) -> InvestigationEventSeverity.Error
        Regex("""\bW\s+\S+:""").containsMatchIn(upper) -> InvestigationEventSeverity.Warning
        else -> InvestigationEventSeverity.Info
    }
}

// ---------------------------------------------------------------------------
// Identity resolution — best-effort only; missing/failed identity must never fail a save.
// ---------------------------------------------------------------------------

internal suspend fun resolveAppIdentity(apps: AppService?, serial: String?): AppIdentity? {
    if (apps == null || serial == null) return null
    val packageName = runCatching { apps.focusedPackage(serial) }.getOrNull() ?: return null
    val details = runCatching { apps.getAppDetails(serial, packageName) }.getOrNull() ?: AndroidAppDetails()
    return AppIdentity(
        packageName = packageName,
        versionName = details.versionName,
        versionCode = details.versionCode,
        minSdk = details.minSdk,
        targetSdk = details.targetSdk,
        debuggable = details.debuggable,
    )
}

internal suspend fun resolveProjectIdentity(
    workspaceStore: WorkspaceStore?,
    actionConfig: ActionConfigStore?,
): ProjectIdentity? {
    if (workspaceStore == null) return null
    val projectId = runCatching { workspaceStore.load().lastActionProjectId }.getOrNull() ?: return null
    val project = runCatching { actionConfig?.load()?.projects?.firstOrNull { it.id == projectId } }.getOrNull()
        ?: return ProjectIdentity(projectId = projectId)
    val dir = File(project.contextDir)
    val head = runGitCommand(dir, "rev-parse", "HEAD")
    val branch = runGitCommand(dir, "rev-parse", "--abbrev-ref", "HEAD")
    val statusOutput = runGitCommand(dir, "status", "--porcelain")
    return ProjectIdentity(
        projectId = project.id,
        contextDir = project.contextDir,
        gitHead = head,
        gitBranch = branch,
        gitDirty = statusOutput?.isNotBlank(),
    )
}

internal fun hostIdentity(): HostIdentity = HostIdentity(
    andyVersionName = runCatching { AndyBuildInfo.versionName }.getOrDefault("unknown"),
    andyVersionCode = 0,
    hostOs = System.getProperty("os.name") ?: "unknown",
)

private fun runGitCommand(dir: File, vararg args: String): String? {
    if (!dir.isDirectory) return null
    return runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) null else output
    }.getOrNull()
}
