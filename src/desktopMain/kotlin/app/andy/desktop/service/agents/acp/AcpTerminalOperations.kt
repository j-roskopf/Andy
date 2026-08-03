package app.andy.desktop.service.agents.acp

import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.protocol.acpFail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Backs the ACP terminal RPCs (create/output/waitForExit/kill/release) with real child
 * processes. Andy is the client here, so it owns and streams the command's output back to the
 * agent (and, via [bufferedOutput], to the transcript renderer) rather than delegating
 * execution to the provider's own exec tool.
 */
class AcpTerminalOperations(
    private val cwd: File,
) : TerminalOperations {
    private class Handle(val process: Process, val limitBytes: Int) {
        val buffer = StringBuilder()
        var truncated = false
        val exitStatus = CompletableDeferred<TerminalExitStatus>()
    }

    private val terminals = ConcurrentHashMap<String, Handle>()

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        val workingDir = cwd?.let { path -> File(path).let { if (it.isAbsolute) it else File(this.cwd, path) } } ?: this.cwd
        val process = runCatching {
            ProcessBuilder(listOf(command) + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(env.associate { it.name to it.value }) }
                .start()
        }.getOrElse { acpFail("Failed to start terminal command: ${it.message}") }

        val terminalId = UUID.randomUUID().toString()
        val limit = (outputByteLimit?.toLong() ?: DEFAULT_OUTPUT_BYTE_LIMIT).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val handle = Handle(process, limit)
        terminals[terminalId] = handle
        Thread({ pump(handle) }, "andy-acp-terminal-$terminalId").apply { isDaemon = true; start() }
        return CreateTerminalResponse(terminalId, null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?): TerminalOutputResponse {
        val handle = terminals[terminalId] ?: acpFail("Unknown terminal: $terminalId")
        val (output, truncated) = synchronized(handle) { handle.buffer.toString() to handle.truncated }
        val exitStatus = if (handle.exitStatus.isCompleted) handle.exitStatus.getCompleted() else null
        return TerminalOutputResponse(output, truncated, exitStatus, null)
    }

    override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?): WaitForTerminalExitResponse {
        val handle = terminals[terminalId] ?: acpFail("Unknown terminal: $terminalId")
        val status = handle.exitStatus.await()
        return WaitForTerminalExitResponse(status.exitCode, status.signal, null)
    }

    override suspend fun terminalKill(terminalId: String, _meta: JsonElement?): KillTerminalCommandResponse {
        val handle = terminals[terminalId] ?: acpFail("Unknown terminal: $terminalId")
        if (handle.process.isAlive) handle.process.destroy()
        return KillTerminalCommandResponse(null)
    }

    override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?): ReleaseTerminalResponse {
        val handle = terminals.remove(terminalId) ?: acpFail("Unknown terminal: $terminalId")
        if (handle.process.isAlive) handle.process.destroyForcibly()
        return ReleaseTerminalResponse(null)
    }

    /** Best-effort output snapshot for transcript rendering; unlike [terminalOutput] this never fails on an unknown id. */
    fun bufferedOutput(terminalId: String): String? =
        terminals[terminalId]?.let { handle -> synchronized(handle) { handle.buffer.toString() } }

    /** Force-stops every terminal this session ever created; called when the ACP session tears down. */
    fun releaseAll() {
        terminals.values.forEach { handle -> if (handle.process.isAlive) handle.process.destroyForcibly() }
        terminals.clear()
    }

    private fun pump(handle: Handle) {
        val process = handle.process
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(handle) {
                        handle.buffer.append(chunk, 0, read)
                        val overflow = handle.buffer.length - handle.limitBytes
                        if (overflow > 0) {
                            handle.buffer.delete(0, overflow)
                            handle.truncated = true
                        }
                    }
                }
            }
        }
        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
        handle.exitStatus.complete(TerminalExitStatus(exitCode.coerceAtLeast(0).toUInt(), null, null))
    }

    private companion object {
        const val DEFAULT_OUTPUT_BYTE_LIMIT = 1_048_576L
    }
}
