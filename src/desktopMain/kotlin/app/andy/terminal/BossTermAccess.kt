package app.andy.terminal

import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.RequestOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reflection bridge into BossTerm APIs that are `internal` to the compose-ui module
 * (`initializeSession`, `session`) but required for Andy's out-of-composition lifecycle
 * (eager PTY spawn, buffer scraping for status detection).
 *
 * Kotlin mangles those internals as `*$compose_ui` on the JVM.
 */
internal object BossTermAccess {
    private val initializeMethod by lazy {
        EmbeddableTerminalState::class.java.declaredMethods.first { method ->
            method.name.startsWith("initializeSession") &&
                method.parameterTypes.firstOrNull() == TerminalSettings::class.java
        }.also { it.isAccessible = true }
    }

    private val sessionGetter by lazy {
        EmbeddableTerminalState::class.java.declaredMethods.first { method ->
            method.name.startsWith("getSession") && method.parameterCount == 0
        }.also { it.isAccessible = true }
    }

    fun initialize(
        state: EmbeddableTerminalState,
        settings: TerminalSettings,
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>?,
        onOutput: ((String) -> Unit)?,
        onExit: ((Int) -> Unit)?,
        platformServices: PlatformServices,
    ) {
        initializeMethod.invoke(
            state,
            settings,
            command,
            workingDirectory,
            environment,
            /* initialCommand */ null,
            /* onInitialCommandComplete */ null,
            onOutput,
            onExit,
            platformServices,
        )
    }

    fun tab(state: EmbeddableTerminalState): TerminalTab? =
        runCatching { sessionGetter.invoke(state) as? TerminalTab }.getOrNull()

    fun textBuffer(state: EmbeddableTerminalState) = tab(state)?.textBuffer

    /** Redraw gate owner for [TerminalFrameLimiter]. Public BossTerm API, no mangling. */
    fun display(state: EmbeddableTerminalState) = tab(state)?.display

    fun isUsingAlternateBuffer(state: EmbeddableTerminalState): Boolean =
        textBuffer(state)?.isUsingAlternateBuffer == true

    fun isMouseReporting(state: EmbeddableTerminalState): Boolean =
        tab(state)?.display?.isMouseReporting() == true

    fun screenText(state: EmbeddableTerminalState): String =
        runCatching {
            tab(state)?.textBuffer?.getScreenLines()?.trimEnd().orEmpty()
        }.getOrDefault("")

    fun writeBytes(state: EmbeddableTerminalState, bytes: ByteArray) {
        state.sendInput(bytes)
    }

    fun resize(state: EmbeddableTerminalState, cols: Int, rows: Int, scope: CoroutineScope) {
        val tab = tab(state) ?: return
        runCatching {
            tab.terminal.resize(TermSize(cols, rows), RequestOrigin.User)
        }
        val handle = tab.processHandle.value ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { handle.resize(cols, rows) }
        }
    }

    fun isProcessAlive(state: EmbeddableTerminalState): Boolean {
        val handle = tab(state)?.processHandle?.value ?: return false
        return handle.isAlive()
    }

    fun pid(state: EmbeddableTerminalState): Long? =
        tab(state)?.processHandle?.value?.getPid()?.takeIf { it > 0 }

    fun windowTitleFlow(state: EmbeddableTerminalState) =
        tab(state)?.display?.windowTitleFlow
}
