package app.andy.desktop.service.tracing

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.model.DeviceConnectionState
import app.andy.model.TracePhase
import app.andy.model.TraceRecording
import app.andy.model.TraceRecordingStatus
import app.andy.model.TraceUserConfig
import app.andy.service.CommandResult
import app.andy.service.FileService
import app.andy.service.TracingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopTracingService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val files: FileService,
    private val tracesDir: File = File(System.getProperty("user.home"), ".andy/traces"),
    private val configsDir: File = File(tracesDir, "configs"),
    private val perfettoLauncher: suspend (command: List<String>, stdin: String) -> CommandResult = { command, stdin ->
        runProcessWithStdin(command, stdin)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pullRetryAttempts: Int = 6,
) : TracingService {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutableStatus = MutableStateFlow(TraceRecordingStatus())
    private val mutableRecordings = MutableStateFlow<List<TraceRecording>>(emptyList())
    private val active = AtomicReference<ActiveRecording?>(null)
    private var pollerJob: Job? = null

    override val status: StateFlow<TraceRecordingStatus> = mutableStatus
    override val recordings: StateFlow<List<TraceRecording>> = mutableRecordings

    init {
        tracesDir.mkdirs()
        configsDir.mkdirs()
        scope.launch { refreshRecordings() }
    }

    override suspend fun checkSupport(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        val sdk = getprop(adb, serial, "ro.build.version.sdk").toIntOrNull()
            ?: return@withContext CommandResult.failure("Could not read Android API level")
        if (sdk < 28) {
            return@withContext CommandResult.failure("Perfetto tracing requires Android 9+ (API 28). This device is API $sdk.")
        }
        if (sdk == 28) {
            ensureTracedEnabled(adb, serial)
        }
        val traced = waitForTraced(adb, serial)
        if (!traced) {
            return@withContext CommandResult.failure("traced is not running. Enable Perfetto on the device and try again.")
        }
        CommandResult.success("API $sdk · traced running")
    }

    override suspend fun start(
        serial: String,
        configTextProto: String,
        name: String,
        presetId: String?,
    ): CommandResult = withContext(Dispatchers.IO) {
        // Failed pulls leave [active] populated so Retry pull can recover the remote file.
        // Do not replace that handle with a new recording.
        if (active.get() != null) {
            return@withContext CommandResult.failure("Finish or retry the current recording first")
        }
        val phase = mutableStatus.value.phase
        if (phase !in setOf(TracePhase.Idle, TracePhase.Done, TracePhase.Error)) {
            return@withContext CommandResult.failure("Recording already in progress")
        }
        val adb = devices.adbPath() ?: return@withContext fail("ADB not found")
        val support = checkSupport(serial)
        if (!support.isSuccess) return@withContext fail(support.stderr.ifBlank { support.stdout })

        val id = "andy-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        val remotePath = "/data/misc/perfetto-traces/$id.perfetto-trace"
        val displayName = name.ifBlank { id }
        mutableStatus.value = TraceRecordingStatus(
            phase = TracePhase.Starting,
            serial = serial,
            traceName = displayName,
            startedAtMillis = clock(),
            message = "Starting…",
        )

        val launch = perfettoLauncher(
            listOf(adb, "-s", serial, "shell", "perfetto", "-c", "-", "--txt", "--background", "-o", remotePath),
            configTextProto,
        )
        if (!launch.isSuccess) {
            return@withContext fail(launch.stderr.ifBlank { launch.stdout }.ifBlank { "perfetto failed to start" })
        }
        val pid = launch.stdout.trim().lineSequence().map { it.trim() }.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
        if (pid == null) {
            return@withContext fail("perfetto did not return a PID (got: ${launch.stdout.trim()})")
        }

        val deviceLabel = devices.listDevices().firstOrNull { it.serial == serial }?.displayName
        val durationHint = Regex("""duration_ms:\s*(\d+)""").find(configTextProto)?.groupValues?.get(1)?.toLongOrNull()
        val recording = ActiveRecording(
            id = id,
            name = displayName,
            serial = serial,
            deviceLabel = deviceLabel,
            presetId = presetId,
            remotePath = remotePath,
            pid = pid,
            startedAtMillis = clock(),
            durationMsHint = durationHint,
            shutdownHook = Thread {
                runCatching {
                    ProcessBuilder(adb, "-s", serial, "shell", "kill", "-TERM", pid.toString()).start().waitFor(5, TimeUnit.SECONDS)
                }
            },
        )
        Runtime.getRuntime().addShutdownHook(recording.shutdownHook)
        active.set(recording)
        mutableStatus.value = TraceRecordingStatus(
            phase = TracePhase.Recording,
            serial = serial,
            traceName = displayName,
            startedAtMillis = recording.startedAtMillis,
            message = "RECORDING",
        )
        startPoller(adb, recording)
        CommandResult.success(id)
    }

    override suspend fun stop(): CommandResult = withContext(Dispatchers.IO) {
        val recording = active.get() ?: return@withContext CommandResult.failure("No active recording")
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        mutableStatus.value = mutableStatus.value.copy(phase = TracePhase.Stopping, message = "Stopping…")
        pollerJob?.cancel()
        val term = runner.run(listOf(adb, "-s", recording.serial, "shell", "kill", "-TERM", recording.pid.toString()), 10)
        var exited = false
        repeat(10) {
            if (!isPidAlive(adb, recording.serial, recording.pid)) {
                exited = true
                return@repeat
            }
            delay(1000)
        }
        if (!exited) {
            runner.run(listOf(adb, "-s", recording.serial, "shell", "kill", "-KILL", recording.pid.toString()), 10)
            delay(500)
            if (isPidAlive(adb, recording.serial, recording.pid)) {
                return@withContext fail("Could not stop perfetto (pid ${recording.pid})")
            }
        }
        if (!term.isSuccess && term.stderr.isNotBlank()) {
            // TERM can race with natural exit; still attempt pull.
        }
        pullTrace(adb, recording)
        CommandResult.success(recording.id)
    }

    override suspend fun refreshRecordings() = withContext(Dispatchers.IO) {
        tracesDir.mkdirs()
        val fromSidecars = tracesDir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<TraceRecording>(file.readText()) }.getOrNull()
            }
        val knownIds = fromSidecars.map { it.id }.toSet()
        val synthesized = tracesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".perfetto-trace") && f.nameWithoutExtension !in knownIds
        }.orEmpty().map { file ->
            val id = file.nameWithoutExtension
            TraceRecording(
                id = id,
                name = id,
                serial = "unknown",
                recordedAtMillis = file.lastModified(),
                sizeBytes = file.length(),
                localPath = file.absolutePath,
            )
        }
        mutableRecordings.value = (fromSidecars + synthesized).sortedByDescending { it.recordedAtMillis }
    }

    override suspend fun deleteRecording(id: String): Boolean = withContext(Dispatchers.IO) {
        val recording = mutableRecordings.value.firstOrNull { it.id == id }
        val trace = recording?.let { File(it.localPath) } ?: File(tracesDir, "$id.perfetto-trace")
        val sidecar = File(tracesDir, "$id.json")
        val deleted = (if (trace.exists()) trace.delete() else true) && (if (sidecar.exists()) sidecar.delete() else true)
        refreshRecordings()
        deleted
    }

    override suspend fun revealRecording(id: String): CommandResult = withContext(Dispatchers.IO) {
        val recording = mutableRecordings.value.firstOrNull { it.id == id }
            ?: return@withContext CommandResult.failure("Recording not found")
        val file = File(recording.localPath)
        if (!file.exists()) return@withContext CommandResult.failure("Trace file missing: ${file.absolutePath}")
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(file)
            } else {
                Desktop.getDesktop().open(file.parentFile)
            }
            CommandResult.success(file.absolutePath)
        }.getOrElse { CommandResult.failure(it.message ?: "Reveal failed") }
    }

    override suspend fun importConfig(sourcePath: String): CommandResult = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.isFile) return@withContext CommandResult.failure("Config file not found")
        configsDir.mkdirs()
        val target = uniqueConfigFile(source.nameWithoutExtension)
        source.copyTo(target, overwrite = false)
        CommandResult.success(target.absolutePath)
    }

    /** Opens a file chooser on the EDT and imports the selected textproto. */
    suspend fun importConfigInteractive(): CommandResult = withContext(Dispatchers.IO) {
        val selected = chooseOpenFile() ?: return@withContext CommandResult.failure("Import canceled")
        importConfig(selected.absolutePath)
    }

    override suspend fun listUserConfigs(): List<TraceUserConfig> = withContext(Dispatchers.IO) {
        configsDir.mkdirs()
        configsDir.listFiles { f -> f.isFile && (f.extension.equals("textproto", true) || f.extension.equals("pbtxt", true) || f.extension.equals("cfg", true)) }
            .orEmpty()
            .sortedBy { it.name.lowercase(Locale.US) }
            .map { TraceUserConfig(id = it.nameWithoutExtension, name = it.nameWithoutExtension, path = it.absolutePath) }
    }

    override suspend fun loadUserConfig(id: String): String? = withContext(Dispatchers.IO) {
        resolveConfigFile(id)?.takeIf { it.isFile }?.readText()
    }

    override suspend fun saveUserConfig(name: String, content: String): CommandResult = withContext(Dispatchers.IO) {
        val safe = name.trim().ifBlank { return@withContext CommandResult.failure("Name required") }
            .replace(Regex("""[^\w.\- ]+"""), "_")
        configsDir.mkdirs()
        val target = File(configsDir, "$safe.textproto")
        target.writeText(content)
        CommandResult.success(target.absolutePath)
    }

    override suspend fun deleteUserConfig(id: String): Boolean = withContext(Dispatchers.IO) {
        resolveConfigFile(id)?.delete() == true
    }

    /** Retries pulling the last failed remote trace when status message indicates pull failure. */
    override suspend fun retryPull(): CommandResult = withContext(Dispatchers.IO) {
        val recording = active.get() ?: return@withContext CommandResult.failure("Nothing to pull")
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        pullTrace(adb, recording)
        CommandResult.success(recording.id)
    }

    private fun startPoller(adb: String, recording: ActiveRecording) {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            val deadline = recording.durationMsHint?.let { recording.startedAtMillis + it + 20_000 }
            while (isActive) {
                delay(1000)
                val current = active.get() ?: break
                if (current.id != recording.id) break

                val devicesOnline = runCatching { devices.listDevices() }.getOrDefault(emptyList())
                if (devicesOnline.none { it.serial == recording.serial && it.state == DeviceConnectionState.Online }) {
                    fail("Device disconnected during recording. On-device file may still exist at ${recording.remotePath}")
                    break
                }

                val alive = isPidAlive(adb, recording.serial, recording.pid)
                val elapsed = clock() - recording.startedAtMillis
                mutableStatus.value = mutableStatus.value.copy(
                    phase = TracePhase.Recording,
                    durationMs = elapsed,
                    message = "RECORDING ${formatElapsed(elapsed)}",
                )
                if (!alive) {
                    pullTrace(adb, recording)
                    break
                }
                if (deadline != null && clock() >= deadline) {
                    runner.run(listOf(adb, "-s", recording.serial, "shell", "kill", "-TERM", recording.pid.toString()), 10)
                    delay(1000)
                    pullTrace(adb, recording)
                    break
                }
            }
        }
    }

    /**
     * Prefer `/proc/<pid>` over `kill -0`. On recent Android user builds, `kill -0`
     * returns "Permission denied" for a live perfetto process (exit ≠ 0), which made
     * the poller treat the session as finished and pull a still-empty trace file.
     */
    private suspend fun isPidAlive(adb: String, serial: String, pid: Int): Boolean {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "test", "-d", "/proc/$pid"), 5)
        return result.isSuccess
    }

    private suspend fun pullTrace(adb: String, recording: ActiveRecording) {
        removeShutdownHook(recording)
        mutableStatus.value = mutableStatus.value.copy(phase = TracePhase.Pulling, message = "Pulling trace…")
        tracesDir.mkdirs()
        val local = File(tracesDir, "${recording.id}.perfetto-trace")
        // Perfetto may still be finalizing the file briefly after the process exits.
        var pull = CommandResult.failure("not attempted")
        val attempts = pullRetryAttempts.coerceAtLeast(1)
        for (attempt in 0 until attempts) {
            if (local.exists()) local.delete()
            pull = files.pull(recording.serial, recording.remotePath, local.absolutePath)
            if (pull.isSuccess && local.isFile && local.length() > 0L) break
            if (attempt < attempts - 1) delay(1000)
        }
        if (!pull.isSuccess || !local.isFile || local.length() <= 0L) {
            mutableStatus.value = TraceRecordingStatus(
                phase = TracePhase.Error,
                serial = recording.serial,
                traceName = recording.name,
                startedAtMillis = recording.startedAtMillis,
                durationMs = clock() - recording.startedAtMillis,
                message = "Pull failed. Remote file kept at ${recording.remotePath}. Retry pull.",
                lastTraceId = recording.id,
            )
            return
        }
        runner.run(listOf(adb, "-s", recording.serial, "shell", "rm", recording.remotePath), 15)
        val duration = clock() - recording.startedAtMillis
        val meta = TraceRecording(
            id = recording.id,
            name = recording.name,
            serial = recording.serial,
            deviceLabel = recording.deviceLabel,
            presetId = recording.presetId,
            recordedAtMillis = recording.startedAtMillis,
            durationMs = duration,
            sizeBytes = local.length(),
            localPath = local.absolutePath,
        )
        File(tracesDir, "${recording.id}.json").writeText(json.encodeToString(meta))
        active.set(null)
        refreshRecordings()
        mutableStatus.value = TraceRecordingStatus(
            phase = TracePhase.Done,
            serial = recording.serial,
            traceName = recording.name,
            startedAtMillis = recording.startedAtMillis,
            durationMs = duration,
            message = "Done",
            lastTraceId = recording.id,
        )
    }

    private suspend fun ensureTracedEnabled(adb: String, serial: String) {
        val enabled = getprop(adb, serial, "persist.traced.enable").trim()
        if (enabled != "1") {
            runner.run(listOf(adb, "-s", serial, "shell", "setprop", "persist.traced.enable", "1"), 10)
            delay(1500)
        }
    }

    private suspend fun waitForTraced(adb: String, serial: String): Boolean {
        repeat(4) {
            if (getprop(adb, serial, "init.svc.traced").trim() == "running") return true
            delay(500)
        }
        return getprop(adb, serial, "init.svc.traced").trim() == "running"
    }

    private suspend fun getprop(adb: String, serial: String, key: String): String {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "getprop", key), 8)
        return result.stdout.trim()
    }

    private fun fail(message: String): CommandResult {
        val current = active.get()
        if (current != null) removeShutdownHook(current)
        pollerJob?.cancel()
        active.set(null)
        mutableStatus.value = TraceRecordingStatus(
            phase = TracePhase.Error,
            serial = current?.serial ?: mutableStatus.value.serial,
            traceName = current?.name ?: mutableStatus.value.traceName,
            startedAtMillis = current?.startedAtMillis ?: mutableStatus.value.startedAtMillis,
            durationMs = current?.let { clock() - it.startedAtMillis },
            message = message,
            lastTraceId = current?.id,
        )
        return CommandResult.failure(message)
    }

    private fun removeShutdownHook(recording: ActiveRecording) {
        runCatching { Runtime.getRuntime().removeShutdownHook(recording.shutdownHook) }
    }

    private fun resolveConfigFile(id: String): File? {
        if (id.isBlank() || id.contains("..") || id.contains('/') || id.contains('\\')) return null
        val candidates = listOf(
            File(configsDir, "$id.textproto"),
            File(configsDir, "$id.pbtxt"),
            File(configsDir, "$id.cfg"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: configsDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == id }
    }

    private fun uniqueConfigFile(baseName: String): File {
        val safe = baseName.replace(Regex("""[^\w.\- ]+"""), "_").ifBlank { "config" }
        var candidate = File(configsDir, "$safe.textproto")
        var index = 2
        while (candidate.exists()) {
            candidate = File(configsDir, "$safe-$index.textproto")
            index++
        }
        return candidate
    }

    private fun chooseOpenFile(): File? {
        var selected: File? = null
        val task = Runnable {
            val chooser = JFileChooser().apply {
                dialogTitle = "Import Perfetto config"
                fileFilter = FileNameExtensionFilter("Perfetto configs", "textproto", "pbtxt", "cfg", "txt")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile
            }
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        return selected
    }

    private data class ActiveRecording(
        val id: String,
        val name: String,
        val serial: String,
        val deviceLabel: String?,
        val presetId: String?,
        val remotePath: String,
        val pid: Int,
        val startedAtMillis: Long,
        val durationMsHint: Long?,
        val shutdownHook: Thread,
    )

    companion object {
        internal fun runProcessWithStdin(command: List<String>, stdin: String, timeoutSeconds: Long = 20): CommandResult {
            return try {
                val process = ProcessBuilder(command).redirectErrorStream(false).start()
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdin)
                    writer.flush()
                }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return CommandResult.failure("perfetto timed out after ${timeoutSeconds}s")
                }
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                CommandResult(process.exitValue(), stdout, stderr)
            } catch (error: Exception) {
                CommandResult.failure(error.message ?: "Failed to launch perfetto")
            }
        }

        private fun formatElapsed(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
    }
}
