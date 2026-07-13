package app.andy.desktop.updates

import app.andy.service.UpdatePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAppUpdateServiceTest {
    @Test
    fun selectsFlatpakAssetForFlatpakInstalls() {
        val asset = selectUpdateAsset(
            UpdatePlatform.LinuxFlatpak,
            listOf(
                GitHubAssetDto("Andy-1.2.3.deb", "https://example.test/andy.deb", state = "uploaded"),
                GitHubAssetDto("Andy-1.2.3.flatpak", "https://example.test/andy.flatpak", state = "uploaded"),
            ),
        )

        assertEquals("Andy-1.2.3.flatpak", asset?.name)
    }

    @Test
    fun flatpakInstallerEscapesToHostAndRelaunchesFlatpak() {
        val script = linuxInstallerHelperScript(
            filePath = "/home/ada/Downloads/Andy-1.2.3.flatpak",
            flatpak = true,
            insideFlatpak = true,
            relaunchCommand = "flatpak run com.joetr.andy",
        )

        assertTrue(script.contains("flatpak-spawn --host sh -c"))
        assertTrue(script.contains("flatpak install --user -y"))
        assertTrue(script.contains("flatpak install -y"))
        assertTrue(script.contains("flatpak run com.joetr.andy"))
    }

    @Test
    fun macInstallerDoesNotRelaunchAndy() {
        val script = macPkgInstallerHelperScript("/tmp/Andy-1.2.3.pkg")

        assertTrue(script.contains("exec /usr/bin/open -W '/tmp/Andy-1.2.3.pkg'"))
        assertTrue(!script.contains("open -a Andy"))
    }

    @Test
    fun macInstallerDetachesWithoutUsingDiscardForStdin() {
        val builder = macInstallerProcessBuilder("/tmp/andy-update.sh")

        assertEquals(java.io.File("/dev/null"), builder.redirectInput().file())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
    }
}
