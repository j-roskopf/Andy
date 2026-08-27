package app.andy.desktop.service

import app.andy.model.AppIdentity
import app.andy.model.DeviceIdentity
import app.andy.model.HostIdentity
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationPayloadRef
import app.andy.model.InvestigationTimeline
import app.andy.model.InvestigationTimelineSchemaVersion
import app.andy.model.ProjectIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val InvestigationJsonFormat = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

@Serializable
internal data class InvestigationTimelineDto(
    val schemaVersion: Int = InvestigationTimelineSchemaVersion,
    val originMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val events: List<InvestigationEventDto> = emptyList(),
) {
    fun toModel(): InvestigationTimeline = InvestigationTimeline(
        schemaVersion = schemaVersion,
        originMillis = originMillis,
        endedAtMillis = endedAtMillis,
        events = events.map { it.toModel() },
    )

    companion object {
        fun fromModel(timeline: InvestigationTimeline): InvestigationTimelineDto =
            InvestigationTimelineDto(
                schemaVersion = timeline.schemaVersion,
                originMillis = timeline.originMillis,
                endedAtMillis = timeline.endedAtMillis,
                events = timeline.events.map { InvestigationEventDto.fromModel(it) },
            )
    }
}

@Serializable
internal data class InvestigationEventDto(
    val id: String,
    val atMillis: Long,
    val sourceTimestampMillis: Long? = null,
    val kind: String = InvestigationEventKind.Action.name,
    val summary: String = "",
    val severity: String = InvestigationEventSeverity.Info.name,
    val detail: String? = null,
    val correlationIds: Map<String, String> = emptyMap(),
    val inline: InvestigationInlinePayloadDto? = null,
    val payloadRef: InvestigationPayloadRefDto? = null,
) {
    fun toModel(): InvestigationEvent = InvestigationEvent(
        id = id,
        atMillis = atMillis,
        sourceTimestampMillis = sourceTimestampMillis,
        kind = runCatching { InvestigationEventKind.valueOf(kind) }
            .getOrDefault(InvestigationEventKind.Action),
        summary = summary,
        severity = runCatching { InvestigationEventSeverity.valueOf(severity) }
            .getOrDefault(InvestigationEventSeverity.Info),
        detail = detail,
        correlationIds = correlationIds,
        inline = inline?.toModel(),
        payloadRef = payloadRef?.toModel(),
    )

    companion object {
        fun fromModel(event: InvestigationEvent): InvestigationEventDto = InvestigationEventDto(
            id = event.id,
            atMillis = event.atMillis,
            sourceTimestampMillis = event.sourceTimestampMillis,
            kind = event.kind.name,
            summary = event.summary,
            severity = event.severity.name,
            detail = event.detail,
            correlationIds = event.correlationIds,
            inline = event.inline?.let { InvestigationInlinePayloadDto.fromModel(it) },
            payloadRef = event.payloadRef?.let { InvestigationPayloadRefDto.fromModel(it) },
        )
    }
}

@Serializable
internal data class InvestigationInlinePayloadDto(
    val text: String? = null,
    val packageName: String? = null,
    val activity: String? = null,
    val level: String? = null,
    val tag: String? = null,
    val deviceTime: String? = null,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val durationMillis: Long? = null,
    val error: String? = null,
    val tlsStatus: String? = null,
    val matchedRuleId: String? = null,
    val flowId: String? = null,
    val cpuPercent: Float? = null,
    val memoryMb: Float? = null,
    val fps: Float? = null,
    val batteryPercent: Int? = null,
    val thermalStatus: String? = null,
    val networkRxKbps: Float? = null,
    val networkTxKbps: Float? = null,
    val crashKind: String? = null,
    val hierarchySource: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val nodeCount: Int? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val approximatelyTimed: Boolean = false,
    val proxySessionScoped: Boolean = false,
) {
    fun toModel(): InvestigationInlinePayload = InvestigationInlinePayload(
        text = text,
        packageName = packageName,
        activity = activity,
        level = level,
        tag = tag,
        deviceTime = deviceTime,
        method = method,
        url = url,
        statusCode = statusCode,
        durationMillis = durationMillis,
        error = error,
        tlsStatus = tlsStatus,
        matchedRuleId = matchedRuleId,
        flowId = flowId,
        cpuPercent = cpuPercent,
        memoryMb = memoryMb,
        fps = fps,
        batteryPercent = batteryPercent,
        thermalStatus = thermalStatus,
        networkRxKbps = networkRxKbps,
        networkTxKbps = networkTxKbps,
        crashKind = crashKind,
        hierarchySource = hierarchySource,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        nodeCount = nodeCount,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        approximatelyTimed = approximatelyTimed,
        proxySessionScoped = proxySessionScoped,
    )

    companion object {
        fun fromModel(payload: InvestigationInlinePayload): InvestigationInlinePayloadDto =
            InvestigationInlinePayloadDto(
                text = payload.text,
                packageName = payload.packageName,
                activity = payload.activity,
                level = payload.level,
                tag = payload.tag,
                deviceTime = payload.deviceTime,
                method = payload.method,
                url = payload.url,
                statusCode = payload.statusCode,
                durationMillis = payload.durationMillis,
                error = payload.error,
                tlsStatus = payload.tlsStatus,
                matchedRuleId = payload.matchedRuleId,
                flowId = payload.flowId,
                cpuPercent = payload.cpuPercent,
                memoryMb = payload.memoryMb,
                fps = payload.fps,
                batteryPercent = payload.batteryPercent,
                thermalStatus = payload.thermalStatus,
                networkRxKbps = payload.networkRxKbps,
                networkTxKbps = payload.networkTxKbps,
                crashKind = payload.crashKind,
                hierarchySource = payload.hierarchySource,
                displayWidth = payload.displayWidth,
                displayHeight = payload.displayHeight,
                nodeCount = payload.nodeCount,
                imageWidth = payload.imageWidth,
                imageHeight = payload.imageHeight,
                approximatelyTimed = payload.approximatelyTimed,
                proxySessionScoped = payload.proxySessionScoped,
            )
    }
}

@Serializable
internal data class InvestigationPayloadRefDto(
    val relativePath: String,
    val kind: String = "file",
    val sizeBytes: Long? = null,
) {
    fun toModel(): InvestigationPayloadRef = InvestigationPayloadRef(
        relativePath = relativePath,
        kind = kind,
        sizeBytes = sizeBytes,
    )

    companion object {
        fun fromModel(ref: InvestigationPayloadRef): InvestigationPayloadRefDto =
            InvestigationPayloadRefDto(
                relativePath = ref.relativePath,
                kind = ref.kind,
                sizeBytes = ref.sizeBytes,
            )
    }
}

@Serializable
internal data class AppIdentityDto(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val debuggable: Boolean? = null,
    val label: String? = null,
) {
    fun toModel(): AppIdentity = AppIdentity(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        minSdk = minSdk,
        targetSdk = targetSdk,
        debuggable = debuggable,
        label = label,
    )

    companion object {
        fun fromModel(identity: AppIdentity): AppIdentityDto = AppIdentityDto(
            packageName = identity.packageName,
            versionName = identity.versionName,
            versionCode = identity.versionCode,
            minSdk = identity.minSdk,
            targetSdk = identity.targetSdk,
            debuggable = identity.debuggable,
            label = identity.label,
        )
    }
}

@Serializable
internal data class ProjectIdentityDto(
    val projectId: String? = null,
    val contextDir: String? = null,
    val gitHead: String? = null,
    val gitBranch: String? = null,
    val gitDirty: Boolean? = null,
) {
    fun toModel(): ProjectIdentity = ProjectIdentity(
        projectId = projectId,
        contextDir = contextDir,
        gitHead = gitHead,
        gitBranch = gitBranch,
        gitDirty = gitDirty,
    )

    companion object {
        fun fromModel(identity: ProjectIdentity): ProjectIdentityDto = ProjectIdentityDto(
            projectId = identity.projectId,
            contextDir = identity.contextDir,
            gitHead = identity.gitHead,
            gitBranch = identity.gitBranch,
            gitDirty = identity.gitDirty,
        )
    }
}

@Serializable
internal data class HostIdentityDto(
    val andyVersionName: String,
    val andyVersionCode: Int = 0,
    val hostOs: String,
) {
    fun toModel(): HostIdentity = HostIdentity(
        andyVersionName = andyVersionName,
        andyVersionCode = andyVersionCode,
        hostOs = hostOs,
    )

    companion object {
        fun fromModel(identity: HostIdentity): HostIdentityDto = HostIdentityDto(
            andyVersionName = identity.andyVersionName,
            andyVersionCode = identity.andyVersionCode,
            hostOs = identity.hostOs,
        )
    }
}

@Serializable
internal data class DeviceIdentityDto(
    val serial: String,
    val model: String? = null,
    val apiLevel: String? = null,
    val abi: String? = null,
    val resolution: String? = null,
) {
    fun toModel(): DeviceIdentity = DeviceIdentity(
        serial = serial,
        model = model,
        apiLevel = apiLevel,
        abi = abi,
        resolution = resolution,
    )

    companion object {
        fun fromModel(identity: DeviceIdentity): DeviceIdentityDto = DeviceIdentityDto(
            serial = identity.serial,
            model = identity.model,
            apiLevel = identity.apiLevel,
            abi = identity.abi,
            resolution = identity.resolution,
        )
    }
}

/** Network exchange sidecar stored under `events/network/`. */
@Serializable
internal data class NetworkEventSidecarDto(
    val exchangeId: String,
    val method: String = "GET",
    val url: String = "",
    val statusCode: Int? = null,
    val durationMillis: Long? = null,
    val error: String? = null,
    val tlsStatus: String? = null,
    val matchedRuleId: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBodyPreview: String? = null,
    val responseBodyPreview: String? = null,
    val proxySessionScoped: Boolean = true,
)

/** Crash/ANR sidecar stored under `events/crashes/`. */
@Serializable
internal data class CrashEventSidecarDto(
    val crashId: String,
    val kind: String,
    val packageName: String? = null,
    val processName: String? = null,
    val summary: String = "",
    val stackTrace: String = "",
)

/** Hierarchy sidecar metadata (nodes may be truncated). */
@Serializable
internal data class HierarchyEventSidecarDto(
    val source: String,
    val packageName: String? = null,
    val activity: String? = null,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val nodeCount: Int = 0,
    val truncated: Boolean = false,
    val treeJson: String = "",
)

internal object InvestigationJson {
    const val TimelineRelativePath = "timeline.json"
    const val EventsNetworkDir = "events/network"
    const val EventsCrashesDir = "events/crashes"
    const val EventsHierarchyDir = "events/hierarchy"
    const val EventsScreenshotsDir = "events/screenshots"

    fun writeTimeline(timeline: InvestigationTimeline): String =
        InvestigationJsonFormat.encodeToString(InvestigationTimelineDto.fromModel(timeline)) + "\n"

    fun readTimeline(json: String): InvestigationTimeline =
        InvestigationJsonFormat.decodeFromString(InvestigationTimelineDto.serializer(), json).toModel()

    fun writeNetworkSidecar(sidecar: NetworkEventSidecarDto): String =
        InvestigationJsonFormat.encodeToString(sidecar) + "\n"

    fun readNetworkSidecar(json: String): NetworkEventSidecarDto =
        InvestigationJsonFormat.decodeFromString(NetworkEventSidecarDto.serializer(), json)

    fun writeCrashSidecar(sidecar: CrashEventSidecarDto): String =
        InvestigationJsonFormat.encodeToString(sidecar) + "\n"

    fun readCrashSidecar(json: String): CrashEventSidecarDto =
        InvestigationJsonFormat.decodeFromString(CrashEventSidecarDto.serializer(), json)

    fun writeHierarchySidecar(sidecar: HierarchyEventSidecarDto): String =
        InvestigationJsonFormat.encodeToString(sidecar) + "\n"

    fun readHierarchySidecar(json: String): HierarchyEventSidecarDto =
        InvestigationJsonFormat.decodeFromString(HierarchyEventSidecarDto.serializer(), json)
}
