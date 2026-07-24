package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Regression: Cursor injects NODE_OPTIONS=--require …/js-debug/bootloader.js into Andy when
 * launched from an IDE terminal. Re-merging System.getenv() after scrub left that var in place
 * and Claude Code exited 1 in ~300ms with a blank terminal.
 */
class ClaudePtyLaunchEnvTest {
    @Test
    fun ketraTermScrubsNodeOptionsBootloaderSoClaudeStaysAlive() = runBlocking {
        val bin = File(System.getProperty("user.home"), ".local/share/claude/versions/2.1.212")
        if (!bin.isFile) {
            println("SKIP: claude binary not installed at ${bin.absolutePath}")
            return@runBlocking
        }
        val cwd = File(System.getProperty("user.home"), ".andy-tasks").also { it.mkdirs() }.absolutePath
        val env = HashMap(System.getenv()).apply {
            put(
                "NODE_OPTIONS",
                " --require /Applications/Cursor.app/Contents/Resources/app/extensions/ms-vscode.js-debug/src/bootloader.js  --inspect-publish-uid=http",
            )
            put("VSCODE_INSPECTOR_OPTIONS", ":::{\"autoAttachMode\":\"always\"}")
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = "claude-node-options-scrub",
                argv = listOf(
                    bin.absolutePath,
                    "--model", "sonnet",
                    "--permission-mode", "acceptEdits",
                    "reply with the single word ok",
                ),
                cwd = cwd,
                env = env,
            ),
        )
        try {
            val exit = withTimeoutOrNull(2_500) { session.exitCode.first { it != null } }
            val snap = session.bufferSnapshot()
            assertNull(exit, "Claude exited early ($exit); buffer=$snap")
            assertTrue(session.isAlive, "Claude PTY should still be alive after scrub")
            assertTrue(
                snap.contains("Claude", ignoreCase = true) || snap.isNotBlank(),
                "expected Claude TUI output, got buffer=$snap",
            )
        } finally {
            session.close()
        }
    }
}
