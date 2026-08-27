package app.andy.model

import kotlinx.serialization.Serializable

@Serializable
enum class LogLevel { Verbose, Debug, Info, Warn, Error, Fatal, Silent }

data class LogcatEntry(
    val time: String,
    val pid: String?,
    val tid: String?,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** A contiguous run of [LogcatEntry] rows reassembled into one stack trace/ANR dump. */
data class StackTraceBlock(
    val startIndex: Int,
    val endIndex: Int,
    val header: String,
    val frames: List<String>,
)
