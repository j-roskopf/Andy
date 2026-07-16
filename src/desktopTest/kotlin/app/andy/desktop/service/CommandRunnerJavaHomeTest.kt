package app.andy.desktop.service

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandRunnerJavaHomeTest {
    @Test
    fun ensureJavaHomeSetsMissingJavaHomeFromLocator() {
        val root = createTempDirectory("andy-java-home").toFile()
        val javaBin = File(root, "bin").also { it.mkdirs() }
        val java = File(javaBin, "java").also {
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }
        assertTrue(java.canExecute())

        val env = mutableMapOf("PATH" to "/usr/bin")
        ensureJavaHome(env, javaHomeProperty = null, locateJavaHome = { root.absolutePath })

        assertEquals(root.absolutePath, env["JAVA_HOME"])
        assertTrue(env.getValue("PATH").startsWith(javaBin.absolutePath + File.pathSeparator))
    }

    @Test
    fun ensureJavaHomeKeepsUsableExistingJavaHome() {
        val existing = createTempDirectory("andy-existing-java").toFile()
        File(existing, "bin").mkdirs()
        File(existing, "bin/java").also {
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }
        val runtime = createTempDirectory("andy-runtime-java").toFile()
        File(runtime, "bin").mkdirs()
        File(runtime, "bin/java").also {
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }

        val env = mutableMapOf("JAVA_HOME" to existing.absolutePath, "PATH" to "/usr/bin")
        ensureJavaHome(env, javaHomeProperty = runtime.absolutePath, locateJavaHome = { runtime.absolutePath })

        assertEquals(existing.absolutePath, env["JAVA_HOME"])
    }

    @Test
    fun ensureJavaHomeReplacesJlinkRuntimeWithoutJavaLauncher() {
        val jlink = createTempDirectory("andy-jlink-runtime").toFile().also { it.mkdirs() }
        val hostJdk = createTempDirectory("andy-host-jdk").toFile()
        File(hostJdk, "bin").mkdirs()
        File(hostJdk, "bin/java").also {
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }

        val env = mutableMapOf("PATH" to "/usr/bin:/bin")
        ensureJavaHome(env, javaHomeProperty = jlink.absolutePath, locateJavaHome = { hostJdk.absolutePath })

        assertEquals(hostJdk.absolutePath, env["JAVA_HOME"])
        assertTrue(env.getValue("PATH").startsWith(File(hostJdk, "bin").absolutePath + File.pathSeparator))
    }
}

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
    fun skipsJlinkRuntimeWithoutJavaLauncherAndFindsSdkman() {
        val root = createTempDir("andy-jlink-then-sdkman")
        val jlink = File(root, "runtime/Home").also { it.mkdirs() }
        val home = File(root, "home").also { it.mkdirs() }
        val sdkman = File(home, ".sdkman/candidates/java/21.0.11-amzn").also { makeFakeJdk(it) }
        File(home, ".sdkman/candidates/java/current").also { current ->
            // Symlink preferred when present; fall back to a plain directory for CI hosts
            // that cannot create symlinks.
            runCatching {
                java.nio.file.Files.createSymbolicLink(current.toPath(), sdkman.toPath())
            }.getOrElse {
                makeFakeJdk(current)
            }
        }

        val found = JavaHomeLocator.find(
            env = emptyMap(),
            javaHomeProperty = jlink.absolutePath,
            userHome = home,
            applications = File(root, "Applications").also { it.mkdirs() },
            runCommand = { null },
        )

        val expectedCurrent = File(home, ".sdkman/candidates/java/current").absolutePath
        assertTrue(
            found == sdkman.absolutePath || found == expectedCurrent,
            "expected sdkman JDK, got $found",
        )
        assertTrue(JavaHomeLocator.isUsableJavaHome(checkNotNull(found)))
    }

    @Test
    fun rejectsJavaHomeWithoutJavaBinaryWhenNoFallbackExists() {
        val root = createTempDir("andy-bad-java-home")
        val emptyHome = File(root, "jlink-runtime").also { it.mkdirs() }

        assertNull(
            JavaHomeLocator.find(
                env = mapOf("JAVA_HOME" to emptyHome.absolutePath),
                javaHomeProperty = emptyHome.absolutePath,
                userHome = File(root, "home").also { it.mkdirs() },
                applications = File(root, "Applications").also { it.mkdirs() },
                runCommand = { null },
            ),
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
