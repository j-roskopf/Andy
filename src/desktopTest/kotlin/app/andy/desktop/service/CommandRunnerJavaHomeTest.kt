package app.andy.desktop.service

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandRunnerJavaHomeTest {
    @Test
    fun ensureJavaHomeSetsMissingJavaHomeFromRuntime() {
        val root = createTempDirectory("andy-java-home").toFile()
        val javaBin = File(root, "bin").also { it.mkdirs() }
        val java = File(javaBin, "java").also {
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }
        assertTrue(java.canExecute())

        val env = mutableMapOf("PATH" to "/usr/bin")
        ensureJavaHome(env, root.absolutePath)

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
        ensureJavaHome(env, runtime.absolutePath)

        assertEquals(existing.absolutePath, env["JAVA_HOME"])
    }
}
