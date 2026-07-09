package app.andy.desktop.service

import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val BugJsonFormat = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
internal data class BugReportDto(
    val id: String,
    val title: String = "Untitled bug",
    val notes: String = "",
    val deviceSerial: String = "unknown-device",
    val deviceModel: String? = null,
    val apiLevel: String? = null,
    val abi: String? = null,
    val resolution: String? = null,
    val capturedAtMillis: Long = 0L,
    val windowStartedAtMillis: Long = 0L,
    val windowEndedAtMillis: Long = 0L,
    val videoStartedAtMillis: Long? = null,
    val videoEndedAtMillis: Long? = null,
    val videoFrameRate: Double? = null,
    val videoFrameTimestampsMillis: List<Long> = emptyList(),
    val actions: List<BugActionDto> = emptyList(),
    val artifacts: List<BugArtifactDto> = emptyList(),
) {
    fun toModel(): BugReport = BugReport(
        id = id,
        title = title,
        notes = notes,
        deviceSerial = deviceSerial,
        deviceModel = deviceModel,
        apiLevel = apiLevel,
        abi = abi,
        resolution = resolution,
        capturedAtMillis = capturedAtMillis,
        windowStartedAtMillis = windowStartedAtMillis,
        windowEndedAtMillis = windowEndedAtMillis,
        actions = actions.map { it.toModel() },
        artifacts = artifacts.map { it.toModel() },
        videoStartedAtMillis = videoStartedAtMillis,
        videoEndedAtMillis = videoEndedAtMillis,
        videoFrameRate = videoFrameRate,
        videoFrameTimestampsMillis = videoFrameTimestampsMillis,
    )

    companion object {
        fun fromModel(report: BugReport): BugReportDto = BugReportDto(
            id = report.id,
            title = report.title,
            notes = report.notes,
            deviceSerial = report.deviceSerial,
            deviceModel = report.deviceModel,
            apiLevel = report.apiLevel,
            abi = report.abi,
            resolution = report.resolution,
            capturedAtMillis = report.capturedAtMillis,
            windowStartedAtMillis = report.windowStartedAtMillis,
            windowEndedAtMillis = report.windowEndedAtMillis,
            videoStartedAtMillis = report.videoStartedAtMillis,
            videoEndedAtMillis = report.videoEndedAtMillis,
            videoFrameRate = report.videoFrameRate,
            videoFrameTimestampsMillis = report.videoFrameTimestampsMillis,
            actions = report.actions.map { BugActionDto.fromModel(it) },
            artifacts = report.artifacts.map { BugArtifactDto.fromModel(it) },
        )
    }
}

@Serializable
internal data class BugActionDto(
    val id: String,
    val timestampMillis: Long,
    val kind: String = "action",
    val label: String = "Action",
    val detail: String? = null,
) {
    fun toModel(): BugAction = BugAction(
        id = id,
        timestampMillis = timestampMillis,
        kind = kind,
        label = label,
        detail = detail,
    )

    companion object {
        fun fromModel(action: BugAction): BugActionDto = BugActionDto(
            id = action.id,
            timestampMillis = action.timestampMillis,
            kind = action.kind,
            label = action.label,
            detail = action.detail,
        )
    }
}

@Serializable
internal data class BugArtifactDto(
    val name: String,
    val relativePath: String,
    val kind: String = "file",
    val sizeBytes: Long? = null,
) {
    fun toModel(): BugArtifact = BugArtifact(
        name = name,
        relativePath = relativePath,
        kind = kind,
        sizeBytes = sizeBytes,
    )

    companion object {
        fun fromModel(artifact: BugArtifact): BugArtifactDto = BugArtifactDto(
            name = artifact.name,
            relativePath = artifact.relativePath,
            kind = artifact.kind,
            sizeBytes = artifact.sizeBytes,
        )
    }
}

@Serializable
internal data class BugActionsFileDto(
    val actions: List<BugActionDto> = emptyList(),
)

internal object BugJson {
    fun writeActions(actions: List<BugAction>): String {
        val dto = BugActionsFileDto(actions = actions.map { BugActionDto.fromModel(it) })
        return BugJsonFormat.encodeToString(dto) + "\n"
    }

    fun writeReport(report: BugReport): String {
        return BugJsonFormat.encodeToString(BugReportDto.fromModel(report)) + "\n"
    }

    fun readReport(json: String): BugReport {
        return BugJsonFormat.decodeFromString(BugReportDto.serializer(), json).toModel()
    }
}
