package app.andy.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.andy.service.CommandResult

class DeviceTransferHelpersTest {
    @Test
    fun classifiesApkOnlyDrops() {
        assertEquals(LocalDropKind.Apks, classifyLocalPaths(listOf("/tmp/app.apk", "/tmp/other.APK")))
    }

    @Test
    fun classifiesFileDrops() {
        assertEquals(LocalDropKind.Files, classifyLocalPaths(listOf("/tmp/notes.txt", "/tmp/assets")))
    }

    @Test
    fun classifiesMixedDrops() {
        assertEquals(LocalDropKind.Mixed, classifyLocalPaths(listOf("/tmp/app.apk", "/tmp/notes.txt")))
    }

    @Test
    fun detectsAlreadyInstalledConflict() {
        val result = CommandResult.failure("Failure [INSTALL_FAILED_ALREADY_EXISTS: Package com.example already exists]")
        assertTrue(result.isAlreadyInstalledConflict())
        assertFalse(CommandResult.failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE").isAlreadyInstalledConflict())
        assertFalse(CommandResult.success("Success").isAlreadyInstalledConflict())
    }

    @Test
    fun joinsRemotePaths() {
        assertEquals("/sdcard/Download/report.txt", joinRemotePath("/sdcard/Download", "report.txt"))
        assertEquals("/report.txt", joinRemotePath("/", "report.txt"))
    }
}
