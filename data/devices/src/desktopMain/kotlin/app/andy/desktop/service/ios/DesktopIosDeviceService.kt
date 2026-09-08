package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.IosDeveloperModeStatus
import app.andy.model.IosDeviceType
import app.andy.model.IosRuntime
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.iosNormalizedTouchCoordinates
import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import app.andy.service.IosTargetRegistry
import java.io.File
import kotlinx.coroutines.delay

class DesktopIosDeviceService(
    private val runner: CommandRunner,
    private val simulatorAppRunning: () -> Boolean = Companion::isSimulatorAppRunning,
    private val visibleSimulatorDeviceWindow: (String?) -> Boolean =
        Companion::hasVisibleSimulatorDeviceWindow,
    private val hideSimulator: () -> Unit = Companion::hideSimulatorApp,
) : IosDeviceService {
    override suspend fun listTargets(): List<IosTarget> {
        val sims = runCatching {
            val result = runner.run(listOf("xcrun", "simctl", "list", "devices", "-j"))
            if (!result.isSuccess) emptyList() else IosParsers.parseSimctlDevices(result.stdout)
        }.getOrDefault(emptyList())
        val physical = runCatching {
            val temp = File.createTempFile("andy-devicectl", ".json")
            val result = runner.run(listOf("xcrun", "devicectl", "list", "devices", "--json-output", temp.absolutePath))
            val output = if (result.isSuccess) temp.readText() else ""
            temp.delete()
            if (output.isBlank()) emptyList() else IosParsers.parseDevicectlDevices(output)
        }.getOrDefault(emptyList())
        val merged = (sims + physical).distinctBy { it.udid }
        // Simulator-vs-physical routing in the app/file/crash services reads the registry, so it
        // has to be refreshed by discovery itself rather than by whoever happens to call this.
        IosTargetRegistry.update(merged)
        return merged
    }

    override suspend fun boot(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "boot", udid), timeoutSeconds = 120)
        if (!result.isSuccess) return result
        return CommandResult.success("Booted $udid")
    }

    override suspend fun shutdown(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "shutdown", udid))
        return if (result.isSuccess) CommandResult.success("Shutdown $udid") else result
    }

    override suspend fun openInSimulatorApp(udid: String): CommandResult {
        val result = runner.run(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", udid))
        return if (result.isSuccess) CommandResult.success("Opened Simulator for $udid") else result
    }

    override fun hasVisibleSimulatorDeviceWindow(displayName: String?): Boolean =
        visibleSimulatorDeviceWindow(displayName)

    override fun hideSimulatorApp() = hideSimulator()

    override suspend fun prepareEmbeddedMirror(udid: String): CommandResult {
        // Launch Simulator.app *before* SimulatorKit IO attaches. Opening it mid-session races the
        // display pipeline and leaves Live black; HID still needs the process for Indigo.
        val alreadyRunning = simulatorAppRunning()
        val launch = ensureSimulatorAppRunning()
        if (!launch.isSuccess) return launch
        val deadline = System.nanoTime() + SIMULATOR_APP_WAIT_NANOS
        while (!simulatorAppRunning() && System.nanoTime() < deadline) {
            delay(100)
        }
        if (!simulatorAppRunning()) {
            return CommandResult.failure("Simulator.app did not start")
        }
        // After a pop-out handoff Simulator may still own visible windows; hide them so embedded
        // Live is the only compositor consumer again.
        hideSimulator()
        if (!alreadyRunning) {
            // Brief settle so CoreSimulator finishes wiring before we open SimDeviceIO.
            delay(SIMULATOR_APP_SETTLE_MILLIS)
        } else {
            delay(SIMULATOR_APP_HIDE_SETTLE_MILLIS)
        }
        return CommandResult.success("Simulator.app ready")
    }

    /**
     * Starts Simulator.app hidden and without activating it (`open -g -j`) so embedded Live can
     * inject HID after a headless [boot]. Safe to call when the app is already running.
     */
    private suspend fun ensureSimulatorAppRunning(): CommandResult {
        if (simulatorAppRunning()) {
            return CommandResult.success("Simulator.app already running")
        }
        // Avoid `--args -CurrentDeviceUDID`: launching with it can reset the booted device's
        // display just as Live tries to attach capture. Boot already selected the runtime via simctl.
        val result = runner.run(listOf("open", "-g", "-j", "-a", "Simulator"))
        return if (result.isSuccess) CommandResult.success("Simulator.app launching") else result
    }

    companion object {
        private const val SIMULATOR_APP_WAIT_NANOS = 15_000_000_000L
        private const val SIMULATOR_APP_SETTLE_MILLIS = 400L
        private const val SIMULATOR_APP_HIDE_SETTLE_MILLIS = 150L
        private const val TAP_HOLD_MILLIS = 170L
        private const val TOUCH_DOWN = 0
        private const val TOUCH_UP = 2
        private const val BUTTON_HOME = 0
        private const val BUTTON_POWER = 1

        internal fun isSimulatorAppRunning(): Boolean =
            runCatching {
                ProcessBuilder("pgrep", "-x", "Simulator")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)

        internal fun hasVisibleSimulatorDeviceWindow(displayName: String? = null): Boolean =
            NativeIosSimJni.hasVisibleDeviceWindow(displayName)

        internal fun hideSimulatorApp() = NativeIosSimJni.hideSimulatorApp()
    }

    override suspend fun iosSimAvailable(): Boolean = NativeIosSimJni.isAvailable()

    override suspend fun iosSimDiagnostic(): String = NativeIosSimJni.diagnostic()

    override suspend fun simctl(args: List<String>): CommandResult =
        runner.run(listOf("xcrun", "simctl") + args, timeoutSeconds = 60)

    override suspend fun listDeviceTypes(): List<IosDeviceType> {
        val result = runner.run(listOf("xcrun", "simctl", "list", "devicetypes", "-j"))
        return if (result.isSuccess) IosParsers.parseDeviceTypes(result.stdout) else emptyList()
    }

    override suspend fun listRuntimes(): List<IosRuntime> {
        val result = runner.run(listOf("xcrun", "simctl", "list", "runtimes", "-j"))
        return if (result.isSuccess) IosParsers.parseRuntimes(result.stdout) else emptyList()
    }

    override suspend fun createSimulator(name: String, deviceTypeId: String, runtimeId: String?): CommandResult {
        val command = buildList {
            add("xcrun"); add("simctl"); add("create"); add(name); add(deviceTypeId)
            if (runtimeId != null) add(runtimeId)
        }
        val result = runner.run(command, timeoutSeconds = 120)
        return if (result.isSuccess) CommandResult.success(result.stdout.trim()) else result
    }

    override suspend fun cloneSimulator(udid: String, newName: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "clone", udid, newName), timeoutSeconds = 120)
        return if (result.isSuccess) CommandResult.success(result.stdout.trim().ifBlank { "Cloned $udid as $newName" }) else result
    }

    override suspend fun eraseSimulator(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "erase", udid), timeoutSeconds = 120)
        return if (result.isSuccess) CommandResult.success("Erased $udid") else result
    }

    override suspend fun renameSimulator(udid: String, newName: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "rename", udid, newName))
        return if (result.isSuccess) CommandResult.success("Renamed $udid to $newName") else result
    }

    override suspend fun deleteSimulator(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "delete", udid))
        return if (result.isSuccess) CommandResult.success("Deleted $udid") else result
    }

    override suspend fun deleteUnavailableSimulators(): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "delete", "unavailable"))
        return if (result.isSuccess) CommandResult.success("Deleted unavailable simulators") else result
    }

    override suspend fun deleteUnusedRuntimes(notUsedSinceDays: Int): CommandResult {
        val result = runner.run(
            listOf("xcrun", "simctl", "runtime", "delete", "--notUsedSinceDays", notUsedSinceDays.toString()),
            timeoutSeconds = 180,
        )
        return if (result.isSuccess) CommandResult.success("Deleted runtimes unused for $notUsedSinceDays+ days") else result
    }

    override suspend fun captureScreenshot(udid: String): ByteArray? {
        val temp = File.createTempFile("andy-ios-studio-shot", ".png")
        return try {
            val result = runner.run(listOf("xcrun", "simctl", "io", udid, "screenshot", temp.absolutePath), timeoutSeconds = 30)
            if (result.isSuccess && temp.isFile && temp.length() > 0L) temp.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    override suspend fun push(udid: String, bundleId: String, payloadJson: String): CommandResult {
        val temp = File.createTempFile("andy-ios-push", ".apns")
        return try {
            temp.writeText(payloadJson)
            runner.run(listOf("xcrun", "simctl", "push", udid, bundleId, temp.absolutePath), timeoutSeconds = 30)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "Push failed")
        } finally {
            temp.delete()
        }
    }

    override suspend fun downloadPlatform(): CommandResult =
        runner.run(listOf("xcodebuild", "-downloadPlatform", "iOS"), timeoutSeconds = 3600)

    override suspend fun tap(udid: String, x: Int, y: Int): CommandResult {
        val ready = prepareHeadlessInput(udid)
        if (!ready.isSuccess) return ready
        val (nx, ny) = normalizedPoint(x, y)
        val down = NativeIosSimJni.sendTouch(TOUCH_DOWN, nx, ny)
        delay(TAP_HOLD_MILLIS)
        val up = NativeIosSimJni.sendTouch(TOUCH_UP, nx, ny)
        return if (down && up) CommandResult.success("Tapped $x,$y") else CommandResult.failure("Simulator HID did not accept tap")
    }

    override suspend fun swipe(
        udid: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ): CommandResult {
        val ready = prepareHeadlessInput(udid)
        if (!ready.isSuccess) return ready
        val (startNx, startNy) = normalizedPoint(startX, startY)
        val (endNx, endNy) = normalizedPoint(endX, endY)
        NativeIosSimJni.sendSwipe(startNx, startNy, endNx, endNy, (durationMillis / 16).coerceAtLeast(2))
        return CommandResult.success("Swiped $startX,$startY to $endX,$endY")
    }

    override suspend fun inputText(udid: String, text: String): CommandResult {
        if (text.isEmpty()) return CommandResult.success("Nothing to type")
        val ready = prepareHeadlessInput(udid)
        if (!ready.isSuccess) return ready
        NativeIosSimJni.sendText(text)
        return CommandResult.success("Typed ${text.length} characters")
    }

    override suspend fun pressButton(udid: String, button: String): CommandResult {
        val code = when (button.trim().lowercase()) {
            "home" -> BUTTON_HOME
            "power", "lock" -> BUTTON_POWER
            else -> return CommandResult.failure("Unsupported iOS button '$button'; expected home or power")
        }
        val ready = prepareHeadlessInput(udid)
        if (!ready.isSuccess) return ready
        NativeIosSimJni.sendButton(code)
        return CommandResult.success("Pressed $button")
    }

    /**
     * Warms the SimulatorKit HID channel for callers with no Live mirror session. Live normally
     * does this during connect; headless callers (MCP, Actions) have nothing attached, so
     * Simulator.app has to be started and SimDeviceIO opened here first. An already-warm channel
     * short-circuits — reconnecting would tear down an active Live session's capture.
     */
    private suspend fun prepareHeadlessInput(udid: String): CommandResult {
        val target = IosTargetRegistry.target(udid)
        if (target != null && target.kind != IosTargetKind.Simulator) {
            return CommandResult.failure("Input is not supported on physical iOS devices")
        }
        if (NativeIosSimJni.ensureInputReady()) return CommandResult.success("Simulator input ready")
        val prepared = prepareEmbeddedMirror(udid)
        if (!prepared.isSuccess) return prepared
        NativeIosSimJni.connect(udid)
        return if (NativeIosSimJni.ensureInputReady()) {
            CommandResult.success("Simulator input ready")
        } else {
            CommandResult.failure("Simulator input is unavailable for $udid")
        }
    }

    /** Device points to the 0..1 space SimulatorKit's Indigo HID events expect. */
    private fun normalizedPoint(x: Int, y: Int): Pair<Float, Float> {
        val size = NativeIosSimJni.contentSizePoints()
        return iosNormalizedTouchCoordinates(x, y, size.getOrElse(0) { 390 }, size.getOrElse(1) { 844 })
    }

    override suspend fun developerModeStatus(udid: String): IosDeveloperModeStatus? {
        val temp = File.createTempFile("andy-devicectl-info", ".json")
        return try {
            val result = runner.run(
                listOf("xcrun", "devicectl", "device", "info", "details", "--device", udid, "--json-output", temp.absolutePath),
                timeoutSeconds = 30,
            )
            if (!result.isSuccess) return null
            val output = temp.readText()
            if (output.isBlank()) null else IosParsers.parseDeveloperModeStatus(output)
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }
}

internal fun refreshedIosTargets(service: IosDeviceService): List<IosTarget> {
    return runCatching { kotlinx.coroutines.runBlocking { service.listTargets() } }.getOrDefault(emptyList())
}

internal fun IosTarget.withBootedState(): IosTarget =
    if (state == IosTargetState.Shutdown) copy(state = IosTargetState.Booted) else this
