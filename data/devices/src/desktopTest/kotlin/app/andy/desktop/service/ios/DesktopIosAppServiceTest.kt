package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.IosTargetRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val PhysicalUdid = "00008140-00026112260B001C"

class DesktopIosAppServiceTest {
    @AfterTest
    fun clearRegistry() {
        IosTargetRegistry.update(emptyList())
    }

    private fun registerPhysicalDevice() {
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = PhysicalUdid,
                    displayName = "iPhone 16 Pro",
                    kind = IosTargetKind.Physical,
                    state = IosTargetState.Unknown,
                ),
            ),
        )
    }

    /** Mimics devicectl: writes the JSON payload to the `--json-output` path, not stdout. */
    private fun devicectlRunner(
        commands: MutableList<List<String>>,
        payloads: (List<String>) -> Pair<CommandResult, String?>,
    ) = CommandRunner { command, _ ->
        commands += command
        val (result, json) = payloads(command)
        val outputIndex = command.indexOf("--json-output")
        if (json != null && outputIndex >= 0) File(command[outputIndex + 1]).writeText(json)
        result
    }

    private val sampleListAppsJson = """
        {
          "com.apple.mobilesafari": {
            "ApplicationType": "System",
            "CFBundleDisplayName": "Safari",
            "CFBundleName": "MobileSafari",
            "CFBundleShortVersionString": "26.0",
            "CFBundleVersion": "20618"
          },
          "com.example.myapp": {
            "ApplicationType": "User",
            "CFBundleDisplayName": "My App",
            "CFBundleShortVersionString": "1.2.3",
            "CFBundleVersion": "45"
          }
        }
    """.trimIndent()

    @Test
    fun parseListAppsJsonSortsUserAppsBeforeSystemApps() {
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })

        val apps = service.parseListAppsJson(sampleListAppsJson)

        assertEquals(2, apps.size)
        assertEquals("com.example.myapp", apps.first().packageName)
        assertFalse(apps.first().system)
        assertEquals("My App", apps.first().label)
        assertEquals("1.2.3", apps.first().versionName)
        assertEquals("45", apps.first().versionCode)

        val safari = apps.last()
        assertEquals("com.apple.mobilesafari", safari.packageName)
        assertTrue(safari.system)
        assertEquals("Safari", safari.label)
    }

    @Test
    fun parseListAppsJsonFallsBackToBundleNameThenIdentifierSuffix() {
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })
        val output = """{"com.example.nolabel": {"CFBundleName": "Fallback Name"}}"""

        val apps = service.parseListAppsJson(output)

        assertEquals("Fallback Name", apps.single().label)
    }

    @Test
    fun parseListAppsJsonReturnsEmptyForMalformedInput() {
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })
        assertEquals(emptyList(), service.parseListAppsJson("not json"))
    }

    @Test
    fun listAppsRunsSimctlListappsThenPlutilConvert() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            when (command.firstOrNull()) {
                "xcrun" -> CommandResult.success("(plist-openstep)")
                "plutil" -> CommandResult.success(sampleListAppsJson)
                else -> CommandResult.failure("unexpected command")
            }
        }
        val service = DesktopIosAppService(runner)

        val apps = service.listApps("BOOTED-UDID")

        assertEquals(2, apps.size)
        assertEquals(listOf("xcrun", "simctl", "listapps", "BOOTED-UDID"), commands.first())
        assertEquals("plutil", commands[1].first())
        assertTrue(commands[1].contains("json"))
    }

    @Test
    fun launchStopUninstallResetPermissionsRunExpectedSimctlCommands() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        val service = DesktopIosAppService(runner)
        val udid = "BOOTED-UDID"
        val bundleId = "com.example.myapp"

        service.launch(udid, bundleId)
        service.stop(udid, bundleId)
        service.uninstall(udid, bundleId)
        service.resetPermissions(udid, bundleId)

        assertEquals(
            listOf(
                listOf("xcrun", "simctl", "launch", udid, bundleId),
                listOf("xcrun", "simctl", "terminate", udid, bundleId),
                listOf("xcrun", "simctl", "uninstall", udid, bundleId),
                listOf("xcrun", "simctl", "privacy", udid, "reset", "all", bundleId),
            ),
            commands,
        )
    }

    @Test
    fun clearDataIsUnsupportedOnIos() = runBlocking {
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })
        val result = service.clearData("udid", "com.example.myapp")
        assertFalse(result.isSuccess)
    }

    @Test
    fun appDataContainerReturnsTrimmedPathOnSuccess() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.success("/path/to/container\n") }
        val service = DesktopIosAppService(runner)
        assertEquals("/path/to/container", service.appDataContainer("udid", "com.example.myapp"))
    }

    @Test
    fun appDataContainerReturnsNullOnFailure() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("not installed") }
        val service = DesktopIosAppService(runner)
        assertEquals(null, service.appDataContainer("udid", "com.example.myapp"))
    }

    @Test
    fun listAppsUsesDevicectlForRegisteredPhysicalDevices() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) {
            CommandResult.success() to """
                {"result":{"apps":[
                  {"bundleIdentifier":"com.example.myapp","name":"My App","version":"1.2.3","bundleVersion":"45"},
                  {"bundleIdentifier":"com.apple.Maps","name":"Maps","defaultApp":true}
                ]}}
            """.trimIndent()
        }
        val service = DesktopIosAppService(runner)

        val apps = service.listApps(PhysicalUdid)

        assertEquals(listOf("com.example.myapp", "com.apple.Maps"), apps.map { it.packageName })
        assertTrue(apps.last().system)
        val command = commands.single()
        assertEquals(
            listOf("xcrun", "devicectl", "device", "info", "apps", "--device", PhysicalUdid, "--include-all-apps"),
            command.dropLast(2),
        )
        assertEquals("--json-output", command[command.size - 2])
        assertTrue(commands.none { it.contains("simctl") })
    }

    @Test
    fun listAppsThrowsDeveloperModeErrorForPhysicalDevices() = runBlocking {
        registerPhysicalDevice()
        val runner = devicectlRunner(mutableListOf()) {
            CommandResult.failure("CoreDeviceError 10005") to """
                {"error":{"code":10005,"userInfo":{"NSLocalizedDescription":{"string":"Developer Mode is disabled."}}}}
            """.trimIndent()
        }
        val service = DesktopIosAppService(runner)
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            service.listApps(PhysicalUdid)
        }
        assertTrue(error.message!!.contains("Developer Mode is disabled."), error.message!!)
    }

    @Test
    fun launchUsesDevicectlProcessLaunchForPhysicalDevices() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { CommandResult.success() to """{"info":{"outcome":"success"}}""" }
        val service = DesktopIosAppService(runner)

        val result = service.launch(PhysicalUdid, "com.example.myapp")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "xcrun", "devicectl", "device", "process", "launch",
                "--device", PhysicalUdid, "com.example.myapp",
            ),
            commands.single().dropLast(2),
        )
    }

    @Test
    fun launchSurfacesDeveloperModeErrorFromJsonOutput() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) {
            CommandResult.failure("CoreDeviceError 10005") to """
                {"error":{"code":10005,"userInfo":{"NSLocalizedDescription":{"string":"Developer Mode is disabled."}}}}
            """.trimIndent()
        }
        val service = DesktopIosAppService(runner)

        val result = service.launch(PhysicalUdid, "com.example.myapp")

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("Developer Mode is disabled."), result.stderr)
    }

    @Test
    fun stopResolvesPidFromProcessListThenTerminates() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { command ->
            val json = when {
                command.contains("processes") -> """
                    {"result":{"runningProcesses":[
                      {"processIdentifier":42,"executable":"file:///private/var/containers/Bundle/Application/ABC/MyApp.app/MyApp"},
                      {"processIdentifier":7,"executable":"file:///usr/libexec/other"}
                    ]}}
                """.trimIndent()
                command.contains("apps") -> """
                    {"result":{"apps":[{"bundleIdentifier":"com.example.myapp","name":"My App",
                      "url":"file:///private/var/containers/Bundle/Application/ABC/MyApp.app/"}]}}
                """.trimIndent()
                else -> """{"info":{"outcome":"success"}}"""
            }
            CommandResult.success() to json
        }
        val service = DesktopIosAppService(runner)

        val result = service.stop(PhysicalUdid, "com.example.myapp")

        assertTrue(result.isSuccess, result.stderr)
        val terminate = commands.last()
        assertEquals(
            listOf("xcrun", "devicectl", "device", "process", "terminate", "--device", PhysicalUdid, "--pid", "42"),
            terminate.dropLast(2),
        )
    }

    @Test
    fun stopFailsWithGuidanceWhenNoMatchingProcessIsRunning() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { command ->
            val json = if (command.contains("processes")) {
                """{"result":{"runningProcesses":[{"processIdentifier":7,"executable":"file:///usr/libexec/other"}]}}"""
            } else {
                """{"result":{"apps":[]}}"""
            }
            CommandResult.success() to json
        }
        val service = DesktopIosAppService(runner)

        val result = service.stop(PhysicalUdid, "com.example.myapp")

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("com.example.myapp"), result.stderr)
        assertTrue(commands.none { it.contains("terminate") })
    }

    @Test
    fun installAndUninstallUseDevicectlAppSubcommands() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { CommandResult.success() to null }
        val service = DesktopIosAppService(runner)

        service.install(PhysicalUdid, "/tmp/MyApp.app", replace = true)
        service.uninstall(PhysicalUdid, "com.example.myapp")

        assertEquals(
            listOf(
                listOf("xcrun", "devicectl", "device", "install", "app", "--device", PhysicalUdid, "/tmp/MyApp.app"),
                listOf("xcrun", "devicectl", "device", "uninstall", "app", "--device", PhysicalUdid, "com.example.myapp"),
            ),
            commands.map { it.dropLast(2) },
        )
    }

    @Test
    fun clearDataAndResetPermissionsExplainThePhysicalWorkaround() = runBlocking {
        registerPhysicalDevice()
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })

        val cleared = service.clearData(PhysicalUdid, "com.example.myapp")
        assertFalse(cleared.isSuccess)
        assertTrue(cleared.stderr.contains("reinstall"), cleared.stderr)

        val reset = service.resetPermissions(PhysicalUdid, "com.example.myapp")
        assertFalse(reset.isSuccess)
        assertTrue(reset.stderr.contains("not supported"), reset.stderr)
    }

    @Test
    fun unregisteredSerialsStillTakeTheSimulatorPath() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        DesktopIosAppService(runner).launch("BOOTED-UDID", "com.example.myapp")
        assertEquals(listOf("xcrun", "simctl", "launch", "BOOTED-UDID", "com.example.myapp"), commands.single())
    }

    @Test
    fun findProcessIdPrefersBundleIdMatchAndFallsBackToAppBundleName() {
        val service = DesktopIosAppService(CommandRunner { _, _ -> CommandResult.success() })
        val output = """
            {"result":{"runningProcesses":[
              {"processIdentifier":11,"executable":"file:///var/containers/Bundle/Application/ABC/MyApp.app/MyApp"},
              {"processIdentifier":12,"executable":"file:///var/mobile/com.example.myapp/run"}
            ]}}
        """.trimIndent()

        assertEquals(12, service.findProcessId(output, "com.example.myapp", "MyApp.app"))
        assertEquals(11, service.findProcessId(output, "com.other.app", "MyApp.app"))
        assertNull(service.findProcessId(output, "com.other.app", null))
        assertNull(service.findProcessId("not json", "com.example.myapp", null))
    }
}
