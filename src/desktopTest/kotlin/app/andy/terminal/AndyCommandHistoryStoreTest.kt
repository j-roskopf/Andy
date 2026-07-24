package app.andy.terminal

import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class AndyCommandHistoryStoreTest {
    @Test
    fun recordsCompletedCommandsUnderAndyKetraTermPath() {
        val dir = Files.createTempDirectory("andy-cmd-history")
        val path = dir.resolve("command-history-v1.tsv")
        AndyCommandHistoryStore(path = path, capacity = 10).use { store ->
            store.record(
                profileId = "session-1",
                metadata = metadata(
                    commandText = "echo hello",
                    workingDirectoryUri = "file:///tmp",
                    exitCode = 0,
                    startedAtEpochMillis = 1_000L,
                    finishedAtEpochMillis = 1_100L,
                ),
            )
            store.flush()
            val snap = store.snapshot()
            assertEquals(1, snap.size)
            assertEquals("echo hello", snap.single().command)
            assertTrue(Files.isRegularFile(path))
            assertTrue(Files.readString(path).startsWith("Andy_COMMAND_HISTORY\t1"))
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun filtersSensitiveCommands() {
        val dir = Files.createTempDirectory("andy-cmd-history-sensitive")
        val path = dir.resolve("command-history-v1.tsv")
        AndyCommandHistoryStore(path = path, capacity = 10).use { store ->
            store.record(
                profileId = "session-1",
                metadata = metadata(
                    commandText = "export TOKEN=super-secret",
                    finishedAtEpochMillis = 2L,
                ),
            )
            store.record(
                profileId = "session-1",
                metadata = metadata(
                    commandText = " ls",
                    startedAtEpochMillis = 3L,
                    finishedAtEpochMillis = 4L,
                ),
            )
            store.record(
                profileId = "session-1",
                metadata = metadata(
                    commandText = "pwd",
                    startedAtEpochMillis = 5L,
                    finishedAtEpochMillis = 6L,
                ),
            )
            store.flush()
            val snap = store.snapshot()
            assertEquals(listOf("pwd"), snap.map { it.command })
        }
        dir.toFile().deleteRecursively()
    }

    private fun metadata(
        commandText: String,
        workingDirectoryUri: String? = null,
        exitCode: Int? = 0,
        startedAtEpochMillis: Long = 1L,
        finishedAtEpochMillis: Long? = 2L,
        recordId: Int = 1,
        lifecycle: Int = 0,
    ) = TerminalShellIntegrationCommandMetadata(
        recordId,
        lifecycle,
        commandText,
        workingDirectoryUri,
        exitCode,
        startedAtEpochMillis,
        finishedAtEpochMillis,
    )
}
