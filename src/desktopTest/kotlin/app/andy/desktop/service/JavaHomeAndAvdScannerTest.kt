package app.andy.desktop.service

import app.andy.model.VirtualDeviceType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaHomeLocatorTest {
    @Test
    fun prefersExplicitJavaHomeWhenUsable() {
        val root = createTempDir("andy-java-home")
        val jdk = File(root, "jdk").also { makeFakeJdk(it) }

        val found = JavaHomeLocator.find(
            env = mapOf("JAVA_HOME" to jdk.absolutePath),
            javaHomeProperty = null,
            userHome = File(root, "home").also { it.mkdirs() },
            applications = File(root, "Applications").also { it.mkdirs() },
            runCommand = { null },
        )

        assertEquals(jdk.absolutePath, found)
    }

    @Test
    fun discoversAndroidStudioJbrWhenEnvMissing() {
        val root = createTempDir("andy-studio-jbr")
        val home = File(root, "home").also { it.mkdirs() }
        val apps = File(root, "Applications").also { it.mkdirs() }
        val jbr = File(apps, "Android Studio.app/Contents/jbr/Contents/Home").also { makeFakeJdk(it) }

        val found = JavaHomeLocator.find(
            env = emptyMap(),
            javaHomeProperty = null,
            userHome = home,
            applications = apps,
            runCommand = { null },
        )

        assertEquals(jbr.absolutePath, found)
    }

    @Test
    fun rejectsJavaHomeWithoutJavaBinary() {
        val root = createTempDir("andy-bad-java-home")
        val emptyHome = File(root, "jlink-runtime").also { it.mkdirs() }

        // jlink-style runtimes (and empty dirs) must not count as usable JAVA_HOME.
        // find() may still discover a real host JDK elsewhere on the machine.
        assertTrue(!JavaHomeLocator.isUsableJavaHome(emptyHome.absolutePath))
        assertNull(
            JavaHomeLocator.find(
                env = mapOf("JAVA_HOME" to emptyHome.absolutePath),
                javaHomeProperty = emptyHome.absolutePath,
                userHome = File(root, "home").also { it.mkdirs() },
                applications = File(root, "Applications").also { it.mkdirs() },
                runCommand = { null },
            )?.takeIf { it == emptyHome.absolutePath }
        )
    }

    private fun makeFakeJdk(home: File) {
        val bin = File(home, "bin").also { it.mkdirs() }
        val java = File(bin, "java")
        java.writeText("#!/bin/sh\n")
        java.setExecutable(true)
    }

    private fun createTempDir(prefix: String): File {
        return File.createTempFile(prefix, null).also {
            it.delete()
            it.mkdirs()
        }
    }
}

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
            """.trimIndent()
        )
        File(avdHome, "Pixel_6.ini").writeText(
            """
            avd.ini.encoding=UTF-8
            path=${pixel.absolutePath}
            target=android-36
            """.trimIndent()
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
    fun returnsEmptyWhenAvdHomeMissing() {
        val root = createTempDir("andy-missing-avd")
        val avds = AvdHomeScanner.listVirtualDevices(
            env = mapOf("ANDROID_AVD_HOME" to File(root, "missing").absolutePath),
        )
        assertTrue(avds.isEmpty())
    }

    private fun createTempDir(prefix: String): File {
        return File.createTempFile(prefix, null).also {
            it.delete()
            it.mkdirs()
        }
    }
}
