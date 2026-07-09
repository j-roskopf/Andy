package app.andy.model

enum class LogLevel { Verbose, Debug, Info, Warn, Error, Fatal, Silent }

data class LogcatEntry(
    val time: String,
    val pid: String?,
    val tid: String?,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
