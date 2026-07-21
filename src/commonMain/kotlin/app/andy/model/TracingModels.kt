package app.andy.model

import kotlinx.serialization.Serializable

enum class TracePhase { Idle, Starting, Recording, Stopping, Pulling, Done, Error }

data class TraceRecordingStatus(
    val phase: TracePhase = TracePhase.Idle,
    val serial: String? = null,
    val traceName: String? = null,
    val startedAtMillis: Long? = null,
    val durationMs: Long? = null,
    val message: String? = null,
    val lastTraceId: String? = null,
)

@Serializable
data class TraceRecording(
    val id: String,
    val name: String,
    val serial: String,
    val deviceLabel: String? = null,
    val presetId: String? = null,
    val recordedAtMillis: Long,
    val durationMs: Long? = null,
    val sizeBytes: Long,
    val localPath: String,
)

data class TraceUserConfig(
    val id: String,
    val name: String,
    val path: String,
)
