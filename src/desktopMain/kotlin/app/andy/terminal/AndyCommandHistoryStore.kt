package app.andy.terminal

import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Andy-owned persistent command-history store under `~/.andy/ketraterm/`.
 *
 * Apache-2.0-compatible design patterned on KetraTerm's unpublished
 * CommandHistoryStore: shell-integration metadata only, sensitive-command filters,
 * bounded TSV with Base64 text fields.
 */
class AndyCommandHistoryStore(
    private val path: Path = AndyKetraTermPaths.commandHistoryFile(),
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val lock = Any()
    private val entries = ArrayDeque<AndyCommandHistoryEntry>(capacity)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "andy-command-history").apply { isDaemon = true }
    }

    init {
        load()
    }

    fun record(
        profileId: String,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        val command = metadata.commandText ?: return
        if (isSensitive(command)) return
        val finishedAt = metadata.finishedAtEpochMillis ?: return
        val entry = AndyCommandHistoryEntry(
            profileId = profileId,
            command = command,
            workingDirectoryUri = metadata.workingDirectoryUri,
            exitCode = metadata.exitCode,
            startedAtEpochMillis = metadata.startedAtEpochMillis,
            finishedAtEpochMillis = finishedAt,
        )
        worker.execute {
            val snapshot = synchronized(lock) {
                while (entries.size >= capacity) entries.removeFirst()
                entries.addLast(entry)
                entries.toList()
            }
            persist(snapshot)
        }
    }

    fun flush() {
        worker.submit {}.get()
    }

    fun snapshot(): List<AndyCommandHistoryEntry> {
        flush()
        return synchronized(lock) { entries.toList() }
    }

    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(5L, TimeUnit.SECONDS)) {
            worker.shutdownNow()
        }
    }

    private fun load() {
        if (!Files.isRegularFile(path)) return
        runCatching {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (lines.firstOrNull() != HEADER) return
            synchronized(lock) {
                for (index in 1 until lines.size) {
                    decode(lines[index])?.let { entry ->
                        while (entries.size >= capacity) entries.removeFirst()
                        entries.addLast(entry)
                    }
                }
            }
        }
    }

    private fun persist(snapshot: List<AndyCommandHistoryEntry>) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine(HEADER)
                for (entry in snapshot) writer.appendLine(encode(entry))
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun encode(entry: AndyCommandHistoryEntry): String =
        listOf(
            entry.startedAtEpochMillis.toString(),
            entry.finishedAtEpochMillis.toString(),
            entry.exitCode?.toString().orEmpty(),
            encodeText(entry.profileId),
            encodeText(entry.workingDirectoryUri.orEmpty()),
            encodeText(entry.command),
        ).joinToString("\t")

    private fun decode(line: String): AndyCommandHistoryEntry? {
        val fields = line.split('\t')
        if (fields.size != FIELD_COUNT) return null
        return runCatching {
            AndyCommandHistoryEntry(
                profileId = decodeText(fields[3]),
                command = decodeText(fields[5]),
                workingDirectoryUri = decodeText(fields[4]).takeIf(String::isNotEmpty),
                exitCode = fields[2].takeIf(String::isNotEmpty)?.toInt(),
                startedAtEpochMillis = fields[0].toLong(),
                finishedAtEpochMillis = fields[1].toLong(),
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun isSensitive(command: String): Boolean {
        if (command.startsWith(" ") || command.startsWith("\t")) return true
        return SENSITIVE_KEYWORDS.any { command.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val HEADER = "Andy_COMMAND_HISTORY\t1"
        private const val FIELD_COUNT = 6
        private const val DEFAULT_CAPACITY = 10_000
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        private val SENSITIVE_KEYWORDS = listOf(
            "password", "passwd", "secret", "token", "bearer", "authorization",
            "credential", "credentials", "passphrase", "passcode", "jwt",
            "key=", "_key", "key_", "-key", "--key", "key ", "auth ", "auth=",
        )

        @Volatile
        private var shared: AndyCommandHistoryStore? = null

        fun shared(): AndyCommandHistoryStore {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: AndyCommandHistoryStore().also { shared = it }
            }
        }
    }
}

data class AndyCommandHistoryEntry(
    val profileId: String,
    val command: String,
    val workingDirectoryUri: String?,
    val exitCode: Int?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
)
