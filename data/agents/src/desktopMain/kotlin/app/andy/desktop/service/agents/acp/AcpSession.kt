@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package app.andy.desktop.service.agents.acp

import app.andy.desktop.service.agents.AgentStatusSnapshot
import app.andy.desktop.service.agents.AndyMcpEndpoint
import app.andy.desktop.service.agents.acpEndpointUrl
import app.andy.desktop.service.agents.AgentWorkflowArtifacts
import app.andy.desktop.service.agents.gooseProviderAndModel
import app.andy.desktop.service.agents.looksLikeProviderAuthFailure
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentSessionMode
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.runtimeKind
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Base64

/** One ACP JSON-RPC connection, kept alive across completed turns for resume/follow-up. */
class AcpSession(
    private val scope: CoroutineScope,
    private val task: AgentTask,
    private val launcher: AcpProcessLauncher,
    private val binary: String?,
    private val environment: Map<String, String>,
    private val mcp: AndyMcpEndpoint?,
    private val bridge: AcpPermissionBridge,
    private val artifacts: AgentWorkflowArtifacts,
    private val knownSkillNames: Set<String>,
    private val allowedSkillNames: Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onStatus: (AgentStatusSnapshot) -> Unit,
    private val onDiagnostics: (String) -> Unit,
) {
    private val promptMutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var transport: StdioTransport? = null
    private var protocol: Protocol? = null
    private var client: Client? = null
    private var session: ClientSession? = null

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    var lastStatus: AgentStatusSnapshot? = null
        private set

    @Volatile
    var lastStopReason: String? = null
        private set

    /** Most recent prompt failure detail (auth / transport), cleared on each prompt attempt. */
    @Volatile
    var lastPromptError: String? = null
        private set

    @Volatile
    private var closed = false

    /** Drop provider history echoed via [ClientSessionOperations.notify] until the next prompt. */
    @Volatile
    private var suppressHistoryReplay = false

    val alive: Boolean
        get() = !closed && process?.isAlive == true

    suspend fun open() {
        val cwd = File(task.cwd ?: error("ACP requires a task working directory")).canonicalFile
        val launched = launcher.launch(
            spec = AcpRegistry.specFor(task) ?: error("${task.runtimeKind().cliName} has no ACP launcher"),
            binary = binary,
            cwd = cwd.path,
            env = environment,
        )
        process = launched.process
        writer = BufferedWriter(OutputStreamWriter(launched.process.outputStream))
        onDiagnostics("ACP command: ${launched.command.joinToString(" ")}\n")

        try {
            openConnected(cwd, launched)
        } catch (error: Throwable) {
            val stderr = launched.stderrSnippet().takeIf { it.isNotBlank() }
            // Prefer stderr when it carries a clearer auth/login hint than the transport error.
            if (
                stderr != null &&
                looksLikeProviderAuthFailure(stderr) &&
                !looksLikeProviderAuthFailure(error.message)
            ) {
                val hint = stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: stderr
                throw IllegalStateException(hint, error)
            }
            throw error
        }
    }

    private suspend fun openConnected(cwd: File, launched: AcpProcess) {
        val input: Flow<String> = flow {
            launched.process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    emit(line)
                }
            }
        }
        val output: suspend (String) -> Unit = { line ->
            synchronized(this@AcpSession) {
                val outputWriter = writer ?: error("ACP stdio writer is closed")
                outputWriter.write(line)
                outputWriter.newLine()
                outputWriter.flush()
            }
        }
        val stdio = StdioTransport(scope, Dispatchers.IO, input, output, "andy-acp-${task.id}")
        val rpc = Protocol(scope, stdio, ProtocolOptions(protocolDebugName = "andy-acp-${task.id}"))
        transport = stdio
        protocol = rpc
        // Protocol.start() starts the underlying transport. Starting the stdio
        // transport separately makes ACP initialization fail with "Transport is
        // not in CREATED state".
        rpc.start()

        val acpClient = Client(rpc)
        client = acpClient
        val agentInfo = acpClient.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                    terminal = true,
                ),
                implementation = Implementation("Andy", "desktop", "Andy ACP client"),
            ),
        )
        acpClient.authenticateAdvertisedLogin(task.runtimeKind(), agentInfo)

        val parameters = SessionCreationParameters(
            cwd = cwd.path,
            // When Network Access is on, loopback MCP requires the shared token (Serve/proxy safe).
            mcpServers = mcp?.let { endpoint ->
                val headers = endpoint.bearerToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                    listOf(HttpHeader("Authorization", "Bearer $token"))
                }.orEmpty()
                listOf(McpServer.Http("andy", task.agent.acpEndpointUrl(endpoint), headers))
            }.orEmpty(),
        )
        val operationsFactory = ClientOperationsFactory { _, _ -> operations(cwd) }
        val storedId = task.acpSessionId?.takeIf { it.isNotBlank() }
        suppressHistoryReplay = storedId != null
        val opened = if (storedId == null) {
            acpClient.newSession(parameters, operationsFactory)
        } else {
            runCatching { acpClient.loadSession(SessionId(storedId), parameters, operationsFactory) }
                .recoverCatching { acpClient.resumeSession(SessionId(storedId), parameters, operationsFactory) }
                .getOrElse {
                    suppressHistoryReplay = false
                    acpClient.newSession(parameters, operationsFactory)
                }
        }
        session = opened
        sessionId = opened.sessionId.toString()
        closed = false
        applySelectedModel(opened)
        onEvent(AgentEvent.SessionStarted(System.currentTimeMillis(), sessionId, task.model))
        if (opened.modesSupported) {
            val modes = opened.availableModes.map { mode ->
                AgentSessionMode(mode.id.toString(), mode.name, mode.description)
            }
            onEvent(AgentEvent.AvailableModes(System.currentTimeMillis(), modes, opened.currentMode.value.toString()))
        }
    }

    /**
     * OpenCode ACP ignores `--model` (the subcommand has no such flag) and otherwise
     * falls through to Zen `big-pickle` unless config lists the selected model.
     * Goose ACP has no `--provider`/`--model` flags either; env sets the provider,
     * then this pin must use Goose's unprefixed model id (not Andy's `ollama/…` catalog).
     */
    private suspend fun applySelectedModel(opened: ClientSession) {
        val selected = acpSessionModelId(task) ?: return
        if (opened.configOptionsSupported) {
            val ok = runCatching {
                opened.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue(selected))
            }.onFailure { onDiagnostics("ACP set model failed: ${it.message}\n") }.isSuccess
            if (ok || !opened.modelsSupported) return
        }
        if (opened.modelsSupported) {
            runCatching { opened.setModel(ModelId(selected)) }
                .onFailure { onDiagnostics("ACP set model failed: ${it.message}\n") }
        }
    }

    /** Switches the live session's mode (e.g. plan vs. execute) for providers that support it. */
    suspend fun setMode(modeId: String): Boolean {
        val current = session ?: return false
        if (!alive) return false
        return runCatching {
            current.setMode(com.agentclientprotocol.model.SessionModeId(modeId))
            onEvent(AgentEvent.ModeChanged(System.currentTimeMillis(), modeId))
            true
        }.onFailure { onDiagnostics("ACP set mode failed: ${it.message}\n") }
            .getOrDefault(false)
    }

    suspend fun prompt(text: String, imagePaths: List<String>): Boolean = promptMutex.withLock {
        val current = session ?: return@withLock false
        if (!alive) return@withLock false
        lastPromptError = null
        suppressHistoryReplay = false
        onStatus(AcpStatusModel.working())
        val blocks = buildList {
            add(ContentBlock.Text(text))
            imagePaths.forEach { path ->
                val image = File(path)
                if (image.isFile) {
                    val mime = when (image.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/png"
                    }
                    add(ContentBlock.Image(Base64.getEncoder().encodeToString(image.readBytes()), mime, null))
                }
            }
        }
        return@withLock runCatching {
            var stopReason: String? = null
            current.prompt(blocks).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> mapEvent(event.update)?.let(onEvent)
                    is Event.PromptResponseEvent -> {
                        stopReason = event.response.stopReason.name.lowercase()
                        lastStopReason = stopReason
                        // Do not publish Done here. The run service decides after it inspects
                        // the transcript for a real RetriableError stall — otherwise a mention
                        // or a dropped stream both ding as a finished turn before retry.
                    }
                }
            }
            true
        }.onFailure { error ->
            lastPromptError = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
            onDiagnostics("ACP prompt failed: ${lastPromptError}\n")
            onStatus(AcpStatusModel.error())
        }.getOrDefault(false)
    }

    fun cancelTurn() {
        scope.launch {
            runCatching { session?.cancel() }
                .onFailure { onDiagnostics("ACP cancel failed: ${it.message}\n") }
        }
    }

    fun stop() {
        bridge.cancelAll()
        terminalOps?.releaseAll()
        scope.launch(Dispatchers.IO) {
            runCatching { session?.close() }
            transport?.close()
            writer?.let { runCatching { it.close() } }
            process?.let { child ->
                if (child.isAlive) {
                    child.destroy()
                    if (!child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly()
                }
            }
            closed = true
        }
    }

    @Volatile
    private var terminalOps: AcpTerminalOperations? = null

    private fun operations(cwd: File): ClientSessionOperations {
        val terminal = AcpTerminalOperations(cwd)
        terminalOps = terminal
        return object : ClientSessionOperations,
            com.agentclientprotocol.common.FileSystemOperations by AcpFileSystemOperations(cwd),
            com.agentclientprotocol.common.TerminalOperations by terminal {
            override suspend fun requestPermissions(
                toolCall: com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate,
                permissions: List<com.agentclientprotocol.model.PermissionOption>,
                _meta: JsonElement?,
            ): com.agentclientprotocol.model.RequestPermissionResponse {
                // Permission RPCs often carry the only useful title (path / command). Session
                // tool notifications for Edit/Terminal are frequently bare verbs with empty args —
                // project this update into the transcript before auto-allow or prompting.
                if (!suppressHistoryReplay) {
                    mapEvent(toolCall)?.let(onEvent)
                }
                return bridge.request(toolCall, permissions, _meta)
            }

            override suspend fun notify(notification: com.agentclientprotocol.model.SessionUpdate, _meta: JsonElement?) {
                if (suppressHistoryReplay) return
                mapEvent(notification)?.let(onEvent)
            }
        }
    }

    private fun mapEvent(update: SessionUpdate): AgentEvent? = AcpEventMapper.map(
        update = update,
        knownSkillNames = knownSkillNames,
        allowedSkillNames = allowedSkillNames,
        terminalOutput = { terminalId -> terminalOps?.bufferedOutput(terminalId) },
    )
}

/** Model id passed to ACP `setConfigOption` / `setModel`. Goose wants the provider-native name. */
internal fun acpSessionModelId(task: AgentTask): String? {
    if (task.runtimeKind() == AgentKind.Goose) return gooseProviderAndModel(task).second
    return task.modelForCli()?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * cursor-agent always advertises `cursor_login`. `session/new` can succeed when
 * `~/.config/cursor/auth.json` is visible, but fails with "Authentication required"
 * when Cursor's sandbox `XDG_CONFIG_HOME` hides it. `authenticate` is the ACP
 * handshake that binds that login (and yields a clearer error if it is missing).
 */
internal suspend fun Client.authenticateAdvertisedLogin(agent: AgentKind, agentInfo: AgentInfo) {
    if (agent != AgentKind.Cursor) return
    val methodId = agentInfo.authMethods.firstOrNull()?.id ?: return
    authenticate(methodId)
}
