package app.andy.desktop.service.remote

import app.andy.desktop.service.DesktopActionConfigStore
import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopLocalServerService
import app.andy.service.WorkspaceStore
import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.model.ActionsConfig
import app.andy.service.ActionRunService
import app.andy.service.AgentRunService
import app.andy.service.AutomationService
import app.andy.service.CommandResult
import app.andy.service.LocalServerService
import app.andy.service.RemoteHostCapabilities
import app.andy.service.RemoteScreenAvailability
import app.andy.service.RemoteSessionService
import app.andy.service.RemoteSessionState
import app.andy.service.RemoteSessionStatus
import app.andy.service.RemoteShellEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
    private val workspaceStore: WorkspaceStore,
    private val scope: CoroutineScope,
    private val attachBridge: DesktopAgentRunService,
    private val agentBackend: SwappableAgentBackend,
    private val automationBackend: SwappableAutomationService,
    private val localAgentBackend: app.andy.service.AgentRunService,
    private val localAutomations: AutomationService,
    private val androidBackend: AndroidBackendSwitcher? = null,
    private val localServers: SwappableLocalServerService? = null,
    private val localLocalServers: LocalServerService? = null,
    private val agentRunsForLocalServers: AgentRunService? = null,
    private val actionRunsForLocalServers: ActionRunService? = null,
) : RemoteSessionService {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        RemoteSessionState(savedTargets = workspaceStore.state?.value?.savedSshTargets.orEmpty()),
    )
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    private val _remoteActionsConfig = MutableStateFlow<ActionsConfig?>(null)
    override val remoteActionsConfig: StateFlow<ActionsConfig?> = _remoteActionsConfig.asStateFlow()
    private val _portForwards = MutableStateFlow<Map<Int, Int>>(emptyMap())
    override val portForwards: StateFlow<Map<Int, Int>> = _portForwards.asStateFlow()

    private val tunnel = AtomicReference<TunnelHandles?>(null)
    private val remoteClient = AtomicReference<McpAgentRunClient?>(null)
    private var remoteLocalServers: DesktopLocalServerService? = null
    /** Authenticated remotes kept alive while the UI is on Local or another host. */
    private val warmByTarget = java.util.concurrent.ConcurrentHashMap<String, WarmSession>()
    private var watchJob: Job? = null
    private var remoteTerminalTaskIdsJob: Job? = null
    private val idSeq = AtomicLong(1)

    private data class WarmSession(
        val handles: TunnelHandles,
        val client: McpAgentRunClient,
        val actionsConfig: ActionsConfig,
        val capabilities: RemoteHostCapabilities? = null,
    ) {
        fun isAlive(): Boolean =
            handles.process.isAlive && handles.localAndyd.exists()
    }

    init {
        scope.launch {
            workspaceStore.state?.collect { ws ->
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

    override suspend fun connect(target: String, rememberPassword: Boolean): Result<Unit> = mutex.withLock {
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
        clearRemoteTerminalBridge()
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

        SshProcess.debugLog("connect start target=$trimmed remember=$rememberPassword")
        SshAskpassBroker.prepareTarget(trimmed)
        val result = runCatching { connectLocked(trimmed) }
        if (result.isFailure) {
            val raw = result.exceptionOrNull()?.message ?: "SSH remote connect failed"
            val message = when {
                SshAskpassBroker.wasCancelled() -> "SSH authentication cancelled"
                else -> raw
            }
            SshProcess.debugLog("connect FAIL: $message")
            SshAskpassBroker.forget(trimmed)
            SshAskpassBroker.clearActiveTarget()
            if (message.contains("Permission denied", ignoreCase = true) ||
                message.contains("Authentication failed", ignoreCase = true)
            ) {
                runCatching { SshCredentialStore.delete(trimmed) }
            }
            teardownTunnelOnly()
            clearRemoteTerminalBridge()
            agentBackend.switchTo(localAgentBackend)
            automationBackend.switchTo(localAutomations)
            androidBackend?.deactivateRemote()
            deactivateLocalServerBackend()
            publishPortForwards(null)
            _remoteActionsConfig.value = null
            _state.update {
                it.copy(
                    status = RemoteSessionStatus.Local,
                    target = null,
                    error = message,
                    hostCapabilities = null,
                )
            }
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException(message))
        }
        if (rememberPassword) {
            SshAskpassBroker.lastSecretFor(trimmed)?.let { secret ->
                SshProcess.debugLog("keychain save for $trimmed")
                runCatching { SshCredentialStore.save(trimmed, secret) }
            }
        }
        SshAskpassBroker.clearActiveTarget()
        SshProcess.debugLog("connect OK target=$trimmed")
        Result.success(Unit)
    }

    override suspend fun disconnect() = mutex.withLock {
        // Park (don't kill) so switching back doesn't re-prompt for the password.
        disconnectLocked(restoreLocal = true, park = true)
    }

    override suspend fun reconnect(rememberPassword: Boolean): Result<Unit> {
        val target = _state.value.target
            ?: workspaceStore.load().savedSshTargets.firstOrNull()
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
                clearRemoteTerminalBridge()
                _state.update {
                    it.copy(status = RemoteSessionStatus.Local, target = target, error = null)
                }
            }
        }
        return connect(target, rememberPassword)
    }

    override fun shellEndpoint(): RemoteShellEndpoint? {
        if (_state.value.status != RemoteSessionStatus.Connected) return null
        val handles = tunnel.get() ?: return null
        return RemoteShellEndpoint(
            sshTarget = handles.target,
            controlPath = handles.controlPath.absolutePath,
        )
    }

    override suspend fun addSavedTarget(target: String) {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return
        workspaceStore.update { current ->
            if (trimmed in current.savedSshTargets) current
            else current.copy(savedSshTargets = current.savedSshTargets + trimmed)
        }
    }

    override suspend fun removeSavedTarget(target: String) {
        val trimmed = target.trim()
        mutex.withLock {
            destroyWarm(trimmed)
            if (tunnel.get()?.target == trimmed) {
                disconnectLocked(restoreLocal = true, park = false)
            }
        }
        workspaceStore.update { current ->
            current.copy(savedSshTargets = current.savedSshTargets.filterNot { it == trimmed })
        }
        SshAskpassBroker.forget(trimmed)
        runCatching { SshCredentialStore.delete(trimmed) }
    }

    private suspend fun connectLocked(target: String) = withContext(Dispatchers.IO) {
        val controlPath = SshProcess.controlPathForTarget(target)
        SshProcess.exitMaster(controlPath)
        controlPath.delete()
        // One-shot ssh (no ControlMaster) so we never leave a ControlPersist mux that a
        // second `ssh -N -L` would trip over with Broken pipe on OpenSSH 10+.
        SshProcess.debugLog("resolve paths one-shot target=$target")
        val paths = resolveRemotePaths(target, controlPath = null)
        SshProcess.debugLog("resolved andyd=${paths.andydSock} tmux=${paths.tmuxSock}")
        val localAndyd = SshProcess.localAndydSocket(target)
        val localTmux = SshProcess.localTmuxSocket(target)
        localAndyd.delete()
        localTmux.delete()
        val localAdbPort = SshAdbTunnel.allocateLocalPort()
        SshProcess.debugLog("startSshTunnel adbPort=$localAdbPort control=$controlPath")

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
            portForwarder = SshPortForwarder(target, controlPath),
        )
        tunnel.set(handles)

        SshProcess.debugLog("awaitSocket andyd")
        awaitSocket(localAndyd, label = "andyd")
        // tmux socket may be absent until a session exists; still require andyd.

        SshProcess.debugLog("probeRequiredTools begin alive=${sshProcess.isAlive}")
        val missing = try {
            probeRequiredTools(localAndyd)
        } catch (err: Throwable) {
            SshProcess.debugLog("probeRequiredTools THROW ${err::class.simpleName}: ${err.message}")
            throw IllegalStateException(
                "SSH tunnel is up but talking to remote andyd failed (${err.message}). " +
                    "Confirm andyd is running on $target (not just that ~/.andy/andyd.sock exists).",
                err,
            )
        }
        SshProcess.debugLog("probeRequiredTools ok missing=$missing")
        if (missing.isNotEmpty()) {
            error(
                "Remote andyd is missing required tools: ${missing.joinToString(", ")}. " +
                    "Update andyd on $target and retry.",
            )
        }

        SshProcess.debugLog("create McpAgentRunClient")
        val client = McpAgentRunClient(
            scope = scope,
            socketPath = localAndyd,
        )
        client.attachLocalTerminalBridge(attachBridge)
        client.setSshProbeTarget(target, controlPath)
        remoteClient.set(client)
        agentBackend.switchTo(client)
        automationBackend.switchTo(client)
        SshProcess.debugLog("activateAndroidBackend")
        activateAndroidBackend(handles)
        SshProcess.debugLog("activateLocalServerBackend")
        activateLocalServerBackend(handles)
        configureRemoteTerminalBridge(client, localTmux)

        val capabilities = runCatching {
            RemoteHostCapabilityScanner.probe(target, controlPath)
        }.getOrElse {
            SshProcess.debugLog("capability probe failed: ${it.message}")
            RemoteHostCapabilities(localVncClient = LocalVncClient.detect())
        }
        SshProcess.debugLog("loadRemoteActionsConfig")
        _remoteActionsConfig.value = loadRemoteActionsConfig(target, controlPath)

        addSavedTargetQuiet(target)
        publishPortForwards(handles)
        _state.update {
            it.copy(
                status = RemoteSessionStatus.Connected,
                target = target,
                error = null,
                hostCapabilities = capabilities,
            )
        }
        startWatch(target, localAndyd, sshProcess)
        SshProcess.debugLog("connectLocked done")
    }

    private suspend fun activateWarm(target: String, warm: WarmSession) {
        warmByTarget.remove(target)
        tunnel.set(warm.handles)
        remoteClient.set(warm.client)
        warm.client.setSshProbeTarget(target, warm.handles.controlPath)
        warm.client.attachLocalTerminalBridge(attachBridge)
        agentBackend.switchTo(warm.client)
        automationBackend.switchTo(warm.client)
        activateAndroidBackend(warm.handles)
        activateLocalServerBackend(warm.handles)
        configureRemoteTerminalBridge(warm.client, warm.handles.localTmux)

        _remoteActionsConfig.value = warm.actionsConfig
        addSavedTargetQuiet(target)
        publishPortForwards(warm.handles)
        _state.update {
            it.copy(
                status = RemoteSessionStatus.Connected,
                target = target,
                error = null,
                hostCapabilities = warm.capabilities,
            )
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

    private fun activateLocalServerBackend(handles: TunnelHandles) {
        val swappable = localServers ?: return
        val agents = agentRunsForLocalServers ?: return
        val actions = actionRunsForLocalServers ?: return
        val probes = SshRemoteProbes(handles.target, handles.controlPath)
        val sshRunner = CommandRunner(executor = { command, _ ->
            val result = probes.sshExec(command)
            CommandResult(result.exitCode, result.stdout, result.stderr)
        })
        val remote = DesktopLocalServerService(
            runner = sshRunner,
            agentRuns = agents,
            actionRuns = actions,
            scope = scope,
        )
        remoteLocalServers?.dispose()
        remoteLocalServers = remote
        swappable.switchTo(remote)
    }

    private fun deactivateLocalServerBackend() {
        val swappable = localServers ?: return
        val local = localLocalServers ?: return
        remoteLocalServers?.dispose()
        remoteLocalServers = null
        swappable.switchTo(local)
    }

    private fun publishPortForwards(handles: TunnelHandles?) {
        _portForwards.value = handles?.portForwarder?.mapping().orEmpty()
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
        val capabilities = _state.value.hostCapabilities
        // Replace any stale warm entry for this host.
        warmByTarget.put(
            handles.target,
            WarmSession(handles, client, actions, capabilities),
        )?.let { stale ->
            if (stale.handles !== handles) destroyHandles(stale.handles)
        }
        clearRemoteTerminalBridge()
        deactivateLocalServerBackend()
        publishPortForwards(null)
    }

    private fun configureRemoteTerminalBridge(client: McpAgentRunClient, localTmux: File) {
        attachBridge.setForwardedTmuxSocket(localTmux)
        attachBridge.setRemoteTerminalTaskIds(client.tasks.value.map { it.id })
        remoteTerminalTaskIdsJob?.cancel()
        remoteTerminalTaskIdsJob = scope.launch {
            client.tasks.collect { tasks ->
                attachBridge.setRemoteTerminalTaskIds(tasks.map { it.id })
            }
        }
    }

    private fun clearRemoteTerminalBridge() {
        remoteTerminalTaskIdsJob?.cancel()
        remoteTerminalTaskIdsJob = null
        attachBridge.setForwardedTmuxSocket(null)
        attachBridge.setRemoteTerminalTaskIds(emptyList())
    }

    private fun destroyWarm(target: String) {
        warmByTarget.remove(target)?.let { warm ->
            warm.client.setSshProbeTarget(null)
            destroyHandles(warm.handles)
        }
    }

    private fun destroyHandles(handles: TunnelHandles) {
        runCatching { handles.portForwarder.releaseAll() }
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

    override suspend fun forwardPort(remotePort: Int): Result<Int> = mutex.withLock {
        val handles = tunnel.get()
            ?: return Result.failure(IllegalStateException("Not connected to a remote host"))
        runCatching {
            val local = handles.portForwarder.forward(remotePort)
            publishPortForwards(handles)
            local
        }
    }

    override suspend fun openRemoteScreen(): Result<String> = mutex.withLock {
        val handles = tunnel.get()
            ?: return Result.failure(IllegalStateException("Not connected to a remote host"))
        val caps = _state.value.hostCapabilities
            ?: return Result.failure(IllegalStateException("Remote host capabilities are unknown"))
        when (caps.screenAvailability) {
            RemoteScreenAvailability.NeedsEnabling ->
                return Result.success(
                    caps.enablementHint
                        ?: "Enable Screen Sharing / a VNC server on the remote host, then reconnect.",
                )
            RemoteScreenAvailability.Unsupported ->
                return Result.success(
                    "Remote screen sharing is not available on this host. " +
                        (caps.enablementHint ?: "No VNC server path was detected."),
                )
            RemoteScreenAvailability.Available -> Unit
        }
        runCatching {
            val localPort = handles.portForwarder.forward(caps.vncPort)
            publishPortForwards(handles)
            val vncUrl = "vnc://127.0.0.1:$localPort"
            val client = caps.localVncClient
            if (client == null) {
                copyToClipboard(vncUrl)
                "No VNC client found on this machine. Copied $vncUrl to the clipboard — " +
                    "open it with your Screen Sharing / VNC app."
            } else {
                val argv = LocalVncClient.launchArgv(client, vncUrl)
                val process = ProcessBuilder(argv).redirectErrorStream(true).start()
                // Don't wait forever — Screen Sharing.app stays running.
                val exitedQuickly = process.waitFor(800, TimeUnit.MILLISECONDS)
                if (exitedQuickly && process.exitValue() != 0) {
                    val err = process.inputStream.bufferedReader().readText().take(200)
                    copyToClipboard(vncUrl)
                    "Could not launch VNC client (${err.ifBlank { "exit ${process.exitValue()}" }}). " +
                        "Copied $vncUrl to the clipboard."
                } else {
                    "Opening remote screen via $vncUrl"
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
            deactivateLocalServerBackend()
            publishPortForwards(null)
        }
        _remoteActionsConfig.value = null
        clearRemoteTerminalBridge()
        if (restoreLocal) {
            agentBackend.switchTo(localAgentBackend)
            automationBackend.switchTo(localAutomations)
            androidBackend?.deactivateRemote()
            deactivateLocalServerBackend()
        }
        _state.update {
            it.copy(
                status = RemoteSessionStatus.Local,
                target = null,
                error = null,
                hostCapabilities = null,
            )
        }
    }

    private fun teardownTunnelOnly() {
        tunnel.getAndSet(null)?.let { destroyHandles(it) }
    }

    private data class RemotePaths(val andydSock: String, val tmuxSock: String)

    private fun resolveRemotePaths(target: String, controlPath: File?): RemotePaths {
        val remoteCommand =
            "set -e; " +
                "ANDY_SOCK=\"\$HOME/.andy/andyd.sock\"; " +
                "if [ ! -S \"\$ANDY_SOCK\" ]; then echo \"andyd_missing:\$ANDY_SOCK\" >&2; exit 2; fi; " +
                // Socket file can be stale — require an actual accept/connect.
                "if command -v python3 >/dev/null 2>&1; then " +
                "python3 -c \"import socket,sys; s=socket.socket(socket.AF_UNIX); s.settimeout(3); s.connect(sys.argv[1]); s.close()\" \"\$ANDY_SOCK\" " +
                "|| { echo \"andyd_not_accepting:\$ANDY_SOCK\" >&2; exit 3; }; " +
                "fi; " +
                "UID_N=\$(id -u); " +
                "TMP=\${TMUX_TMPDIR:-\${TMPDIR:-/tmp}}; " +
                "TMUX_SOCK=\"\$TMP/tmux-\$UID_N/andy\"; " +
                "printf '%s\\n%s\\n' \"\$ANDY_SOCK\" \"\$TMUX_SOCK\""
        SshProcess.debugLog("sshShell resolve controlPath=${controlPath?.absolutePath ?: "none"}")
        val result = SshRemoteProbes(target, controlPath).sshShell(remoteCommand)
        SshProcess.debugLog("sshShell resolve exit=${result.exitCode} stderr=${result.stderr.take(200)}")
        if (result.exitCode == 2 || result.stderr.contains("andyd_missing")) {
            error(
                "Remote andyd is not running (missing ~/.andy/andyd.sock). " +
                    "Start standalone andyd (launchd/systemd) on $target, then retry.",
            )
        }
        if (result.exitCode == 3 || result.stderr.contains("andyd_not_accepting")) {
            error(
                "Restart andyd on $target — ~/.andy/andyd.sock is stale (not accepting). " +
                    "On the remote: remove ~/.andy/andyd.sock and start andyd (Andy app, " +
                    "launchd, or `andy` daemon), then Connect again.",
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
     * Long-lived `ssh -N` ControlMaster that also owns the unix/TCP local forwards.
     * Askpass password is reused from the one-shot [resolveRemotePaths] via [SshAskpassBroker].
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
        controlPath.delete()
        val cmd = buildList {
            add("ssh")
            add("-N")
            addAll(SshProcess.masterOptions(controlPath))
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
        SshProcess.debugLog("ssh -N master cmd=${cmd.joinToString(" ")}")
        val masterLog = File("/tmp", "andy-ssh-master-${SshProcess.targetKey(target)}.log")
        val process = SshProcess.processBuilder(cmd)
            .redirectOutput(masterLog)
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
            val err = masterLog.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            SshProcess.debugLog("ssh -N master died: $err")
            error(sshFailureMessage(target, err, "", process.exitValue()))
        }
        // Local socket file can appear before the streamlocal forward accepts — wait for a real connect.
        var ready = false
        var readyWaited = 0
        while (process.isAlive && !ready && readyWaited < 10_000) {
            ready = runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                    ch.connect(UnixDomainSocketAddress.of(localAndyd.toPath()))
                }
                true
            }.getOrDefault(false)
            if (!ready) {
                Thread.sleep(100)
                readyWaited += 100
            }
        }
        SshProcess.debugLog(
            "ssh -N master up andydExists=${localAndyd.exists()} connectReady=$ready " +
                "waitedMs=$waited readyWaitedMs=$readyWaited alive=${process.isAlive}",
        )
        if (!ready) {
            val err = masterLog.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            process.destroyForcibly()
            error(
                sshFailureMessage(
                    target,
                    err.ifBlank { "local andyd forward never accepted a connection" },
                    "",
                    -1,
                ),
            )
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
        var lastError: Throwable? = null
        repeat(15) { attempt ->
            val available = runCatching { listTools(socket) }
                .onFailure {
                    lastError = it
                    SshProcess.debugLog("listTools attempt=$attempt failed: ${it.message}")
                }
                .getOrNull()
            if (available != null) {
                return required.filter { it !in available }
            }
            Thread.sleep(150)
        }
        throw lastError ?: IllegalStateException("Could not list tools on remote andyd")
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
        val portForwarder: SshPortForwarder,
    )
}
