package app.andy.ui.logcat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry

internal class LogcatState {
    var entries by mutableStateOf<List<LogcatEntry>>(emptyList())
    var search by mutableStateOf("")
    var live by mutableStateOf(true)
    val levels = mutableStateMapOf<LogLevel, Boolean>().also { map -> LogLevel.entries.forEach { map[it] = it != LogLevel.Verbose && it != LogLevel.Silent } }
    var lastSerial by mutableStateOf<String?>(null)
    var lastSearch by mutableStateOf<String?>(null)
    var lastLevels by mutableStateOf<Set<LogLevel>?>(null)
    var lastLive by mutableStateOf(true)
    var lastPackage by mutableStateOf<String?>(null)
}
