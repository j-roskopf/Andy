package app.andy.desktop.service.tracing

import app.andy.desktop.service.MockAndroidDeviceEnvironment
import app.andy.model.TracePhase
import app.andy.service.CommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTracingServiceTest {
    @Test
    fun happyPathDurationRecordingWalksPhasesAndWritesSidecar() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.propValues["ro.build.version.sdk"] = "35"
        env.propValues["init.svc.traced"] = "running"
        env.perfettoAliveChecksRemaining = 1
        val root = createTempDirectory("andy-traces").toFile()
        val configs = File(root, "configs").also { it.mkdirs() }
        val phases = mutableListOf<TracePhase>()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = configs,
            perfettoLauncher = { command, stdin ->
                env.commands += command
                env.perfettoConfigs += stdin
                env.perfettoBackgroundResult
            },
            clock = { 1_700_000_000_000L },
        )

        val start = service.start(
            serial = "emulator-5554",
            configTextProto = "buffers { size_kb: 1024 }\nduration_ms: 1000\n",
            name = "default",
            presetId = "default",
        )
        assertTrue(start.isSuccess, start.stderr)
        assertTrue(env.ran("perfetto", "-c", "-", "--txt", "--background"))
        assertEquals(1, env.perfettoConfigs.size)
        phases += service.status.value.phase

        withTimeout(10_000) {
            while (service.status.value.phase != TracePhase.Done) {
                phases += service.status.value.phase
                delay(50)
            }
        }
        phases += TracePhase.Done

        assertTrue(phases.contains(TracePhase.Starting) || phases.contains(TracePhase.Recording))
        assertTrue(phases.contains(TracePhase.Pulling) || service.status.value.phase == TracePhase.Done)
        assertEquals(TracePhase.Done, service.status.value.phase)
        assertTrue(env.ran("test", "-d"), "liveness must use /proc (not kill -0)")
        val id = start.stdout.trim()
        assertTrue(File(root, "$id.perfetto-trace").isFile)
        assertTrue(File(root, "$id.json").isFile)
        assertTrue(env.ran("rm"))
    }

    @Test
    fun manualStopSendsTerm() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.perfettoAliveChecksRemaining = 100
        val root = createTempDirectory("andy-traces-manual").toFile()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = File(root, "configs").also { it.mkdirs() },
            perfettoLauncher = { command, stdin ->
                env.commands += command
                env.perfettoConfigs += stdin
                CommandResult.success("4242")
            },
        )
        assertTrue(service.start("emulator-5554", "buffers { size_kb: 1024 }\nwrite_into_file: true\n", "manual", null).isSuccess)
        delay(200)
        assertEquals(TracePhase.Recording, service.status.value.phase)
        val stop = service.stop()
        assertTrue(stop.isSuccess, stop.stderr)
        assertTrue(env.ran("kill", "-TERM", "4242"))
        withTimeout(10_000) {
            while (service.status.value.phase !in setOf(TracePhase.Done, TracePhase.Error)) delay(50)
        }
        assertEquals(TracePhase.Done, service.status.value.phase)
    }

    @Test
    fun api24ErrorsWithoutLaunch() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.propValues["ro.build.version.sdk"] = "24"
        var launched = false
        val root = createTempDirectory("andy-traces-api24").toFile()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = File(root, "configs").also { it.mkdirs() },
            perfettoLauncher = { _, _ ->
                launched = true
                CommandResult.success("1")
            },
        )
        val result = service.start("emulator-5554", "buffers {}\n", "x", null)
        assertFalse(result.isSuccess)
        assertFalse(launched)
        assertEquals(TracePhase.Error, service.status.value.phase)
        assertTrue(result.stderr.contains("Android 9+"))
    }

    @Test
    fun api28IssuesSetprop() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.propValues["ro.build.version.sdk"] = "28"
        env.propValues["persist.traced.enable"] = "0"
        env.propValues["init.svc.traced"] = "running"
        env.perfettoAliveChecksRemaining = 0
        val root = createTempDirectory("andy-traces-api28").toFile()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = File(root, "configs").also { it.mkdirs() },
            perfettoLauncher = { command, _ ->
                env.commands += command
                CommandResult.success("99")
            },
        )
        assertTrue(service.start("emulator-5554", "buffers { size_kb: 1024 }\nduration_ms: 500\n", "x", null).isSuccess)
        assertTrue(env.ran("setprop", "persist.traced.enable", "1"))
        withTimeout(15_000) {
            while (service.status.value.phase !in setOf(TracePhase.Done, TracePhase.Error)) delay(50)
        }
    }

    @Test
    fun nonNumericPidErrors() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val root = createTempDirectory("andy-traces-pid").toFile()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = File(root, "configs").also { it.mkdirs() },
            perfettoLauncher = { _, _ -> CommandResult.success("not-a-pid") },
        )
        val result = service.start("emulator-5554", "buffers {}\n", "x", null)
        assertFalse(result.isSuccess)
        assertEquals(TracePhase.Error, service.status.value.phase)
        assertTrue(result.stderr.contains("PID", ignoreCase = true))
    }

    @Test
    fun emptyPulledFileErrorsWithoutRm() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        env.pullWritesNonEmptyFile = false
        env.perfettoAliveChecksRemaining = 0
        val root = createTempDirectory("andy-traces-empty").toFile()
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = File(root, "configs").also { it.mkdirs() },
            perfettoLauncher = { command, _ ->
                env.commands += command
                CommandResult.success("55")
            },
            pullRetryAttempts = 1,
        )
        assertTrue(service.start("emulator-5554", "buffers { size_kb: 1024 }\nduration_ms: 500\n", "x", null).isSuccess)
        withTimeout(10_000) {
            while (service.status.value.phase != TracePhase.Error) delay(50)
        }
        assertTrue(service.status.value.message.orEmpty().contains("Retry pull"))
        assertFalse(env.ran("rm"))
    }

    @Test
    fun userConfigImportSaveListDeleteRoundTrip() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val root = createTempDirectory("andy-traces-configs").toFile()
        val configs = File(root, "configs").also { it.mkdirs() }
        val service = DesktopTracingService(
            runner = env.runner,
            devices = env.devices,
            files = env.services().files,
            tracesDir = root,
            configsDir = configs,
        )
        val source = File(root, "imported.textproto").apply { writeText("buffers { size_kb: 1 }\n") }
        assertTrue(service.importConfig(source.absolutePath).isSuccess)
        assertTrue(service.saveUserConfig("mine", "buffers { size_kb: 2 }\n").isSuccess)
        val listed = service.listUserConfigs()
        assertTrue(listed.any { it.name == "imported" })
        assertTrue(listed.any { it.name == "mine" })
        assertEquals("buffers { size_kb: 2 }\n", service.loadUserConfig("mine"))
        assertTrue(service.deleteUserConfig("mine"))
        assertEquals(null, service.loadUserConfig("mine"))
    }
}
