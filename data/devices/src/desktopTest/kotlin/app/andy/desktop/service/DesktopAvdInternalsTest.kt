package app.andy.desktop.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAvdInternalsTest {
    @Test
    fun emulatorGraphicsInfoReadsTheBackendActuallySelectedByAutoGpu() {
        val log = kotlin.io.path.createTempFile("andy-emulator", ".log").toFile()
        try {
            log.writeText(
                """
                INFO | Graphics backend: gfxstream
                INFO | GPU Renderer=[Android Emulator OpenGL ES Translator (Apple M5 Pro)]
                INFO | Graphics backend: gfxstream
                INFO | Graphics Adapter Android Emulator OpenGL ES Translator (Apple M5 Pro)
                """.trimIndent(),
            )

            assertEquals(
                EmulatorGraphicsInfo("gfxstream", "Android Emulator OpenGL ES Translator (Apple M5 Pro)"),
                emulatorGraphicsInfo(log),
            )
        } finally {
            log.delete()
        }
    }

    @Test
    fun emulatorGraphicsInfoMarksSoftwareAutoGpuFallbacks() {
        val log = kotlin.io.path.createTempFile("andy-emulator-software", ".log").toFile()
        try {
            log.writeText(
                """
                INFO | Graphics backend: gfxstream
                INFO | GPU Renderer=[Android Emulator OpenGL ES Translator (ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device)), SwiftShader driver)]
                """.trimIndent(),
            )

            val graphics = requireNotNull(emulatorGraphicsInfo(log))
            assertTrue(graphics.softwareRendered)
        } finally {
            log.delete()
        }
    }
    @Test
    fun emulatorLaunchCommandUsesStudioStyleHiddenWindowAndGrpc() {
        val command = emulatorStudioStyleLaunchCommand(
            emulator = "/sdk/emulator/emulator",
            name = "Pixel_8_API_36",
            extraArgs = listOf("-no-snapshot-load", "-no-snapshot-save"),
            ports = EmulatorLaunchPorts(console = 5560, adb = 5561, grpc = 8560),
        )

        assertEquals("/sdk/emulator/emulator", command.first())
        assertTrue(command.windowed(2).any { it == listOf("-avd", "Pixel_8_API_36") })
        assertTrue("-qt-hide-window" in command)
        assertTrue(command.windowed(2).any { it == listOf("-ports", "5560,5561") })
        assertTrue(command.windowed(2).any { it == listOf("-grpc", "8560") })
        assertTrue(command.windowed(2).any { it == listOf("-idle-grpc-timeout", "300") })
        assertTrue(command.windowed(2).any { it == listOf("-gpu", "host") })
        assertTrue(command.windowed(2).any { it == listOf("-vsync-rate", "120") })
        assertTrue("-writable-system" in command)
        assertTrue("-no-snapshot-load" in command)
        assertTrue("-no-snapshot-save" in command)
        assertFalse("-no-window" in command)
        assertFalse("-grpc-use-token" in command)
        assertFalse("swiftshader_indirect" in command)
    }

    @Test
    fun emulatorLaunchCommandHonorsVsyncRateOverride() {
        val command = emulatorStudioStyleLaunchCommand(
            emulator = "/sdk/emulator/emulator",
            name = "Pixel_8",
            vsyncRate = 90,
        )
        assertTrue(command.windowed(2).any { it == listOf("-vsync-rate", "90") })
    }

    @Test
    fun emulatorLaunchEnvironmentForcesXcbOnLinuxAndDropsSoftwareGlOverrides() {
        val env = mutableMapOf(
            "DISPLAY" to ":0",
            "WAYLAND_DISPLAY" to "wayland-0",
            "QT_QPA_PLATFORM" to "wayland",
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            "GALLIUM_DRIVER" to "llvmpipe",
            "VK_ICD_FILENAMES" to "/usr/share/vulkan/icd.d/lvp_icd.json",
            "HOME" to "/home/test",
        )
        applyEmulatorLaunchEnvironment(env, osName = "Linux")
        assertEquals("xcb", env["QT_QPA_PLATFORM"])
        assertEquals(":0", env["DISPLAY"])
        assertEquals("/home/test", env["HOME"])
        assertNull(env["LIBGL_ALWAYS_SOFTWARE"])
        assertNull(env["GALLIUM_DRIVER"])
        assertNull(env["VK_ICD_FILENAMES"])
    }

    @Test
    fun emulatorLaunchEnvironmentLeavesQtPlatformAloneOffLinux() {
        val env = mutableMapOf("QT_QPA_PLATFORM" to "cocoa")
        applyEmulatorLaunchEnvironment(env, osName = "Mac OS X")
        assertEquals("cocoa", env["QT_QPA_PLATFORM"])
    }
}
