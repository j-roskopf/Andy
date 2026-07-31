package app.andy.terminal

import ai.rever.bossterm.terminal.model.TerminalHistoryBufferListener
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.TextBufferChangesListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the terminal model's committed scrollback rows.
 *
 * This deliberately listens to terminal-buffer mutations instead of polling rendered text.
 * A row enters this model exactly when BossTerm moves it out of the live screen, so repainting
 * the current screen cannot duplicate it. The live BossTerm buffer remains the interactive
 * source of truth; this controller is the cheap, session-owned index used by persistence and
 * recovery code.
 */
class TerminalHistoryController(
    private val buffer: TerminalTextBuffer,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) : AutoCloseable {
    private val lock = Any()
    private val rows = ArrayDeque<StyledTerminalRow>()
    private var observedTailVersion: Long? = null
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val historyListener = object : TerminalHistoryBufferListener {
        override fun historyBufferLineCountChanged() {
            captureCommittedRows()
        }
    }

    private val changesListener = object : TextBufferChangesListener {
        override fun linesDiscardedFromHistory(lines: List<TerminalLine>) {
            // The buffer may trim old rows before notifying the history-count listener. Keep
            // our own cap independent so consumers never observe a half-trimmed batch.
            captureCommittedRows()
        }

        override fun historyCleared() {
            synchronized(lock) {
                rows.clear()
                observedTailVersion = null
                _revision.value++
            }
        }
    }

    init {
        buffer.addHistoryBufferListener(historyListener)
        buffer.addChangesListener(changesListener)
        captureCommittedRows()
    }

    fun snapshot(): List<StyledTerminalRow> = synchronized(lock) { rows.toList() }

    /** Explicit resync seam for attach/recovery; normal live updates arrive via listeners. */
    fun refresh() = captureCommittedRows()

    private fun captureCommittedRows() {
        buffer.lock()
        try {
            val snapshot = buffer.createSnapshot()
            val history = snapshot.historyLines
            synchronized(lock) {
                val tailIndex = observedTailVersion?.let { version ->
                    history.indexOfLast { it.getSnapshotVersion() == version }
                } ?: -1
                if (observedTailVersion != null && tailIndex < 0) {
                    rows.clear()
                }
                val start = tailIndex + 1
                for (index in start until history.size) {
                    rows.addLast(styledRowFromTerminalLine(history[index]))
                }
                while (rows.size > maxRows) rows.removeFirst()
                observedTailVersion = history.lastOrNull()?.getSnapshotVersion()
                _revision.value++
            }
        } finally {
            buffer.unlock()
        }
    }

    override fun close() {
        buffer.removeHistoryBufferListener(historyListener)
        buffer.removeChangesListener(changesListener)
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 10_000
    }
}
