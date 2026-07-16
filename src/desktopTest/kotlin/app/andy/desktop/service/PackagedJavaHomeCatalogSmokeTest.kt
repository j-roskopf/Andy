package app.andy.desktop.service

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Simulates packaged Andy: jlink `java.home` with no `bin/java`, and no inherited JAVA_HOME.
 * Requires a host JDK (SDKMAN / Studio / Homebrew) and Android cmdline-tools.
 */
class PackagedJavaHomeCatalogSmokeTest {
    @Test
    fun catalogListSystemImagesWorksWithoutInheritedJavaHome() = runBlocking {
        val jlinkHome = resolveReleaseJlinkHome()
            ?: File.createTempFile("andy-jlink-home", null).also {
                it.delete()
                it.mkdirs()
            }
        assertTrue(!File(jlinkHome, "bin/java").canExecute(), "jlink fixture must lack bin/java")

        val env = mutableMapOf(
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to System.getProperty("user.home"),
        )
        ensureJavaHome(env, javaHomeProperty = jlinkHome.absolutePath)
        val javaHome = env["JAVA_HOME"]
            ?: fail("expected ensureJavaHome to discover a host JDK when jlink runtime has no launcher")
        assertTrue(JavaHomeLocator.isUsableJavaHome(javaHome), "discovered JAVA_HOME must include bin/java")

        val runner = CommandRunner { command, timeoutSeconds ->
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(env)
                    ensureJavaHome(builder.environment(), javaHomeProperty = jlinkHome.absolutePath)
                }
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@CommandRunner app.andy.service.CommandResult.failure("timeout", 124)
            }
            app.andy.service.CommandResult(process.exitValue(), stdout, stderr)
        }
        val images = DesktopAvdService(runner, SdkLocator()).listSystemImages()
        assertTrue(images.isNotEmpty(), "catalog should return system images under packaged-env simulation")
        assertTrue(images.any { it.packageId.contains("system-images") || it.apiLevel > 0 })
    }

    private fun resolveReleaseJlinkHome(): File? {
        val candidates = listOf(
            File("build/compose/binaries/main-release/app/Andy.app/Contents/runtime/Contents/Home"),
            File("build/compose/binaries/main/app/Andy.app/Contents/runtime/Contents/Home"),
        )
        return candidates.firstOrNull { it.isDirectory && !File(it, "bin/java").canExecute() }
    }
}
