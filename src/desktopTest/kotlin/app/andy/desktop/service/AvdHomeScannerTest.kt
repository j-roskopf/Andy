package app.andy.desktop.service

import app.andy.model.VirtualDeviceType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvdHomeScannerTest {
    @Test
    fun listsAvdsFromIniAndConfig() {
        val root = createTempDir("andy-avd-home")
        val avdHome = File(root, "avd").also { it.mkdirs() }
        val pixel = File(avdHome, "Pixel_6.avd").also { it.mkdirs() }
        File(pixel, "config.ini").writeText(
            """
            abi.type=arm64-v8a
            image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/
            hw.device.name=pixel_6
            """.trimIndent(),
        )
        File(avdHome, "Pixel_6.ini").writeText(
            """
            avd.ini.encoding=UTF-8
            path=${pixel.absolutePath}
            target=android-36
            """.trimIndent(),
        )

        val avds = AvdHomeScanner.listVirtualDevices(env = mapOf("ANDROID_AVD_HOME" to avdHome.absolutePath))

        assertEquals(1, avds.size)
        assertEquals("Pixel_6", avds.single().name)
        assertEquals(pixel.absolutePath, avds.single().path)
        assertEquals("android-36", avds.single().target)
        assertEquals("arm64-v8a", avds.single().abi)
        assertEquals(36, avds.single().apiLevel)
        assertEquals(VirtualDeviceType.Phone, avds.single().deviceType)
    }

    @Test
    fun resolvesAvdHomeFromAndroidUserHome() {
        val root = createTempDir("andy-user-home")
        val userHome = File(root, "android-user").also { it.mkdirs() }
        val avdHome = File(userHome, "avd").also { it.mkdirs() }
        val pixel = File(avdHome, "Wear_OS.avd").also { it.mkdirs() }
        File(pixel, "config.ini").writeText("hw.device.name=wear_os_square\n")
        File(avdHome, "Wear_OS.ini").writeText("path=${pixel.absolutePath}\n")

        val resolved = AvdHomeScanner.avdHome(env = mapOf("ANDROID_USER_HOME" to userHome.absolutePath))
        val avds = AvdHomeScanner.listVirtualDevices(env = mapOf("ANDROID_USER_HOME" to userHome.absolutePath))

        assertEquals(avdHome.absolutePath, resolved.absolutePath)
        assertEquals(listOf("Wear_OS"), avds.map { it.name })
        assertEquals(VirtualDeviceType.Watch, avds.single().deviceType)
    }

    @Test
    fun prefersAndroidAvdHomeOverUserHome() {
        val root = createTempDir("andy-avd-override")
        val preferred = File(root, "preferred/avd").also { it.mkdirs() }
        val ignored = File(root, "ignored/avd").also { it.mkdirs() }
        val pixel = File(preferred, "Preferred.avd").also { it.mkdirs() }
        File(pixel, "config.ini").writeText("hw.device.name=pixel_8\n")
        File(preferred, "Preferred.ini").writeText("path=${pixel.absolutePath}\n")
        File(ignored, "Ignored.ini").writeText("path=${File(ignored, "Ignored.avd").absolutePath}\n")

        val avds = AvdHomeScanner.listVirtualDevices(
            env = mapOf(
                "ANDROID_AVD_HOME" to preferred.absolutePath,
                "ANDROID_USER_HOME" to File(root, "ignored").absolutePath,
            ),
        )

        assertEquals(listOf("Preferred"), avds.map { it.name })
    }

    @Test
    fun sortsByNameAndSkipsMalformedEntries() {
        val root = createTempDir("andy-avd-sort")
        val avdHome = File(root, "avd").also { it.mkdirs() }

        val zebra = File(avdHome, "Zebra.avd").also { it.mkdirs() }
        File(zebra, "config.ini").writeText("hw.device.name=pixel_9\n")
        File(avdHome, "Zebra.ini").writeText("path=${zebra.absolutePath}\n")

        val alpha = File(avdHome, "Alpha.avd").also { it.mkdirs() }
        File(alpha, "config.ini").writeText("hw.device.name=pixel_6\n")
        File(avdHome, "Alpha.ini").writeText("path=${alpha.absolutePath}\n")

        File(avdHome, "Broken.ini").writeText("path=/definitely/missing/Broken.avd\n")
        File(avdHome, "Empty.ini").writeText("# no path\n")
        File(avdHome, "not-an-ini.txt").writeText("ignore me\n")

        val avds = AvdHomeScanner.listVirtualDevices(env = mapOf("ANDROID_AVD_HOME" to avdHome.absolutePath))

        assertEquals(listOf("Alpha", "Zebra"), avds.map { it.name })
    }

    @Test
    fun returnsEmptyWhenAvdHomeMissing() {
        val root = createTempDir("andy-missing-avd")
        val avds = AvdHomeScanner.listVirtualDevices(
            env = mapOf("ANDROID_AVD_HOME" to File(root, "missing").absolutePath),
        )
        assertTrue(avds.isEmpty())
    }

    @Test
    fun usesDefaultUserHomeWhenEnvMissing() {
        val root = createTempDir("andy-default-home")
        val avdHome = File(root, ".android/avd").also { it.mkdirs() }
        val pixel = File(avdHome, "Default.avd").also { it.mkdirs() }
        File(pixel, "config.ini").writeText(
            """
            abi.type=x86_64
            image.sysdir.1=system-images/android-34/google_apis/x86_64/
            hw.device.name=pixel_7
            """.trimIndent(),
        )
        File(avdHome, "Default.ini").writeText("path=${pixel.absolutePath}\ntarget=android-34\n")

        val resolved = AvdHomeScanner.avdHome(env = emptyMap(), userHome = root)
        val avds = AvdHomeScanner.listVirtualDevices(env = emptyMap(), userHome = root)

        assertEquals(avdHome.absolutePath, resolved.absolutePath)
        assertEquals("Default", avds.single().name)
        assertEquals(34, avds.single().apiLevel)
        assertEquals("x86_64", avds.single().abi)
    }

    private fun createTempDir(prefix: String): File {
        return File.createTempFile(prefix, null).also {
            it.delete()
            it.mkdirs()
        }
    }
}
