package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosAppServiceTest {
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
}
