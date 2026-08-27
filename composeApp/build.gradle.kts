import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.testRetry)
}

val andyVersionName = providers.gradleProperty("andy.versionName").orElse("0.1.0").get()
val andyVersionCode = providers.gradleProperty("andy.versionCode").orElse("1").map { it.toInt() }.get()
val andyDebugDistribution = providers.gradleProperty("andy.debugDistribution")
    .orElse(providers.environmentVariable("ANDY_DEBUG_DISTRIBUTION"))
    .map(String::toBoolean)
    .orElse(false)
val andyPackageId = if (andyDebugDistribution.get()) "com.joetr.andy.debug" else "com.joetr.andy"
val andyPackageName = if (andyDebugDistribution.get()) "Andy Debug" else "Andy"

val andyJpackagePackageVersion = run {
    val parts = andyVersionName.split(".")
    val major = parts.firstOrNull()?.toIntOrNull()
    if (major != null && major <= 250 && parts.size == 3) {
        val minor = parts[1].toIntOrNull()
        val patch = parts[2].toIntOrNull()
        if (minor != null && minor <= 255 && patch != null && patch <= 65535) {
            "${major + 1}.$minor.$patch"
        } else {
            "100.0.$andyVersionCode"
        }
    } else {
        "100.0.$andyVersionCode"
    }
}

val hostPlatform: String = run {
    val platformOverride = providers.gradleProperty("javacppPlatform").orNull
    if (!platformOverride.isNullOrBlank()) {
        platformOverride
    } else {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        when {
            osName.contains("mac") || osName.contains("darwin") -> {
                if (osArch == "aarch64" || osArch == "arm64") "macosx-arm64" else "macosx-x86_64"
            }
            osName.contains("windows") -> "windows-x86_64"
            osName.contains("linux") -> {
                if (osArch == "aarch64" || osArch == "arm64") "linux-arm64" else "linux-x86_64"
            }
            else -> "linux-x86_64"
        }
    }
}

group = "app.andy"
version = andyVersionName

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvm("desktop")
    wasmJs {
        browser {
            commonWebpackConfig {
                // The rename invalidates older cached development bundles once. The
                // webpack.config.d override prevents subsequent rebuilds from being
                // hidden by the browser cache.
                outputFileName = "andy-web.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).copy(
                    port = 10000,
                    open = providers.gradleProperty("web.openBrowser").map { it.toBoolean() }.orElse(true).get(),
                )
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":ui:shell"))
                implementation(project(":domain"))
                implementation(project(":navigation"))
                implementation(project(":core:platform"))
                implementation(libs.compose.components.resources)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":feature:agents"))
                implementation(project(":feature:settings"))
                implementation(project(":data:platform-tools"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":core:di"))
                implementation(project(":core:platform"))
                implementation(project(":ui:core"))
                implementation(project(":ui:components"))
                implementation(project(":data:mirror"))
                implementation(project(":data:agents"))
                implementation(project(":data:workspace"))
                implementation(project(":data:platform-tools"))
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("io.github.kdroidfilter:composenativetray:1.3.3")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":core:di"))
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
                implementation(npm("@yume-chan/adb", "2.6.0"))
                implementation(npm("@yume-chan/adb-daemon-webusb", "2.3.2"))
                implementation(npm("@yume-chan/adb-credential-web", "2.1.0"))
                implementation(npm("@yume-chan/adb-scrcpy", "2.3.2"))
                implementation(npm("@yume-chan/scrcpy", "2.3.0"))
                implementation(npm("@yume-chan/scrcpy-decoder-webcodecs", "2.5.3"))
                implementation(npm("@yume-chan/scrcpy-decoder-tinyh264", "2.1.0"))
                implementation(npm("@yume-chan/stream-extra", "2.6.1"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
                implementation("io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.60.0")
                implementation(project(":core:di"))
                implementation(project(":ui:shell"))
                implementation(project(":feature:agents"))
                implementation(project(":feature:settings"))
                implementation(project(":feature:live"))
                implementation(project(":feature:logcat"))
                implementation(project(":data:agents"))
                implementation(project(":data:mirror"))
                implementation(project(":data:platform-tools"))
                implementation(project(":data:devices"))
                implementation(project(":ui:components"))
                implementation(project(":agent-store"))
                implementation(project(":data:network"))
                implementation(project(":core:testing"))
                implementation(project(":feature:controls"))
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.zxing.core)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

// Kotlin 2.4 defaults managed Node to 25.x, which is not published to all repos yet.
plugins.withType<NodeJsPlugin> {
    extensions.configure<NodeJsEnvSpec>("nodejs") {
        version.set("22.22.0")
    }
}
plugins.withType<WasmNodeJsPlugin> {
    extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
        version.set("22.22.0")
        download.set(true)
    }
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("src/screenshotTest/roborazzi"))
}

val verifyScrcpyServer by tasks.registering {
    group = "verification"
    description = "Verifies the pinned upstream scrcpy-server release binary."
    val source = rootProject.project(":data:mirror").layout.projectDirectory
        .file("src/commonMain/resources/scrcpy/scrcpy-server")
    inputs.file(source)
    doLast {
        val file = source.asFile
        check(file.isFile && file.length() > 0) { "Pinned scrcpy-server binary is missing at ${source.asFile}" }
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
        }
        val digest = messageDigest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        // Official Genymobile scrcpy-server-v4.0 release hash.
        check(digest == "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a") {
            "Pinned scrcpy-server SHA-256 mismatch: $digest"
        }
    }
}

tasks.named<Copy>("desktopProcessResources") {
    dependsOn(
        "buildAndyNotificationsJniMacArm64",
        "buildAndyNotificationsJniMacX64",
        "buildAndyVoiceJniMacArm64",
        "buildAndyVoiceJniMacX64",
        "buildAndyBrowserJniMacArm64",
        "buildAndyBrowserJniMacX64",
        verifyScrcpyServer,
        ":data:mirror:desktopProcessResources",
    )
    from(layout.buildDirectory.dir("native/andy-notifications")) {
        include("**/andy-notifications-jni.dylib")
        into("andy-notifications")
    }
    from(layout.buildDirectory.dir("native/andy-browser")) {
        include("**/andy-browser-jni.dylib")
        into("andy-browser")
    }
    from(layout.buildDirectory.dir("native/andy-voice")) {
        include("**/andy-voice-jni.dylib")
        into("andy-voice")
    }
}

val buildAndyNotificationsJniMacArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS arm64 Notification Center bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-notifications/jni/andy_notifications_jni.m")
    val output = layout.buildDirectory.file("native/andy-notifications/macos-arm64/andy-notifications-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "arm64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AppKit",
        "-framework", "ApplicationServices",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyBrowserJniMacArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS arm64 WKWebView browser overlay bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-browser/jni/andy_browser_jni.m")
    val output = layout.buildDirectory.file("native/andy-browser/macos-arm64/andy-browser-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "arm64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AppKit",
        "-framework", "WebKit",
        "-framework", "QuartzCore",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyVoiceJniMacArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS arm64 AVFoundation microphone TCC bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-voice/jni/andy_voice_jni.m")
    val output = layout.buildDirectory.file("native/andy-voice/macos-arm64/andy-voice-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "arm64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AVFoundation",
        "-framework", "Foundation",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyNotificationsJniMacX64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS x64 Notification Center bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-notifications/jni/andy_notifications_jni.m")
    val output = layout.buildDirectory.file("native/andy-notifications/macos-x86_64/andy-notifications-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("x86_64", "amd64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "x86_64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AppKit",
        "-framework", "ApplicationServices",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyBrowserJniMacX64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS x64 WKWebView browser overlay bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-browser/jni/andy_browser_jni.m")
    val output = layout.buildDirectory.file("native/andy-browser/macos-x86_64/andy-browser-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("x86_64", "amd64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "x86_64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AppKit",
        "-framework", "WebKit",
        "-framework", "QuartzCore",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyVoiceJniMacX64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS x64 AVFoundation microphone TCC bridge."
    val source = rootProject.layout.projectDirectory.file("native/andy-voice/jni/andy_voice_jni.m")
    val output = layout.buildDirectory.file("native/andy-voice/macos-x86_64/andy-voice-jni.dylib")
    inputs.file(source)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("x86_64", "amd64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "x86_64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        source.asFile.absolutePath,
        "-framework", "AVFoundation",
        "-framework", "Foundation",
        "-o", output.get().asFile.absolutePath,
    )
}

// Enable test retries via an explicit Gradle property the CI workflow passes
// (`-PandyRetryTests=true`). A property is propagated to the daemon reliably, unlike env vars
// such as CI, which are not part of daemon compatibility and can be missing on the runner — that
// is why the earlier `System.getenv("CI")`-gated retry never fired on GitHub CI. The property name
// is intentionally dot-free: PowerShell (the Windows CI shell) splits `-Pfoo.bar=true` at the dot.
// The env check is kept as a best-effort fallback for other CI setups.
val andyRetryTests =
    providers.gradleProperty("andyRetryTests").map(String::toBoolean).getOrElse(false) ||
        System.getenv("CI") != null

// Ordinary `desktopTest` / `check` should not also run the Roborazzi suite. Those captures are
// slow, silent for long stretches, and already covered by verify/recordRoborazziDesktop on macOS.
// Detect Roborazzi task names from the start parameters so `./gradlew verifyRoborazziDesktop`
// still exercises AndyDesktopScreenshotTest through the shared desktopTest task.
val isRoborazziTaskRequested =
    gradle.startParameter.taskNames.any { it.contains("Roborazzi", ignoreCase = true) }

// Roborazzi Compose captures must stay single-process (shared renderer). Ordinary desktopTest
// stays single-fork by default: parallel JVMs stress shared PTY/tmux/AWT surfaces on CI.
// Opt in with -PandyDesktopTestParallelForks=N; TmuxAndy uniquifies sockets + launch-script dirs
// per org.gradle.test.worker so forks do not kill each other's sessions.
val andyDesktopTestParallelForks =
    providers.gradleProperty("andyDesktopTestParallelForks")
        .map { it.toInt().coerceAtLeast(1) }
        .getOrElse(1)

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    systemProperty("java.awt.headless", "false")
    jvmArgs("--add-exports=jdk.unsupported.desktop/jdk.swing.interop=ALL-UNNAMED")
    // Forward -Dandy.bench* / -Dandy.terminal.* into the test JVM for pipeline benchmarks.
    listOf(
        "andy.bench",
        "andy.bench.fps",
        "andy.bench.knobs",
        "andy.bench.warmupSec",
        "andy.bench.measureSec",
        "andy.bench.stream",
        "andy.terminal.repaint.fps",
        "andy.terminal.repaint.renderWindowMs",
        "andy.terminal.performanceMode",
        "andy.terminal.detectFilePaths",
        "andy.rust.term.bench",
        "andy.terminal.engine.dylib",
        "andy.terminal.engine",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // Opt-in live macOS whisper bottle install smoke (downloads Homebrew bottles).
    System.getProperty("andy.voice.live.smoke")?.let { systemProperty("andy.voice.live.smoke", it) }
    System.getProperty("andy.voice.live.home")?.let { systemProperty("andy.voice.live.home", it) }
    // Keep desktopTest off the live `tmux -L andy` socket. Tests that recycle a poisoned
    // server call kill-server; sharing production would print `[server exited]` in every
    // attached agent chat. Socket name is further uniquified per Gradle test worker.
    if (name == "desktopTest") {
        environment("ANDY_TMUX_SOCKET", "andy-test")
        // Most desktop service tests inject fake terminal adapters. Keep those fixtures
        // on their intended lane; ACP behavior is covered by the dedicated ACP tests.
        environment("ANDY_AGENT_LANE", "terminal")
        // Orchestrating Andy/Cursor sessions inject ANDY_TASK_ID / ANDY_PROJECT_ROOT into
        // the agent environment. Clear them for the test JVM so status-hook scripts and
        // cwd-scoped fixtures are not redirected to the parent chat's task id.
        environment("ANDY_TASK_ID", "")
        environment("ANDY_PROJECT_ROOT", "")
        // Concurrent Gradle that cleans/recompiles while desktopTest runs can produce mass
        // NoClassDefFoundError / missing binary result files (classpath race). Run the suite
        // exclusively — see docs/TESTS.md. Not a product failure; do not quarantine suites.
        if (!isRoborazziTaskRequested) {
            filter.excludeTestsMatching("app.andy.AndyDesktopScreenshotTest")
            maxParallelForks = andyDesktopTestParallelForks
        }
        // Per-test progress: the suite is long and otherwise prints nothing for many minutes,
        // which looks like a hang locally.
        testLogging {
            events("started", "passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    // These desktop suites include real-subprocess agent/workflow tests and hardware-backed
    // mirror/simulator smoke tests whose timing is inherently variable on shared CI runners.
    // The assertions are being hardened to poll for conditions rather than sleep fixed windows,
    // but a retry keeps a lone residual flake from turning the whole PR red and forcing a manual
    // re-run. Off by default locally so local runs still surface flakiness honestly.
    if (andyRetryTests) {
        retry {
            maxRetries.set(2)
            // Circuit breaker: if this many distinct tests fail, treat it as a real breakage
            // (compile/env/systemic) and stop retrying instead of burning CI minutes.
            maxFailures.set(20)
            // A test that fails then passes on retry must not fail the build — that is the
            // whole point of the net. Retried tests are still reported for visibility.
            failOnPassedAfterRetry.set(false)
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.andy.desktop.MainKt"
        // On macOS, the JDK's posix_spawn helper can fail with a JDK version
        // mismatch. Use the direct fork path so Andy can still launch agent CLIs.
        jvmArgs += "-Djdk.lang.Process.launchMechanism=FORK"
        jvmArgs += "--add-opens=java.base/java.lang=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
        jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
        // MacAwtWindow.nsWindowNumber peers into sun.lwawt for WKWebView/Metal overlays.
        jvmArgs += "--add-exports=java.base/java.lang=ALL-UNNAMED"
        jvmArgs += "--add-exports=java.desktop/sun.awt=ALL-UNNAMED"
        jvmArgs += "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "java.sql", // SQLDelight JdbcSqliteDriver (agent/project store)
                "jdk.unsupported",
            )
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = andyPackageName
            packageVersion = andyJpackagePackageVersion
            description = "Android emulator and device companion"
            vendor = "Andy"
            macOS {
                bundleID = andyPackageId
                iconFile.set(project.file("src/desktopMain/resources/icons/andy.icns"))
                // Overrides the compose plugin's bundled default-entitlements.plist, which only
                // grants JIT/unsigned-memory/library-validation. Without camera+mic entries here,
                // jpackage's forced hardened runtime silently blocks device access on every build
                // (dev runDistributable included), not just the signed release path.
                entitlementsFile.set(rootProject.file("packaging/macos/entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCameraUsageDescription</key>
                        <string>Andy uses the camera permission to receive video from a connected iPhone.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Andy uses the microphone for voice dictation in the chat composer.</string>
                    """.trimIndent()
                }
                signing {
                    identity.set(
                        providers.gradleProperty("compose.desktop.mac.signing.identity")
                            .map { it.removePrefix("Developer ID Application: ")
                                     .removePrefix("Developer ID Installer: ")
                                     .removePrefix("3rd Party Mac Developer Application: ")
                                     .removePrefix("3rd Party Mac Developer Installer: ") }
                    )
                }
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/andy.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/andy.png"))
            }
        }
    }
}

val stripMacReleaseFfmpegExecutables by tasks.registering {
    dependsOn("createReleaseDistributable")

    doLast {
        val releaseAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
        val ffmpegJars = fileTree(releaseAppDir) {
            include("**/Contents/app/ffmpeg-*-macosx-*.jar")
        }.files

        ffmpegJars.forEach { jarFile ->
            val replacement = temporaryDir.resolve(jarFile.name)
            var removedEntries = 0

            ZipFile(jarFile).use { source ->
                ZipOutputStream(replacement.outputStream().buffered()).use { target ->
                    source.entries().asSequence().forEach { entry ->
                        if (entry.name.endsWith("/ffmpeg") || entry.name.endsWith("/ffprobe")) {
                            removedEntries++
                            return@forEach
                        }

                        val nextEntry = ZipEntry(entry.name).apply {
                            time = entry.time
                            comment = entry.comment
                            setExtra(entry.extra)
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        }
                        target.putNextEntry(nextEntry)
                        source.getInputStream(entry).use { input ->
                            input.copyTo(target)
                        }
                        target.closeEntry()
                    }
                }
            }

            if (removedEntries > 0) {
                replacement.copyTo(jarFile, overwrite = true)
                logger.lifecycle("Removed $removedEntries notarization-blocking FFmpeg executables from ${jarFile.name}")
            }
        }
    }
}

val hardenMacReleasePty4jSpawnHelper by tasks.registering {
    dependsOn(stripMacReleaseFfmpegExecutables)

    val signingIdentity = providers.gradleProperty("compose.desktop.mac.signing.identity")
    val signingKeychain = providers.gradleProperty("compose.desktop.mac.signing.keychain")

    onlyIf {
        System.getProperty("os.name").contains("mac", ignoreCase = true) &&
            !signingIdentity.orNull.isNullOrBlank()
    }

    doLast {
        val releaseAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
        val pty4jJars = fileTree(releaseAppDir) {
            include("**/Contents/app/pty4j-*.jar")
        }.files
        val identity = signingIdentity.get()
        val keychainArgs = signingKeychain.orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("--keychain", it) }
            .orEmpty()
        val helperEntry = "resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper"

        fun runCommand(command: List<String>, workingDirectory: File? = null) {
            val builder = ProcessBuilder(command).inheritIO()
            workingDirectory?.let(builder::directory)
            val exitCode = builder.start().waitFor()
            if (exitCode != 0) {
                error("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
            }
        }

        pty4jJars.forEach { jarFile ->
            ZipFile(jarFile).use { source ->
                if (source.getEntry(helperEntry) == null) return@forEach
            }

            val extractionDir = temporaryDir.resolve(jarFile.nameWithoutExtension).apply {
                deleteRecursively()
                mkdirs()
            }
            val replacement = temporaryDir.resolve("${jarFile.name}.signed")
            replacement.delete()

            runCommand(listOf("unzip", "-q", jarFile.absolutePath, "-d", extractionDir.absolutePath))
            val helper = extractionDir.resolve(helperEntry)
            check(helper.isFile) { "Could not extract $helperEntry from ${jarFile.name}" }
            helper.setExecutable(true)
            runCommand(
                listOf(
                    "codesign",
                    "--force",
                    "--options",
                    "runtime",
                    "--timestamp",
                    "--sign",
                    identity,
                ) + keychainArgs + helper.absolutePath
            )
            runCommand(listOf("codesign", "--verify", "--strict", "--verbose=2", helper.absolutePath))
            runCommand(listOf("zip", "-qry", replacement.absolutePath, "."), extractionDir)
            replacement.copyTo(jarFile, overwrite = true)
            logger.lifecycle("Signed the hardened-runtime Pty4J spawn helper in ${jarFile.name}")
        }
    }
}

val resignMacReleaseApp by tasks.registering {
    dependsOn(hardenMacReleasePty4jSpawnHelper)

    val signingIdentity = providers.gradleProperty("compose.desktop.mac.signing.identity")
    val signingKeychain = providers.gradleProperty("compose.desktop.mac.signing.keychain")

    onlyIf {
        System.getProperty("os.name").contains("mac", ignoreCase = true) &&
            !signingIdentity.orNull.isNullOrBlank()
    }

    doLast {
        val releaseAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
        val apps = releaseAppDir.listFiles { file -> file.isDirectory && file.extension == "app" }.orEmpty()
        val identity = signingIdentity.get()
        val keychainArgs = signingKeychain.orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("--keychain", it) }
            .orEmpty()

        // Reuses the same file the jpackage DSL points at (see the `macOS { entitlementsFile }`
        // block above) so this re-sign step can never drift from what dev builds already ship.
        val entitlementsFile = rootProject.file("packaging/macos/entitlements.plist")

        fun runCommand(command: List<String>) {
            val exitCode = ProcessBuilder(command)
                .inheritIO()
                .start()
                .waitFor()
            if (exitCode != 0) {
                error("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
            }
        }

        apps.forEach { app ->
            runCommand(
                listOf(
                    "codesign",
                    "--force",
                    "--deep",
                    "--options",
                    "runtime",
                    "--entitlements",
                    entitlementsFile.absolutePath,
                    "--timestamp",
                    "--sign",
                    identity,
                ) + keychainArgs + app.absolutePath
            )
            runCommand(listOf("codesign", "--verify", "--deep", "--strict", "--verbose=2", app.absolutePath))
        }
    }
}

/**
 * Ad-hoc + hardened-runtime packages get silent TCC denial for microphone (Denied with no
 * System Settings row and no prompt). Re-sign the local distributable with a real identity
 * whenever one is configured or discoverable so `runDistributable` can actually request mic.
 */
val resignMacDistributable by tasks.registering {
    dependsOn("createDistributable")
    group = "compose desktop"
    description = "Re-sign the local macOS distributable so microphone TCC can prompt."

    val signingIdentityProp = providers.gradleProperty("compose.desktop.mac.signing.identity")
    val signingKeychain = providers.gradleProperty("compose.desktop.mac.signing.keychain")

    onlyIf {
        System.getProperty("os.name").contains("mac", ignoreCase = true)
    }

    doLast {
        fun discoverDeveloperIdIdentity(): String? {
            val proc = ProcessBuilder("security", "find-identity", "-v", "-p", "codesigning")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val match = Regex("\"(Developer ID Application: [^\"]+)\"").find(out)
            return match?.groupValues?.get(1)
        }

        val identity = signingIdentityProp.orNull?.takeIf { it.isNotBlank() }
            ?: discoverDeveloperIdIdentity()
            ?: run {
                logger.lifecycle(
                    "resignMacDistributable: no Developer ID identity found; " +
                        "leaving ad-hoc signature (mic TCC will silently deny).",
                )
                return@doLast
            }

        val appDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val apps = appDir.listFiles { file -> file.isDirectory && file.extension == "app" }.orEmpty()
        if (apps.isEmpty()) {
            logger.warn("resignMacDistributable: no .app found under ${appDir.absolutePath}")
            return@doLast
        }
        val keychainArgs = signingKeychain.orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("--keychain", it) }
            .orEmpty()
        val entitlementsFile = rootProject.file("packaging/macos/entitlements.plist")

        fun runCommand(command: List<String>) {
            val exitCode = ProcessBuilder(command)
                .inheritIO()
                .start()
                .waitFor()
            if (exitCode != 0) {
                error("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
            }
        }

        apps.forEach { app ->
            logger.lifecycle("resignMacDistributable: signing ${app.name} with $identity")
            runCommand(
                listOf(
                    "codesign",
                    "--force",
                    "--deep",
                    "--options",
                    "runtime",
                    "--entitlements",
                    entitlementsFile.absolutePath,
                    "--sign",
                    identity,
                ) + keychainArgs + app.absolutePath
            )
            runCommand(listOf("codesign", "--verify", "--deep", "--strict", "--verbose=2", app.absolutePath))
        }
    }
}

tasks.matching { it.name == "runDistributable" }.configureEach {
    dependsOn(resignMacDistributable)
}

tasks.matching { it.name in setOf("packageReleaseDmg", "notarizeReleaseDmg") }
    .configureEach {
        dependsOn(resignMacReleaseApp)
    }

// Headless daemon entry point (`andyd`).
tasks.register<JavaExec>("runAndyd") {
    group = "application"
    description = "Run the headless Andy daemon (andyd) with MCP unix socket"
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopCompilation.compileTaskProvider)
    dependsOn(":agent-store:jar")
    mainClass.set("app.andy.desktop.AndydMainKt")
    classpath = files(
        desktopCompilation.output.allOutputs,
        desktopCompilation.runtimeDependencyFiles,
    )
    jvmArgs(
        "-Djdk.lang.Process.launchMechanism=FORK",
        // Headless daemon: no Dock icon / AWT UI from Compose desktop deps.
        "-Dapple.awt.UIElement=true",
        "-Djava.awt.headless=true",
    )
    val pathSep = System.getProperty("path.separator") ?: ":"
    environment(
        "PATH",
        listOfNotNull(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            System.getenv("PATH"),
        ).joinToString(pathSep),
    )
}

tasks.register("killAndyd") {
    group = "application"
    description = "Stop a running andyd daemon (SIGTERM via ~/.andy/andyd.pid)"
    doLast {
        val andyHome = file("${System.getProperty("user.home")}/.andy")
        val pidFile = file("${andyHome}/andyd.pid")
        val sockFile = file("${andyHome}/andyd.sock")
        if (!pidFile.isFile) {
            println("andyd not running (no pidfile ${pidFile.absolutePath})")
            return@doLast
        }
        val pidText = pidFile.readText().trim()
        val pid = pidText.toLongOrNull()
        if (pid == null) {
            println("andyd pidfile unreadable ($pidText); removing stale lock files")
            pidFile.delete()
            sockFile.delete()
            return@doLast
        }
        val handle = ProcessHandle.of(pid)
        if (handle.isEmpty || !handle.get().isAlive) {
            println("andyd pid $pid not alive; removing stale lock files")
            pidFile.delete()
            sockFile.delete()
            return@doLast
        }
        val proc = handle.get()
        println("Stopping andyd pid=$pid …")
        proc.destroy()
        val deadline = System.currentTimeMillis() + 10_000
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (proc.isAlive) {
            println("andyd pid=$pid still alive; sending SIGKILL")
            proc.destroyForcibly()
            val hardDeadline = System.currentTimeMillis() + 5_000
            while (proc.isAlive && System.currentTimeMillis() < hardDeadline) {
                Thread.sleep(100)
            }
        }
        if (proc.isAlive) {
            throw GradleException("Failed to stop andyd pid=$pid")
        }
        // Shutdown hook normally cleans these; clear leftovers if needed.
        pidFile.delete()
        sockFile.delete()
        println("andyd stopped")
    }
}

tasks.register<Copy>("installAndydLaunchAgent") {
    group = "distribution"
    description = "Copy the andyd launchd plist into ~/Library/LaunchAgents"
    from(rootProject.file("packaging/macos/com.joetr.andyd.plist"))
    into("${System.getProperty("user.home")}/Library/LaunchAgents")
}

tasks.register<Exec>("buildAndyCli") {
    group = "build"
    description = "Build the release andy CLI binary (cli/andy)"
    workingDir = rootProject.layout.projectDirectory.dir("cli/andy").asFile
    val pathSep = System.getProperty("path.separator") ?: ":"
    val cargoHomeBin = file("${System.getProperty("user.home")}/.cargo/bin")
    val pathWithCargo = listOfNotNull(
        cargoHomeBin.takeIf { it.isDirectory }?.absolutePath,
        "/opt/homebrew/bin",
        "/usr/local/bin",
        System.getenv("PATH"),
    ).joinToString(pathSep)
    environment("PATH", pathWithCargo)
    // Align `andy --version` with AndyBuildInfo / gradle.properties.
    environment("ANDY_VERSION", andyVersionName)
    val cargo = sequenceOf(
        file("${cargoHomeBin.absolutePath}/cargo"),
        file("/opt/homebrew/bin/cargo"),
        file("/usr/local/bin/cargo"),
    ).firstOrNull { it.isFile }
        ?: error(
            "cargo not found. Install Rust (https://rustup.rs) so ~/.cargo/bin/cargo exists, " +
                "then re-run ./gradlew installAndyCli",
        )
    commandLine(cargo.absolutePath, "build", "--release")
}

tasks.register<Copy>("installAndyCli") {
    group = "distribution"
    description = "Install release andy CLI to ~/.andy/bin/andy"
    dependsOn("buildAndyCli")
    from(rootProject.layout.projectDirectory.file("cli/andy/target/release/andy"))
    into("${System.getProperty("user.home")}/.andy/bin")
    filePermissions {
        user {
            read = true
            write = true
            execute = true
        }
        group {
            read = true
            execute = true
        }
        other {
            read = true
            execute = true
        }
    }
    doLast {
        val binDir = file("${System.getProperty("user.home")}/.andy/bin")
        val dest = file("$binDir/andy")
        // Gradle Copy invalidates the cargo adhoc signature; macOS then
        // SIGKILLs the binary ("Code Signature Invalid") until re-signed.
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            val codesign = ProcessBuilder("codesign", "--force", "--sign", "-", dest.absolutePath)
                .inheritIO()
                .start()
            check(codesign.waitFor() == 0) { "codesign failed for ${dest.absolutePath}" }
        }

        val hookSrc = rootProject.file("scripts/andy-status-hook.sh")
        check(hookSrc.isFile) { "missing ${hookSrc.path}" }
        val hookDest = file("$binDir/andy-status-hook.sh")
        hookDest.writeText(hookSrc.readText())
        hookDest.setExecutable(true, false)

        val piExtSrc = rootProject.file("scripts/pi-andy-extension.ts")
        check(piExtSrc.isFile) { "missing ${piExtSrc.path}" }
        val piExtDest = file("${System.getProperty("user.home")}/.andy/pi/andy-extension.ts")
        piExtDest.parentFile.mkdirs()
        piExtDest.writeText(piExtSrc.readText())

        val ocPluginSrc = rootProject.file("scripts/opencode-andy-status.js")
        check(ocPluginSrc.isFile) { "missing ${ocPluginSrc.path}" }
        // Canonical copy next to the status hook for reference; projects get a
        // fresh install from AndyOpenCodePluginInstaller on session start.
        val ocPluginDest = file("${System.getProperty("user.home")}/.andy/opencode/andy-status.js")
        ocPluginDest.parentFile.mkdirs()
        ocPluginDest.writeText(ocPluginSrc.readText())

        val releaseMeta = file("${System.getProperty("user.home")}/.andy/installed-release.json")
        releaseMeta.parentFile.mkdirs()
        releaseMeta.writeText(
            """
            {
              "version": "$andyVersionName",
              "releasePageUrl": null,
              "installedAtEpochMs": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n",
        )

        println("Installed ${dest.absolutePath}")
        println("Installed ${hookDest.absolutePath}")
        println("Installed ${piExtDest.absolutePath}")
        println("Installed ${ocPluginDest.absolutePath}")
        println("Recorded ${releaseMeta.absolutePath}")
        println("Add ~/.andy/bin to PATH permanently if needed:")
        println("  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.zshrc   # zsh")
        println("  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.bashrc  # bash")
    }
}

val andydFatJar = tasks.register<Jar>("andydFatJar") {
    group = "distribution"
    description = "Fat JAR for the headless andyd daemon"
    archiveBaseName.set("andyd")
    archiveVersion.set(andyVersionName)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Runtime classpath exceeds the classic ZIP entry limit once webchat + web-push
    // deps are included; Zip64 is required for a valid standalone andyd.jar.
    isZip64 = true
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopCompilation.compileTaskProvider)
    // allOutputs alone does not always pull processResources into the task graph when
    // compile is UP-TO-DATE; without this, standalone andyd.jar can omit webchat/.
    dependsOn("desktopProcessResources")
    dependsOn(":agent-store:jar")
    from(desktopCompilation.output.classesDirs)
    from(desktopCompilation.output.resourcesDir)
    from({
        desktopCompilation.runtimeDependencyFiles?.filter { it.isFile }?.map { zipTree(it) } ?: emptyArray<File>()
    })
    manifest {
        attributes["Main-Class"] = "app.andy.desktop.AndydMainKt"
    }
}

tasks.register("installAndyd") {
    group = "distribution"
    description = "Install andyd launcher + fat JAR to ~/.andy"
    dependsOn(andydFatJar)
    doLast {
        val andyHome = file("${System.getProperty("user.home")}/.andy")
        val binDir = file("$andyHome/bin")
        val runtimeDir = file("$andyHome/andyd")
        binDir.mkdirs()
        runtimeDir.mkdirs()

        val jarDest = file("$runtimeDir/andyd.jar")
        andydFatJar.get().archiveFile.get().asFile.copyTo(jarDest, overwrite = true)

        val launcherSrc = rootProject.file("scripts/andyd-launcher.sh")
        check(launcherSrc.isFile) { "missing ${launcherSrc.path}" }
        val launcherDest = file("$binDir/andyd")
        launcherDest.writeText(launcherSrc.readText())
        launcherDest.setExecutable(true, false)

        val releaseMeta = file("$andyHome/installed-release.json")
        releaseMeta.writeText(
            """
            {
              "version": "$andyVersionName",
              "releasePageUrl": null,
              "installedAtEpochMs": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n",
        )

        println("Installed ${launcherDest.absolutePath}")
        println("Installed ${jarDest.absolutePath}")
        println("Recorded ${releaseMeta.absolutePath}")
        println("Add ~/.andy/bin to PATH permanently if needed:")
        println("  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.zshrc   # zsh")
        println("  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.bashrc  # bash")
    }
}
