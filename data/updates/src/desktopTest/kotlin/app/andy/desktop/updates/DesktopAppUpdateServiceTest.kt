package app.andy.desktop.updates

import app.andy.service.UpdatePlatform
import java.nio.file.Files
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
    fun macDmgInstallerReplacesAndRelaunchesAppBundle() {
        val script = macDmgInstallerHelperScript(
            dmgPath = "/tmp/Andy-1.2.3.dmg",
            targetAppBundle = "/Applications/Andy.app",
            parentProcessId = 1234,
        )

        // macOS ships mkdir under /bin, not /usr/bin.
        assertTrue(script.contains("/bin/mkdir -p \"${'$'}mount_point\""))
        assertTrue(script.lineSequence().none { it.trimStart().startsWith("/usr/bin/mkdir") })
        assertTrue(script.contains("/usr/bin/hdiutil attach \"${'$'}dmg_path\""))
        assertTrue(script.contains("/usr/bin/codesign --verify --deep --strict \"${'$'}source_app\""))
        assertTrue(script.contains("/usr/bin/ditto \"${'$'}source_app\" \"${'$'}staging_app\""))
        assertTrue(script.contains("with administrator privileges"))
        assertTrue(script.contains("should_reopen=1"))
        assertTrue(script.contains("reopen_current_app"))
        assertTrue(script.contains("trap 'exit 1' HUP INT TERM"))
        assertTrue(script.contains("trap cleanup EXIT"))
        assertTrue(script.contains("/usr/bin/open \"${'$'}target_app\""))
    }

    @Test
    fun macDmgInstallerFallsBackToOpeningTheDmgOutsideAnAppBundle() {
        val script = macDmgInstallerHelperScript(
            dmgPath = "/tmp/Andy-1.2.3.dmg",
            targetAppBundle = null,
            parentProcessId = 1234,
        )

        assertTrue(script.contains("exec /usr/bin/open -W '/tmp/Andy-1.2.3.dmg'"))
    }

    @Test
    fun macDmgInstallerIsValidShell() {
        // The syntax check needs a POSIX shell, which Windows CI lacks.
        if (!java.io.File("/bin/sh").exists()) return
        val script = macDmgInstallerHelperScript(
            dmgPath = "/tmp/Andy-1.2.3.dmg",
            targetAppBundle = "/Applications/Andy.app",
            parentProcessId = 1234,
        )
        val file = Files.createTempFile("andy-update", ".sh").toFile()
        try {
            file.writeText(script)
            val process = ProcessBuilder("/bin/sh", "-n", file.absolutePath).start()
            assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun recognizesPackagedMacAppFromLauncherAndJarPaths() {
        val tempDir = Files.createTempDirectory("andy-update")
        val app = tempDir.resolve("Andy.app")
        try {
            val launcher = app.resolve("Contents/MacOS/Andy")
            val jar = app.resolve("Contents/app/andy.jar")
            Files.createDirectories(launcher.parent)
            Files.createDirectories(jar.parent)

            assertEquals(app.toString(), macAppBundleForPath(launcher.toString()))
            assertEquals(app.toString(), macAppBundleForPath(jar.toString()))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun macInstallerDetachesWithoutUsingDiscardForStdin() {
        val builder = macInstallerProcessBuilder("/tmp/andy-update.sh")

        assertEquals(java.io.File("/dev/null"), builder.redirectInput().file())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput())
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
    }
}
