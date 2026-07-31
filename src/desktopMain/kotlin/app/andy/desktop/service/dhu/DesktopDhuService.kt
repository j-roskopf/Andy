package app.andy.desktop.service.dhu

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.dhu.capture.DhuPointerAction
import app.andy.desktop.service.dhu.capture.DhuWindowHost
import app.andy.desktop.service.dhu.capture.DhuWindowRef
import app.andy.desktop.service.dhu.capture.createDhuWindowHost
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.classifyDeviceKind
import app.andy.model.classifyDeviceTransport
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuCommandFactory
import app.andy.service.DhuConsoleHistory
import app.andy.service.DhuConsoleState
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuLinkTransport
import app.andy.service.DhuReadiness
import app.andy.service.DhuService
import app.andy.service.DhuSession
import app.andy.service.DhuSessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

internal class DesktopDhuService(
    private val devices: DeviceService,
    private val runner: CommandRunner = CommandRunner(),
    private val host: DhuWindowHost = createDhuWindowHost(),
    private val configDir: File = File(System.getProperty("user.home"), ".andy/dhu"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : DhuService {
    private val mutex = Mutex()
    private val readinessState = MutableStateFlow(
        DhuReadiness(hostKind = host.hostKind, checks = emptyList()),
    )
    private val sessionState = MutableStateFlow<DhuSession?>(null)
    private val consoleState = MutableStateFlow(DhuConsoleState())
    private val captureState = MutableStateFlow<DhuCaptureFrame?>(null)

    override val readiness: StateFlow<DhuReadiness> = readinessState
    override val session: StateFlow<DhuSession?> = sessionState
    override val console: StateFlow<DhuConsoleState> = consoleState
    override val captureFrame: StateFlow<DhuCaptureFrame?> = captureState

    private val active = AtomicReference<ActiveSession?>(null)
    private var captureJob: Job? = null
    private var outputJob: Job? = null

    override suspend fun refreshReadiness(serial: String?): DhuReadiness = withContext(Dispatchers.IO) {
        val sdk = devices.discoverSdk()
        val device = if (serial.isNullOrBlank()) null else devices.listDevices().firstOrNull { it.serial == serial }
        val deviceOnline = device?.state == DeviceConnectionState.Online
        val link = if (device != null) {
            DhuCommandFactory.preferredLinkTransport(device.transport, device.kind)
        } else {
            null
        }
        val headUnitListening = if (
            link == DhuLinkTransport.Adb &&
            deviceOnline &&
            !serial.isNullOrBlank() &&
            !sdk.adbPath.isNullOrBlank()
        ) {
            probeHeadUnitServerListening(sdk.adbPath, serial)
        } else {
            null
        }
        val evaluated = DhuDiscovery.evaluate(
            sdkPath = sdk.sdkPath,
            adbPath = sdk.adbPath,
            serial = serial,
            deviceOnline = deviceOnline,
            env = host.environment(),
            headUnitServerListening = headUnitListening,
            linkTransport = link,
        )
        readinessState.value = evaluated
        evaluated
    }

    override suspend fun start(serial: String): CommandResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val readiness = refreshReadiness(serial)
            if (!readiness.ready) {
                val detail = readiness.blocking.joinToString("; ") { "${it.label}: ${it.detail}" }
                sessionState.value = DhuSession(
                    serial = serial,
                    localPort = 0,
                    phase = DhuSessionPhase.Failed,
                    message = detail,
                )
                return@withContext CommandResult.failure(detail)
            }
            // Single-session: tear down any previous Live DHU before replacing.
            tearDownLocked(clearConsole = false)

            val device = devices.listDevices().firstOrNull { it.serial == serial }
            val link = DhuCommandFactory.preferredLinkTransport(device?.transport, device?.kind)
            val executable = readiness.executablePath!!
            val adb = readiness.adbPath!!
            val autoDir = File(readiness.autoDir!!)
            val configFile = DhuDiscovery.writeConfigFile(configDir)
            val localPort = if (link == DhuLinkTransport.Adb) allocateLocalPort() else 0

            sessionState.value = DhuSession(
                serial = serial,
                localPort = localPort,
                phase = DhuSessionPhase.Starting,
                message = when (link) {
                    DhuLinkTransport.Usb -> "Launching DHU over USB…"
                    DhuLinkTransport.Adb -> "Forwarding ADB and launching DHU…"
                },
                startedAtMillis = nowMillis(),
            )

            if (link == DhuLinkTransport.Usb) {
                sessionState.value = sessionState.value?.copy(message = "Clearing stale USB accessory mode…")
                    ?: DhuSession(
                        serial = serial,
                        localPort = 0,
                        phase = DhuSessionPhase.Starting,
                        message = "Clearing stale USB accessory mode…",
                        startedAtMillis = nowMillis(),
                    )
                clearStaleUsbAccessory(adb, serial)
            }

            if (link == DhuLinkTransport.Adb) {
                val forwardCmd = DhuCommandFactory.buildAdbForward(adb, serial, localPort)
                val forward = runner.run(forwardCmd)
                if (!forward.isSuccess) {
                    val msg = forward.stderr.ifBlank { forward.stdout }.ifBlank { "ADB forward failed" }
                    sessionState.value = DhuSession(
                        serial = serial,
                        localPort = localPort,
                        phase = DhuSessionPhase.Failed,
                        message = msg,
                    )
                    return@withContext CommandResult.failure(msg)
                }
            }

            val launch = DhuCommandFactory.buildLaunchCommand(
                executable = executable,
                configPath = configFile.absolutePath,
                link = link,
                serial = serial,
                localAdbPort = localPort,
            )
            appendConsole("\$ ${launch.joinToString(" ")}")
            val process = try {
                ProcessBuilder(launch)
                    .directory(autoDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: Throwable) {
                if (link == DhuLinkTransport.Adb) removeForward(adb, serial, localPort)
                val msg = error.message ?: "Failed to launch DHU"
                sessionState.value = DhuSession(
                    serial = serial,
                    localPort = localPort,
                    phase = DhuSessionPhase.Failed,
                    message = msg,
                )
                return@withContext CommandResult.failure(msg)
            }

            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val session = ActiveSession(
                serial = serial,
                localPort = localPort,
                link = link,
                adbPath = adb,
                process = process,
                stdin = stdin,
                executable = executable,
                workingDir = autoDir,
                configPath = configFile.absolutePath,
            )
            active.set(session)
            outputJob = scope.launch { drainOutput(process) }

            // USB AOA renegotiation can take several seconds; ADB failures often show within ~2s.
            val earlyExit = awaitEarlyExit(
                process,
                timeoutMillis = if (link == DhuLinkTransport.Usb) 6_000L else 2_500L,
            )
            if (earlyExit != null) {
                tearDownLocked(clearConsole = false)
                sessionState.value = DhuSession(
                    serial = serial,
                    localPort = localPort,
                    phase = DhuSessionPhase.Failed,
                    message = earlyExit,
                    processAlive = false,
                    startedAtMillis = nowMillis(),
                )
                return@withContext CommandResult.failure(earlyExit)
            }

            // Do not park/resize/capture the native window — DHU stays interactive in its own
            // process window. Embedding + pointer pass-through was too laggy.
            val linkReady = awaitLinkReady(process, timeoutMillis = 20_000L)
            if (linkReady != null) {
                tearDownLocked(clearConsole = false)
                sessionState.value = DhuSession(
                    serial = serial,
                    localPort = localPort,
                    phase = DhuSessionPhase.Failed,
                    message = linkReady,
                    processAlive = false,
                    startedAtMillis = nowMillis(),
                )
                return@withContext CommandResult.failure(linkReady)
            }

            captureJob = scope.launch { watchProcess(session) }

            sessionState.value = DhuSession(
                serial = serial,
                localPort = localPort,
                phase = DhuSessionPhase.Running,
                message = when (link) {
                    DhuLinkTransport.Usb -> "DHU running over USB in its own window"
                    DhuLinkTransport.Adb -> "DHU running over ADB in its own window"
                },
                captureAvailable = false,
                processAlive = process.isAlive,
                startedAtMillis = nowMillis(),
            )

            CommandResult.success(
                when (link) {
                    DhuLinkTransport.Usb -> "DHU started over USB (separate window)"
                    DhuLinkTransport.Adb -> "DHU started on port $localPort (separate window)"
                },
            )
        }
    }

    override suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) {
            tearDownLocked(clearConsole = false)
            sessionState.value = null
            captureState.value = null
        }
    }

    override suspend fun sendConsoleCommand(command: String): CommandResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return@withContext CommandResult.failure("Empty command")
            val current = active.get()
                ?: return@withContext CommandResult.failure("DHU is not running")
            val history = DhuConsoleHistory.pushCommand(consoleState.value.history, trimmed)
            consoleState.value = consoleState.value.copy(history = history, historyIndex = -1)
            appendConsole("> $trimmed")
            runCatching {
                current.stdin.write(trimmed)
                current.stdin.newLine()
                current.stdin.flush()
                CommandResult.success()
            }.getOrElse {
                CommandResult.failure(it.message ?: "Failed to write DHU stdin")
            }
        }
    }

    override fun openHelp() {
        runCatching { Desktop.getDesktop().browse(URI(DhuFixedConfig.HelpUrl)) }
    }

    override fun openExternalTroubleshooting(): CommandResult {
        val current = active.get()
        val readiness = readinessState.value
        val serial = current?.serial ?: readiness.serial
            ?: return CommandResult.failure("No device serial for DHU")
        // Prefer focusing the already-running window.
        current?.let { session ->
            val window = session.window ?: host.findWindow(session.process.pid())
            if (window != null) {
                session.window = window
                if (host.focus(window)) {
                    return CommandResult.success("Focused the Desktop Head Unit window")
                }
            }
            // Process is still owned by Andy — launch a visible copy only when we still have a
            // valid USB link or an allocated ADB forward (do not invent --adb=5277).
            if (session.link == DhuLinkTransport.Adb && session.localPort <= 0) {
                return CommandResult.failure("No ADB forward for DHU. Use Retry or toggle Android Auto again.")
            }
            val args = DhuCommandFactory.buildLaunchCommand(
                executable = session.executable,
                configPath = session.configPath,
                link = session.link,
                serial = serial,
                localAdbPort = session.localPort,
            ).drop(1)
            return if (host.launchExternal(session.executable, args, session.workingDir)) {
                CommandResult.success("Launched Desktop Head Unit window")
            } else {
                CommandResult.failure("Could not launch Desktop Head Unit")
            }
        }
        return CommandResult.failure(
            "DHU is not running. Use Retry or toggle Android Auto on to start a managed session.",
        )
    }

    override fun copyDiagnostics(): String = buildString {
        append(readinessState.value.diagnosticsText())
        sessionState.value?.let { session ->
            appendLine()
            appendLine("session phase=${session.phase} port=${session.localPort} alive=${session.processAlive}")
            appendLine(session.message)
        }
        val lines = consoleState.value.lines.takeLast(80)
        if (lines.isNotEmpty()) {
            appendLine()
            appendLine("console tail:")
            lines.forEach { appendLine(it) }
        }
    }

    internal fun sendPointer(action: DhuPointerAction): Boolean = false

    internal fun sendKey(keyCode: Int, typedChar: Char? = null): Boolean = false

    internal fun focusHost(): Boolean {
        val window = active.get()?.window ?: return false
        return host.focus(window)
    }

    private suspend fun tearDownLocked(clearConsole: Boolean, cancelWatcher: Boolean = true) {
        if (cancelWatcher) {
            captureJob?.cancel()
            captureJob = null
        } else {
            // Called from the watcher itself — just drop the handle.
            captureJob = null
        }
        outputJob?.cancel()
        outputJob = null
        val current = active.getAndSet(null) ?: run {
            if (clearConsole) consoleState.value = DhuConsoleState()
            return
        }
        sessionState.value = current.asSession(DhuSessionPhase.Stopping, "Stopping DHU…")
        current.window?.let { host.teardown(it) }
        runCatching {
            current.stdin.close()
        }
        runCatching {
            current.process.destroy()
            if (!current.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                current.process.destroyForcibly()
            }
        }
        removeForward(current.adbPath, current.serial, current.localPort)
        if (current.link == DhuLinkTransport.Usb) {
            restoreUsbAdb(current.adbPath, current.serial)
        }
        appendConsole("DHU stopped")
        if (clearConsole) consoleState.value = DhuConsoleState()
    }

    private suspend fun clearStaleUsbAccessory(adb: String, serial: String) {
        val dump = runner.run(listOf(adb, "-s", serial, "shell", "dumpsys", "usb"), timeoutSeconds = 8)
        val text = dump.stdout + "\n" + dump.stderr
        if (!DhuCommandFactory.isUsbAccessoryMode(text)) {
            appendConsole("[andy] USB not in accessory mode; skipping reset")
            return
        }
        appendConsole("[andy] Stale USB ACCESSORY detected — resetting to adb")
        for (cmd in DhuCommandFactory.buildClearUsbAccessory(adb, serial)) {
            runner.run(cmd, timeoutSeconds = 8)
            delay(400)
        }
        waitForDeviceOnline(serial, timeoutMillis = 12_000L)
    }

    private suspend fun restoreUsbAdb(adb: String, serial: String) {
        runCatching {
            runner.run(DhuCommandFactory.buildRestoreUsbAdb(adb, serial), timeoutSeconds = 8)
            waitForDeviceOnline(serial, timeoutMillis = 8_000L)
        }
    }

    private suspend fun waitForDeviceOnline(serial: String, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val online = devices.listDevices().any {
                it.serial == serial && it.state == DeviceConnectionState.Online
            }
            if (online) return
            delay(250)
        }
    }

    private suspend fun removeForward(adb: String, serial: String, localPort: Int) {
        if (localPort <= 0) return
        runCatching {
            runner.run(DhuCommandFactory.buildAdbForwardRemove(adb, serial, localPort))
        }
    }

    /** Returns an error message if DHU dies during the initial connect window; null if still alive. */
    private suspend fun awaitEarlyExit(process: Process, timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                return interpretDhuExit(consoleState.value.lines, fallback = "DHU exited immediately after launch.")
            }
            val lines = consoleState.value.lines
            if (isLinkBroken(lines)) {
                delay(300)
                if (!process.isAlive) {
                    return interpretDhuExit(lines, fallback = "DHU disconnected from the device transport.")
                }
            }
            // Handshake completed early — stop waiting for a late crash.
            if (isLinkReady(lines)) return null
            delay(100)
        }
        return if (process.isAlive) null else interpretDhuExit(consoleState.value.lines, fallback = "DHU exited after launch.")
    }

    /**
     * Wait until GAL/TLS handshake logs appear before marking the session Running.
     * Returns an error message on failure/timeout; null when ready.
     */
    private suspend fun awaitLinkReady(process: Process, timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                return interpretDhuExit(consoleState.value.lines, fallback = "DHU exited during link setup.")
            }
            val lines = consoleState.value.lines
            if (isLinkBroken(lines)) {
                delay(300)
                return interpretDhuExit(lines, fallback = "DHU link broke during setup.")
            }
            if (isLinkReady(lines)) {
                return if (process.isAlive) null else interpretDhuExit(consoleState.value.lines, fallback = "DHU exited after handshake.")
            }
            delay(100)
        }
        return if (process.isAlive && isLinkReady(consoleState.value.lines)) {
            null
        } else if (!process.isAlive) {
            interpretDhuExit(consoleState.value.lines, fallback = "DHU exited during link setup.")
        } else {
            "DHU attached but the phone never finished the Automotive Link handshake. " +
                "Unlock the phone, accept any Android Auto prompts, unplug/replug USB, then Retry."
        }
    }

    private suspend fun watchProcess(session: ActiveSession) {
        while (scope.isActive && active.get() === session && session.process.isAlive) {
            delay(250L)
        }
        if (active.get() !== session) return
        val message = interpretDhuExit(consoleState.value.lines, fallback = "DHU process exited")
        mutex.withLock {
            if (active.get() !== session) return@withLock
            // Tear down forwards / USB accessory mode even when DHU exits on its own.
            tearDownLocked(clearConsole = false, cancelWatcher = false)
            sessionState.value = DhuSession(
                serial = session.serial,
                localPort = session.localPort,
                phase = DhuSessionPhase.Failed,
                message = message,
                captureAvailable = false,
                processAlive = false,
            )
        }
    }

    private suspend fun drainOutput(process: Process) {
        val reader = process.inputStream.bufferedReader()
        while (scope.isActive && process.isAlive) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            appendConsole(line)
        }
        // Drain anything left after exit so transport-failure lines are visible.
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                appendConsole(line)
            }
        }
    }

    private fun appendConsole(line: String) {
        val current = consoleState.value
        consoleState.value = current.copy(lines = DhuConsoleHistory.appendLine(current.lines, line))
    }

    private fun allocateLocalPort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * True/false when `/proc/net/tcp` conclusively shows (or omits) listen on [DhuFixedConfig.DevicePort];
     * null when the probe cannot run or parse (omit readiness check; rely on post-launch guidance).
     */
    private suspend fun probeHeadUnitServerListening(adb: String, serial: String): Boolean? {
        val result = runner.run(
            listOf(adb, "-s", serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6"),
            timeoutSeconds = 8,
        )
        val text = (result.stdout + "\n" + result.stderr).trim()
        if (text.isBlank()) return null
        // Empty success with no TCP table lines is treated as "not listening" only when we see
        // recognizable /proc/net/tcp formatting (sl local_address …).
        val looksLikeProcNet = text.contains(Regex("""\d+:\s+[0-9A-Fa-f]+:[0-9A-Fa-f]{4}\s+"""))
        if (!looksLikeProcNet && !result.isSuccess) return null
        if (!looksLikeProcNet) return null
        return DhuDiscovery.isDevicePortListening(text, DhuFixedConfig.DevicePort)
    }

    private data class ActiveSession(
        val serial: String,
        val localPort: Int,
        val link: DhuLinkTransport,
        val adbPath: String,
        val process: Process,
        val stdin: BufferedWriter,
        val executable: String,
        val workingDir: File,
        val configPath: String,
        var window: DhuWindowRef? = null,
    ) {
        fun asSession(
            phase: DhuSessionPhase,
            message: String,
            processAlive: Boolean = process.isAlive,
        ) = DhuSession(
            serial = serial,
            localPort = localPort,
            phase = phase,
            message = message,
            captureAvailable = false,
            processAlive = processAlive,
        )
    }

    companion object {
        internal const val FramingErrorRemediation =
            "Unlock the phone, accept any Android Auto prompts, unplug/replug USB, close any other " +
                "desktop-head-unit, then Retry. Andy waits for the Automotive Link handshake before marking DHU ready."

        internal fun isLinkReady(lines: List<String>): Boolean {
            val blob = lines.takeLast(60).joinToString("\n")
            if (isLinkBroken(lines)) return false
            return blob.contains("SSL negotiation finished successfully", ignoreCase = true) ||
                blob.contains("Verify returned: ok", ignoreCase = true)
        }

        internal fun isLinkBroken(lines: List<String>): Boolean {
            val blob = lines.takeLast(40).joinToString("\n")
            return blob.contains("failed to read from transport", ignoreCase = true) ||
                blob.contains("Failed to start Google Automotive Link", ignoreCase = true) ||
                blob.contains("Framing Error", ignoreCase = true) ||
                blob.contains("Out of sync with phone", ignoreCase = true) ||
                blob.contains("Unrecoverable error -251", ignoreCase = true) ||
                blob.contains("Google Automotive Link error -251", ignoreCase = true) ||
                blob.contains("Stream is broken", ignoreCase = true)
        }

        internal fun interpretDhuExit(lines: List<String>, fallback: String): String {
            val blob = lines.takeLast(30).joinToString("\n")
            val framingFail = isLinkBroken(lines) && (
                blob.contains("Framing Error", ignoreCase = true) ||
                    blob.contains("-251", ignoreCase = true) ||
                    blob.contains("Out of sync", ignoreCase = true) ||
                    blob.contains("Stream is broken", ignoreCase = true)
                )
            val transportFail =
                blob.contains("failed to read from transport", ignoreCase = true) ||
                    blob.contains("disconnect. exiting", ignoreCase = true) ||
                    blob.contains("Head Unit Server", ignoreCase = true) ||
                    blob.contains("Failed to start Google Automotive Link", ignoreCase = true)
            return when {
                framingFail -> buildString {
                    append("DHU USB/video link broke (framing / GAL -251). ")
                    append(FramingErrorRemediation)
                    if (blob.isNotBlank()) {
                        append("\n\n")
                        append(blob)
                    }
                }
                transportFail -> buildString {
                    append("DHU connected over ADB but the device closed the link. ")
                    append(DhuDiscovery.HeadUnitServerRemediation)
                    if (blob.isNotBlank()) {
                        append("\n\n")
                        append(blob)
                    }
                }
                blob.isNotBlank() -> "DHU exited:\n$blob"
                else -> fallback
            }
        }
    }
}
