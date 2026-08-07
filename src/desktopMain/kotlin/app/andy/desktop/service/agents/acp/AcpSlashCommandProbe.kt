@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package app.andy.desktop.service.agents.acp

import app.andy.model.AgentKind
import app.andy.model.AgentSlashCommand
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/** Fetches a provider's advertised slash commands without starting an Andy task. */
internal class AcpSlashCommandProbe(
    private val launcher: AcpProcessLauncher = AcpProcessLauncher(),
    private val timeoutMs: Long = 20_000,
) {
    suspend fun probe(
        agent: AgentKind,
        cwd: File,
        binary: String?,
        env: Map<String, String>,
    ): List<AgentSlashCommand> = withContext(Dispatchers.IO) {
        val spec = AcpRegistry.spec(agent) ?: return@withContext emptyList()
        val canonicalCwd = cwd.canonicalFile
        if (!canonicalCwd.isDirectory) return@withContext emptyList()
        // Timeout must cover newSession/initialize — Claude ACP can hang there, not only
        // on the AvailableCommands wait that follows.
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                coroutineScope {
                    probeOnce(this, spec, agent, binary, canonicalCwd, env)
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    private suspend fun probeOnce(
        scope: CoroutineScope,
        spec: AcpLaunchSpec,
        agent: AgentKind,
        binary: String?,
        cwd: File,
        env: Map<String, String>,
    ): List<AgentSlashCommand> {
        launcher.preflight(spec, binary).getOrThrow()
        val launched = launcher.launch(spec, binary, cwd.path, env)
        val process = launched.process
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val commands = CompletableDeferred<List<AgentSlashCommand>>()
        try {
            val input = kotlinx.coroutines.flow.flow {
                launched.process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        emit(line)
                    }
                }
            }
            val output: suspend (String) -> Unit = { line ->
                synchronized(writer) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            }
            val transport = StdioTransport(
                scope,
                Dispatchers.IO,
                input,
                output,
                "andy-acp-probe-${agent.name}",
            )
            val rpc = Protocol(
                scope,
                transport,
                ProtocolOptions(protocolDebugName = "andy-acp-probe-${agent.name}"),
            )
            rpc.start()
            val client = Client(rpc)
            client.initialize(
                ClientInfo(
                    capabilities = ClientCapabilities(
                        fs = FileSystemCapability(readTextFile = true, writeTextFile = false),
                        terminal = false,
                    ),
                    implementation = Implementation("Andy", "desktop", "Andy ACP slash probe"),
                ),
            )
            val operationsFactory = ClientOperationsFactory { _, _ ->
                probeOperations(cwd, commands)
            }
            val session: ClientSession = client.newSession(
                SessionCreationParameters(cwd = cwd.path, mcpServers = emptyList()),
                operationsFactory,
            )
            // Keep the session referenced until commands arrive so the client cannot GC it.
            session.sessionId
            return commands.await()
        } finally {
            runCatching { writer.close() }
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }

    private fun probeOperations(
        cwd: File,
        commands: CompletableDeferred<List<AgentSlashCommand>>,
    ): ClientSessionOperations {
        val terminal = AcpTerminalOperations(cwd)
        return object : ClientSessionOperations,
            com.agentclientprotocol.common.FileSystemOperations by AcpFileSystemOperations(cwd),
            com.agentclientprotocol.common.TerminalOperations by terminal {
            override suspend fun requestPermissions(
                toolCall: SessionUpdate.ToolCallUpdate,
                permissions: List<com.agentclientprotocol.model.PermissionOption>,
                _meta: JsonElement?,
            ): com.agentclientprotocol.model.RequestPermissionResponse {
                val selected = permissions.firstOrNull()?.optionId
                    ?: return com.agentclientprotocol.model.RequestPermissionResponse(
                        com.agentclientprotocol.model.RequestPermissionOutcome.Cancelled,
                        _meta,
                    )
                return com.agentclientprotocol.model.RequestPermissionResponse(
                    com.agentclientprotocol.model.RequestPermissionOutcome.Selected(selected),
                    _meta,
                )
            }

            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
                if (notification !is SessionUpdate.AvailableCommandsUpdate || commands.isCompleted) return
                commands.complete(AcpEventMapper.mapSlashCommands(notification))
            }
        }
    }
}
