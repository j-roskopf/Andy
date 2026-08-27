package app.andy.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DhuModelsTest {
    @Test
    fun fixedConfigIniUsesTouch800x480_160dpi_30fps() {
        val ini = DhuFixedConfig.iniContents()
        assertTrue(ini.contains("touch = true"))
        assertTrue(ini.contains("resolution = 800x480"))
        assertTrue(ini.contains("dpi = 160"))
        assertTrue(ini.contains("framerate = 30"))
        assertTrue(ini.contains("[sensors]"))
        assertTrue(ini.contains("night_mode = true"))
    }

    @Test
    fun buildLaunchCommandUsesAndyOwnedFlags() {
        val adb = DhuCommandFactory.buildLaunchCommand(
            executable = "/sdk/extras/google/auto/desktop-head-unit",
            configPath = "/tmp/andy-dhu.ini",
            link = DhuLinkTransport.Adb,
            serial = "emulator-5554",
            localAdbPort = 41234,
        )
        assertEquals(
            listOf(
                "/sdk/extras/google/auto/desktop-head-unit",
                "--config=/tmp/andy-dhu.ini",
                "--input=touch",
                "--adb=41234",
            ),
            adb,
        )
        val usb = DhuCommandFactory.buildLaunchCommand(
            executable = "/sdk/extras/google/auto/desktop-head-unit",
            configPath = "/tmp/andy-dhu.ini",
            link = DhuLinkTransport.Usb,
            serial = "5A080DLCH000UR",
        )
        assertEquals(
            listOf(
                "/sdk/extras/google/auto/desktop-head-unit",
                "--config=/tmp/andy-dhu.ini",
                "--input=touch",
                "--usb=5A080DLCH000UR",
            ),
            usb,
        )
        assertEquals(
            DhuLinkTransport.Usb,
            DhuCommandFactory.preferredLinkTransport(app.andy.model.DeviceTransport.Usb),
        )
        assertEquals(
            DhuLinkTransport.Adb,
            DhuCommandFactory.preferredLinkTransport(
                app.andy.model.DeviceTransport.Unknown,
                app.andy.model.DeviceKind.Emulator,
            ),
        )
        assertTrue(
            DhuCommandFactory.isUsbAccessoryMode("current_functions=ACCESSORY\nconfigured=true"),
        )
        assertFalse(
            DhuCommandFactory.isUsbAccessoryMode("current_functions=ADB\nconfigured=true"),
        )
        assertEquals(
            listOf(
                listOf("/adb", "-s", "S", "shell", "svc", "usb", "setFunctions", "none"),
                listOf("/adb", "-s", "S", "shell", "svc", "usb", "setFunctions", "adb"),
            ),
            DhuCommandFactory.buildClearUsbAccessory("/adb", "S"),
        )
    }

    @Test
    fun adbForwardIsSerialSpecificAndCleanupRemovesLocalPort() {
        val forward = DhuCommandFactory.buildAdbForward("/adb", "emulator-5554", 19001)
        assertEquals(listOf("/adb", "-s", "emulator-5554", "forward", "tcp:19001", "tcp:5277"), forward)
        val remove = DhuCommandFactory.buildAdbForwardRemove("/adb", "emulator-5554", 19001)
        assertEquals(listOf("/adb", "-s", "emulator-5554", "forward", "--remove", "tcp:19001"), remove)
    }

    @Test
    fun consoleHistoryPushesTrimsAndRecalls() {
        var history = emptyList<String>()
        history = DhuConsoleHistory.pushCommand(history, "day")
        history = DhuConsoleHistory.pushCommand(history, "night")
        history = DhuConsoleHistory.pushCommand(history, "day")
        assertEquals(listOf("night", "day"), history)

        val (upIdx, up) = DhuConsoleHistory.recall(history, -1, -1)
        assertEquals("day", up)
        val (olderIdx, older) = DhuConsoleHistory.recall(history, upIdx, -1)
        assertEquals("night", older)
        val (cleared, value) = DhuConsoleHistory.recall(history, olderIdx, 2)
        assertEquals(-1, cleared)
        assertEquals(null, value)
    }

    @Test
    fun consoleLinesCapAtMax() {
        var lines = emptyList<String>()
        repeat(DhuConsoleHistory.MaxLines + 25) { i ->
            lines = DhuConsoleHistory.appendLine(lines, "line-$i")
        }
        assertEquals(DhuConsoleHistory.MaxLines, lines.size)
        assertTrue(lines.first().startsWith("line-"))
        assertEquals("line-${DhuConsoleHistory.MaxLines + 24}", lines.last())
    }

    @Test
    fun readinessReadyRequiresAllOk() {
        val ready = DhuReadiness(
            hostKind = DhuHostKind.MacOs,
            checks = listOf(
                DhuReadinessCheck("a", "A", DhuCheckStatus.Ok, "ok"),
                DhuReadinessCheck("b", "B", DhuCheckStatus.Ok, "ok"),
            ),
        )
        assertTrue(ready.ready)
        val blocked = ready.copy(
            checks = ready.checks + DhuReadinessCheck("c", "C", DhuCheckStatus.Missing, "no", "fix it"),
        )
        assertFalse(blocked.ready)
        assertEquals(1, blocked.blocking.size)
        assertTrue(blocked.diagnosticsText().contains("fix: fix it"))
    }
}
