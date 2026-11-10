package andy

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.testretry.TestRetryTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.libraryNamespace(): String {
    val suffix = path
        .removePrefix(":")
        .split(":")
        .joinToString(".") { segment ->
            segment
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .lowercase()
        }
    return "com.joetr.andy.$suffix"
}

internal fun Project.isAndySharedLibraryModule(): Boolean =
    path.startsWith(":") &&
        path != ":" &&
        path != ":composeApp" &&
        path != ":androidApp" &&
        path != ":desktopApp" &&
        path != ":web-launcher" &&
        path != ":agent-store"

internal fun Project.configureAndyKmp(
    extension: KotlinMultiplatformExtension,
    enableIosTargets: Boolean = false,
) {
    extensions.configure(BasePluginExtension::class.java) {
        archivesName.set(path.removePrefix(":").replace(":", "-"))
    }

    extension.apply {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }

        jvm("desktop") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }

        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }

        if (enableIosTargets) {
            iosArm64()
            iosSimulatorArm64()
        }

        sourceSets.apply {
            all {
                languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                languageSettings.optIn("kotlinx.coroutines.FlowPreview")
            }
            matching { it.name.contains("wasmJs", ignoreCase = true) }.configureEach {
                languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            }
            named("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }

    configureAndyDesktopTests()
}

internal fun Project.configureAndyDesktopTests() {
    // CI opts into retries with -PandyRetryTests=true. Timing/socket/file-watcher tests are
    // occasionally flaky on slower macOS runners; retrying keeps the signal without silencing
    // genuine failures (a test that only ever fails still fails the build).
    val retryTests = providers.gradleProperty("andyRetryTests").orNull?.toBoolean() == true
    if (retryTests) {
        pluginManager.apply("org.gradle.test-retry")
    }
    tasks.withType<Test>().configureEach {
        if (name == "desktopTest") {
            systemProperty("java.awt.headless", "false")
            jvmArgs("--add-exports=jdk.unsupported.desktop/jdk.swing.interop=ALL-UNNAMED")
            if (retryTests) {
                extensions.configure<TestRetryTaskExtension> {
                    maxRetries.set(3)
                    failOnPassedAfterRetry.set(false)
                }
            }
        }
    }
}

fun Project.ffmpegPlatformClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> when (arch) {
            "aarch64", "arm64" -> "macosx-arm64"
            else -> "macosx-x86_64"
        }
        os.contains("win") -> "windows-x86_64"
        arch == "aarch64" || arch == "arm64" -> "linux-arm64"
        else -> "linux-x86_64"
    }
}
