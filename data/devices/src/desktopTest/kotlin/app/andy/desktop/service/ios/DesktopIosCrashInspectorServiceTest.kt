package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.CrashKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.IosTargetRegistry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val Udid = "CA4B2892-6294-4CD4-AA5A-6031551226BA"
private const val OtherUdid = "11111111-2222-3333-4444-555555555555"

class DesktopIosCrashInspectorServiceTest {
    @AfterTest
    fun clearRegistry() {
        IosTargetRegistry.update(emptyList())
    }

    private fun ipsReportText(udid: String, processName: String = "MyApp"): String {
        val header = """{"app_name":"$processName","timestamp":"2026-08-19 21:00:00.000000 -0500"}"""
        val body = """
            {
              "procName": "$processName",
              "procPath": "/Users/me/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Bundle/Application/ABC/$processName.app/$processName",
              "exception": {"type": "EXC_BREAKPOINT"}
            }
        """.trimIndent()
        return "$header\n$body"
    }

    @Test
    fun listCrashesFiltersToSelectedSimulatorAndParsesSummary() = runBlocking {
        val dir = Files.createTempDirectory("andy-ips-test").toFile()
        java.io.File(dir, "MyApp-2026-08-19.ips").writeText(ipsReportText(Udid))
        java.io.File(dir, "OtherApp-2026-08-19.ips").writeText(ipsReportText(OtherUdid, "OtherApp"))
        java.io.File(dir, "not-a-crash.txt").writeText("ignored")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = Udid,
                    displayName = "iPhone 17 Pro",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val service = DesktopIosCrashInspectorService(CommandRunner(), dir)

        val crashes = service.listCrashes(Udid)

        assertEquals(1, crashes.size)
        assertEquals("MyApp", crashes.first().packageName)
        assertEquals(CrashKind.NativeCrash, crashes.first().kind)
        assertTrue(crashes.first().summary.contains("MyApp"))
    }

    @Test
    fun listCrashesReturnsEmptyWhenDirectoryMissing() = runBlocking {
        val dir = Files.createTempDirectory("andy-ips-missing").toFile().resolve("nope")
        val service = DesktopIosCrashInspectorService(CommandRunner(), dir)

        assertEquals(emptyList(), service.listCrashes(Udid))
    }

    @Test
    fun loadCrashReturnsRawTextForUnknownId() = runBlocking {
        val dir = Files.createTempDirectory("andy-ips-load").toFile()
        val service = DesktopIosCrashInspectorService(CommandRunner(), dir)

        val text = service.loadCrash(Udid, "ips:/does/not/exist.ips")

        assertTrue(text.contains("not found"))
    }

    @Test
    fun listCrashesUsesSystemCrashLogsDomainForPhysicalDevices() = runBlocking {
        val physicalUdid = "00008140-00026112260B001C"
        IosTargetRegistry.update(
            listOf(IosTarget(physicalUdid, "iPhone 16 Pro", IosTargetKind.Physical, IosTargetState.Unknown)),
        )
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            val outputIndex = command.indexOf("--json-output")
            if (outputIndex >= 0) {
                java.io.File(command[outputIndex + 1]).writeText(
                    """
                    {"result":{"files":[
                      {"name":"MyApp-2026-08-19-210000.ips","path":"Retired/MyApp-2026-08-19-210000.ips","type":"regularFile"},
                      {"name":"log-aggregated.txt","path":"log-aggregated.txt","type":"regularFile"},
                      {"name":"Retired","path":"Retired","type":"directory"}
                    ]}}
                    """.trimIndent(),
                )
            }
            CommandResult.success()
        }
        val dir = Files.createTempDirectory("andy-ips-physical").toFile()

        val crashes = DesktopIosCrashInspectorService(runner, dir).listCrashes(physicalUdid)

        val crash = crashes.single()
        assertEquals("devicectl:Retired/MyApp-2026-08-19-210000.ips", crash.id)
        assertEquals("MyApp", crash.packageName)
        assertTrue(commands.single().containsAll(listOf("--domain-type", "systemCrashLogs")))
    }

    @Test
    fun loadCrashCopiesFromDeviceThenReadsTheReport() = runBlocking {
        val physicalUdid = "00008140-00026112260B001C"
        IosTargetRegistry.update(
            listOf(IosTarget(physicalUdid, "iPhone 16 Pro", IosTargetKind.Physical, IosTargetState.Unknown)),
        )
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            val destinationIndex = command.indexOf("--destination")
            if (destinationIndex >= 0) {
                java.io.File(command[destinationIndex + 1], "MyApp.ips").writeText(ipsReportText(Udid))
            }
            CommandResult.success()
        }
        val dir = Files.createTempDirectory("andy-ips-physical-load").toFile()

        val text = DesktopIosCrashInspectorService(runner, dir)
            .loadCrash(physicalUdid, "devicectl:Retired/MyApp.ips")

        assertTrue(text.contains("MyApp"), text)
        val copy = commands.single()
        assertTrue(copy.containsAll(listOf("device", "copy", "from", "--source", "Retired/MyApp.ips")))
    }

    @Test
    fun exportCrashWritesLoadedTextToLocalPath() = runBlocking {
        val dir = Files.createTempDirectory("andy-ips-export").toFile()
        val crashFile = java.io.File(dir, "MyApp.ips").apply { writeText(ipsReportText(Udid)) }
        val service = DesktopIosCrashInspectorService(CommandRunner(), dir)
        val id = "ips:${crashFile.absolutePath}"
        val outFile = java.io.File(dir, "exported.txt")

        val result = service.exportCrash(Udid, id, outFile.absolutePath)

        assertTrue(result.isSuccess, result.stderr)
        assertTrue(outFile.readText().contains("MyApp"))
    }
}
