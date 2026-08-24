package app.andy.desktop.service.remote

import app.andy.desktop.service.DesktopActionConfigStore
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.model.ActionsConfig
import app.andy.service.AutomationService
import app.andy.service.RemoteSessionService
import app.andy.service.RemoteSessionState
import app.andy.service.RemoteSessionStatus
import app.andy.terminal.TmuxAndy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SSH-tunnels remote `andyd.sock` + `tmux -L andy` into local temp sockets, capability-gates
 * the remote daemon, then swaps [SwappableAgentBackend] / automations onto a remote
 * [McpAgentRunClient]. Uses system `ssh` only (no Andy-stored secrets, no agent forwarding).
 * Password / passphrase prompts use a GUI askpass dialog (no TTY BatchMode).
 */
class DesktopRemoteSessionService(
    private val workspaceStore: DesktopWorkspaceStore,
    private val scope: CoroutineScope,
    private val attachBridge: DesktopAgentRunService,
    private val agentBackend: SwappableAgentBackend,
    private val automationBackend: SwappableAutomationService,
    private val localAgentBackend: app.andy.service.AgentRunService,
    private val localAutomations: AutomationService,
    private val androidBackend: AndroidBackendSwitcher? = null,
) : RemoteSessionService {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        RemoteSessionState(savedTargets = workspaceStore.state.value.savedSshTargets),
    )
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    private val _remoteActionsConfig = MutableStateFlow<ActionsConfig?>(null)
    override val remoteActionsConfig: StateFlow<ActionsConfig?> = _remoteActionsConfig.asStateFlow()

    private val tunnel = AtomicReference<TunnelHandles?>(null)
    private val remoteClient = AtomicReference<McpAgentRunClient?>(null)
    /** Authenticated remotes kept alive while the UI is on Local or another host. */
    private val warmByTarget = java.util.concurrent.ConcurrentHashMap<String, WarmSession>()
    private var watchJob: Job? = null
    private val idSeq = AtomicLong(1)

    private data class WarmSession(
        val handles: TunnelHandles,
        val client: McpAgentRunClient,
        val actionsConfig: ActionsConfig,
    ) {
        fun isAlive(): Boolean =
            handles.process.isAlive && handles.localAndyd.exists()
    }

    init {
        scope.launch {
            workspaceStore.state.collect { ws ->
                _state.update { it.copy(savedTargets = ws.savedSshTargets) }
            }
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                warmByTarget.keys.toList().forEach { destroyWarm(it) }
                teardownTunnelOnly()
                SshAskpassBroker.clear()
            },
        )
    }

    override suspend fun connect(target: String): Result<Unit> = mutex.withLock {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("SSH target is empty"))
        }
        if (!isSupportedPlatform()) {
            return Result.failure(
                IllegalStateException("SSH remote is only supported on macOS and Linux."),
            )
        }
        // Already on this host — no tear-down / re-auth.
        if (_state.value.status == RemoteSessionStatus.Connected && _state.value.target == trimmed) {
            return Result.success(Unit)
        }
        _state.update {
            it.copy(status = RemoteSessionStatus.Connecting, target = trimmed, error = null)
        }
        // Keep the current remote warm when hopping away (Local or another host).
        parkActiveIfAny()
        TmuxAndy.useAbsoluteSocket(null)
        agentBackend.switchTo(localAgentBackend)
        automationBackend.switchTo(localAutomations)
        androidBackend?.deactivateRemote()
        _remoteActionsConfig.value = null

        val warm = warmByTarget[trimmed]
        if (warm != null) {
            if (warm.isAlive()) {
                val result = runCatching { activateWarm(trimmed, warm) }
                if (result.isSuccess) return Result.success(Unit)
                destroyWarm(trimmed)
            } else {
                destroyWarm(trimmed)
            }
        }

        val result = runCatching { connectLocked(trimmed) }
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "SSH remote connect failed"
            SshAskpassBroker.forget(trimmed)
            teardownTunnelOnly()
            TmuxAndy.useAbsoluteSocket(null)
            agentBackend.switchTo(localAgentBackend)
            automationBackend.switchTo(localAutomations)
            androidBackend?.deactivateRemote()
            _remoteActionsConfig.value = null
            _state.update {
                it.copy(status = RemoteSessionStatus.Local, target = null, error = message)
            }
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException(message))
        }
        Result.success(Unit)
    }

    override suspend fun disconnect() = mutex.withLock {
        // Park (don't kill) so switching back doesn't re-prompt for the password.
        disconnectLocked(restoreLocal = true, park = true)
    }

    override suspend fun reconnect(): Result<Unit> {
        val target = _state.value.target
            ?: workspaceStore.state.value.savedSshTargets.firstOrNull()
            ?: return Result.failure(IllegalStateException("No SSH target to reconnect"))
        mutex.withLock {
            destroyWarm(target)
            SshAskpassBroker.forget(target)
            // Drop active if it's this target without parking.
            if (tunnel.get()?.target == target) {
                watchJob?.cancel()
                watchJob = null
                remoteClient.getAndSet(null)?.setSshProbeTarget(null)
                teardownTunnelOnly()
            }
        }
        return connect(target)
    }

    override suspend fun addSavedTarget(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return
        val current = workspaceStore.state.value
        if (trimmed in current.savedSshTargets) return
        workspaceStore.save(current.copy(savedSshTargets = current.savedSshTargets + trimmed))
    }

    override suspend fun removeSavedTarget(target: String) {
        val trimmed = target.trim()
        mutex.withLock {
            destroyWarm(trimmed)
            if (tunnel.get()?.target == trimmed) {
                disconnectLocked(restoreLocal = true, park = false)
            }
        }
        val current = workspaceStore.state.value
        workspaceStore.save(
            current.copy(savedSshTargets = current.savedSshTargets.filterNot { it == trimmed }),
        )
        SshAskpassBroker.forget(trimmed)
    }

    private suspend fun connectLocked(target: String) = withContext(Dispatchers.IO) {
        val controlPath = SshProcess.controlPathForTarget(target)
        controlPath.delete()
        val paths = resolveRemotePaths(target, controlPath)
        val localAndyd = SshProcess.localAndydSocket(target)
        val localTmux = SshProcess.localTmuxSocket(target)
        localAndyd.delete()
        localTmux.delete()
        val localAdbPort = SshAdbTunnel.allocateLocalPort()

        val sshProcess = startSshTunnel(
            target = target,
            localAndyd = localAndyd,
            remoteAndyd = paths.andydSock,
            localTmux = localTmux,
            remoteTmux = paths.tmuxSock,
            localAdbPort = localAdbPort,
            controlPath = controlPath,
        )
        val handles = TunnelHandles(
            process = sshProcess,
            localAndyd = localAndyd,
            localTmux = localTmux,
            controlPath = controlPath,
            target = target,
            localAdbPort = localAdbPort,
        )
        tunnel.set(handles)

        awaitSocket(localAndyd, label = "andyd")
        // tmux socket may be absent until a session exists; still require andyd.
        TmuxAndy.useAbsoluteSocket(localTmux)

        val missing = probeRequiredTools(localAndyd)
        if (missing.isNotEmpty()) {
            error(
                "Remote andyd is missing required tools: ${missing.joinToString(", ")}. " +
                    "Update andyd on $target and retry.",
            )
        }

        val client = McpAgentRunClient(
            scope = scope,
            socketPath = localAndyd,
        )
        client.attachLocalTerminalBridge(attachBridge)
        client.setSshProbeTarget(target, controlPath)
        remoteClient.set(client)
        agentBackend.switchTo(client)
        automationBackend.switchTo(client)
        activateAndroidBackend(handles)

        _remoteActionsConfig.value = loadRemoteActionsConfig(target, controlPath)

        addSavedTargetQuiet(target)
        _state.update {
            it.copy(status = RemoteSessionStatus.Connected, target = target, error = null)
        }
        startWatch(target, localAndyd, sshProcess)
    }

    private suspend fun activateWarm(target: String, warm: WarmSession) {
        warmByTarget.remove(target)
        tunnel.set(warm.handles)
        remoteClient.set(warm.client)
        warm.client.setSshProbeTarget(target, warm.handles.controlPath)
        warm.client.attachLocalTerminalBridge(attachBridge)
        TmuxAndy.useAbsoluteSocket(warm.handles.localTmux)
        agentBackend.switchTo(warm.client)
        automationBackend.switchTo(warm.client)
        activateAndroidBackend(warm.handles)

        _remoteActionsConfig.value = warm.actionsConfig
        addSavedTargetQuiet(target)
        _state.update {
            it.copy(status = RemoteSessionStatus.Connected, target = target, error = null)
        }
        startWatch(target, warm.handles.localAndyd, warm.handles.process)
    }

    private fun activateAndroidBackend(handles: TunnelHandles) {
        val adbTunnel = SshAdbTunnel(
            target = handles.target,
            controlPath = handles.controlPath,
            localAdbPort = handles.localAdbPort,
        )
        androidBackend?.activateRemote(adbTunnel)
    }

    private fun parkActiveIfAny() {
        watchJob?.cancel()
        watchJob = null
        val handles = tunnel.getAndSet(null) ?: return
        val client = remoteClient.getAndSet(null)
        if (client == null) {
            destroyHandles(handles)
            return
        }
        client.setSshProbeTarget(null)
        val actions = _remoteActionsConfig.value ?: ActionsConfig()
        // Replace any stale warm entry for this host.
        warmByTarget.put(handles.target, WarmSession(handles, client, actions))?.let { stale ->
            if (stale.handles !== handles) destroyHandles(stale.handles)
        }
    }

    private fun destroyWarm(target: String) {
        warmByTarget.remove(target)?.let { warm ->
            warm.client.setSshProbeTarget(null)
            destroyHandles(warm.handles)
        }
    }

    private fun destroyHandles(handles: TunnelHandles) {
        handles.process.destroy()
        runCatching {
            if (!handles.process.waitFor(2, TimeUnit.SECONDS)) {
                handles.process.destroyForcibly()
            }
        }
        SshProcess.exitMaster(handles.controlPath)
        handles.localAndyd.delete()
        handles.localTmux.delete()
    }

    override suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit> = mutex.withLock {
        val handles = tunnel.get()
            ?: return Result.failure(IllegalStateException("Not connected to a remote host"))
        runCatching {
            writeRemoteActionsConfig(handles.target, handles.controlPath, config)
            _remoteActionsConfig.value = config
        }
    }

    private fun addSavedTargetQuiet(target: String) {
        scope.launch {
            runCatching { addSavedTarget(target) }
        }
    }

    private fun startWatch(target: String, localAndyd: File, sshProcess: Process) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val alive = sshProcess.isAlive && localAndyd.exists()
                if (!alive) {
                    mutex.withLock {
                        if (_state.value.status == RemoteSessionStatus.Connected &&
                            _state.value.target == target
                        ) {
                            // Tunnel died — drop warm + active; password cache still helps reconnect.
                            destroyWarm(target)
                            disconnectLocked(restoreLocal = true, park = false)
                            _state.update {
                                it.copy(
                                    status = RemoteSessionStatus.Local,
                                    target = null,
                                    error = "SSH tunnel to $target dropped. Reconnect to continue remotely.",
                                )
                            }
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun disconnectLocked(restoreLocal: Boolean, park: Boolean = true) {
        watchJob?.cancel()
        watchJob = null
        if (park) {
            parkActiveIfAny()
        } else {
            remoteClient.getAndSet(null)?.setSshProbeTarget(null)
            teardownTunnelOnly()
        }
        _remoteActionsConfig.value = null
        TmuxAndy.useAbsoluteSocket(null)
        if (restoreLocal) {
            agentBackend.switchTo(localAgentBackend)
            automationBackend.switchTo(localAutomations)
            androidBackend?.deactivateRemote()
        }
        _state.update {
            it.copy(status = RemoteSessionStatus.Local, target = null, error = null)
        }
    }

    private fun teardownTunnelOnly() {
        tunnel.getAndSet(null)?.let { destroyHandles(it) }
    }

    private data class RemotePaths(val andydSock: String, val tmuxSock: String)

    private fun resolveRemotePaths(target: String, controlPath: File): RemotePaths {
        val remoteCommand =
            "set -e; " +
                "ANDY_SOCK=\"\$HOME/.andy/andyd.sock\"; " +
                "if [ ! -S \"\$ANDY_SOCK\" ]; then echo \"andyd_missing:\$ANDY_SOCK\" >&2; exit 2; fi; " +
                "UID_N=\$(id -u); " +
                "TMP=\${TMUX_TMPDIR:-\${TMPDIR:-/tmp}}; " +
                "TMUX_SOCK=\"\$TMP/tmux-\$UID_N/andy\"; " +
                "printf '%s\\n%s\\n' \"\$ANDY_SOCK\" \"\$TMUX_SOCK\""
        val result = SshRemoteProbes(target, controlPath).sshShell(remoteCommand)
        if (result.exitCode == 2 || result.stderr.contains("andyd_missing")) {
            error(
                "Remote andyd is not running (missing ~/.andy/andyd.sock). " +
                    "Start standalone andyd (launchd/systemd) on $target, then retry.",
            )
        }
        if (result.exitCode != 0) {
            error(sshFailureMessage(target, result.stderr, result.stdout, result.exitCode))
        }
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) {
            error("Could not resolve remote andyd/tmux sockets on $target")
        }
        return RemotePaths(andydSock = lines[0], tmuxSock = lines[1])
    }

    /**
     * Persistent ssh master we can destroy on disconnect. Reuses [controlPath] from the
     * path-resolve step so password askpass only runs once.
     */
    private fun startSshTunnel(
        target: String,
        localAndyd: File,
        remoteAndyd: String,
        localTmux: File,
        remoteTmux: String,
        localAdbPort: Int,
        controlPath: File,
    ): Process {
        localAndyd.delete()
        localTmux.delete()
        val cmd = buildList {
            add("ssh")
            add("-N")
            addAll(SshProcess.baseOptions(controlPath))
            add("-o")
            add("ExitOnForwardFailure=yes")
            add("-o")
            add("ServerAliveInterval=15")
            add("-o")
            add("ServerAliveCountMax=3")
            add("-L")
            add("${localAndyd.absolutePath}:$remoteAndyd")
            add("-L")
            add("${localTmux.absolutePath}:$remoteTmux")
            // Tunnel remote adb server (default 5037) so local scrcpy/adb talk to remote devices.
            add("-L")
            add("$localAdbPort:127.0.0.1:5037")
            add(target)
        }
        val process = SshProcess.processBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        // Give forwards a moment; failure usually exits the process quickly.
        // Password dialog may take longer — wait up to ~2 minutes for the socket.
        var waited = 0
        while (process.isAlive && !localAndyd.exists() && waited < 120_000) {
            Thread.sleep(100)
            waited += 100
        }
        if (!process.isAlive) {
            val err = process.inputStream.bufferedReader().readText().trim()
            error(sshFailureMessage(target, err, "", process.exitValue()))
        }
        return process
    }

    private fun sshFailureMessage(target: String, stderr: String, stdout: String, exitCode: Int): String {
        val detail = stderr.ifBlank { stdout }.trim().ifBlank { "exit $exitCode" }
        val authHint =
            if (detail.contains("Permission denied", ignoreCase = true) ||
                detail.contains("publickey", ignoreCase = true) ||
                detail.contains("Authentication failed", ignoreCase = true)
            ) {
                " Enter the password in the Andy SSH dialog when prompted, or set up key-based auth (`ssh-copy-id`) so Connect works without a password."
            } else {
                ""
            }
        return "SSH to $target failed: $detail.$authHint"
    }

    private fun loadRemoteActionsConfig(target: String, controlPath: File): ActionsConfig {
        val result = SshRemoteProbes(target, controlPath).sshShell(
            "if [ -f \"\$HOME/.andy/actions.toml\" ]; then cat \"\$HOME/.andy/actions.toml\"; fi",
        )
        if (result.exitCode != 0) {
            error(
                "Could not read remote ~/.andy/actions.toml: " +
                    result.stderr.ifBlank { result.stdout }.trim().ifBlank { "exit ${result.exitCode}" },
            )
        }
        val text = result.stdout.trim()
        if (text.isEmpty()) return ActionsConfig()
        return runCatching { DesktopActionConfigStore.parseToml(text) }
            .getOrElse { err ->
                error("Remote ~/.andy/actions.toml is invalid: ${err.message ?: err}")
            }
    }

    private fun writeRemoteActionsConfig(target: String, controlPath: File, config: ActionsConfig) {
        val toml = DesktopActionConfigStore.encodeToml(config)
        // Write via a here-doc so we don't need sftp; quote delimiter so remote doesn't expand.
        val remote = buildString {
            append("mkdir -p \"\$HOME/.andy\" && cat > \"\$HOME/.andy/actions.toml\" <<'ANDY_ACTIONS_EOF'\n")
            append(toml)
            if (!toml.endsWith("\n")) append('\n')
            append("ANDY_ACTIONS_EOF\n")
        }
        val result = SshRemoteProbes(target, controlPath).sshShell(remote)
        if (result.exitCode != 0) {
            error(
                "Could not write remote ~/.andy/actions.toml: " +
                    result.stderr.ifBlank { result.stdout }.trim().ifBlank { "exit ${result.exitCode}" },
            )
        }
    }

    private fun awaitSocket(file: File, label: String) {
        repeat(40) {
            if (file.exists()) return
            Thread.sleep(50)
        }
        error("SSH tunnel did not create local $label socket at ${file.absolutePath}")
    }

    private fun probeRequiredTools(socket: File): List<String> {
        val required = listOf(
            "chat.list",
            "chat.start",
            "chat.events",
            "project.list",
            "automation.list",
        )
        val available = listTools(socket)
        return required.filter { it !in available }
    }

    private fun listTools(socket: File): Set<String> {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
            val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
            val initId = idSeq.getAndIncrement()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", initId)
                    put("method", "initialize")
                    put(
                        "params",
                        buildJsonObject {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", buildJsonObject {})
                            put(
                                "clientInfo",
                                buildJsonObject {
                                    put("name", "andy-gui-remote")
                                    put("version", "1.0.0")
                                },
                            )
                        },
                    )
                }.toString(),
            )
            writer.write("\n")
            writer.flush()
            reader.readLine()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString(),
            )
            writer.write("\n")
            writer.flush()
            val listId = idSeq.getAndIncrement()
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", listId)
                    put("method", "tools/list")
                    put("params", buildJsonObject {})
                }.toString(),
            )
            writer.write("\n")
            writer.flush()
            val line = reader.readLine() ?: return emptySet()
            val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return emptySet()
            val tools = root["result"]?.jsonObject?.get("tools")?.jsonArray
                ?: return emptySet()
            return tools.mapNotNull { el ->
                el.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }.toSet()
        }
    }

    companion object {
        fun isSupportedPlatform(): Boolean {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return os.contains("mac") || os.contains("darwin") || os.contains("linux")
        }
    }

    private data class TunnelHandles(
        val process: Process,
        val localAndyd: File,
        val localTmux: File,
        val controlPath: File,
        val target: String,
        val localAdbPort: Int,
    )
}
