import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("io.github.takahirom.roborazzi") version "1.60.0"
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

val generateAndyBuildInfo = tasks.register("generateAndyBuildInfo") {
    val version = andyVersionName
    val outputDir = layout.buildDirectory.dir("generated/andyBuildInfo")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("app/andy/updates/AndyBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package app.andy.updates

            object AndyBuildInfo {
                const val versionName = "$version"
                const val githubOwner = "j-roskopf"
                const val githubRepo = "Andy"
            }
            """.trimIndent()
        )
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
                    open = false,
                )
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAndyBuildInfo)
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.components:components-resources:1.11.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("net.peanuuutz.tomlkt:tomlkt:0.4.0")
                implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
                implementation("org.jetbrains.jediterm:jediterm-core:3.73")
                implementation("org.jetbrains.jediterm:jediterm-ui:3.73")
                implementation("org.jetbrains.pty4j:pty4j:0.13.12")
                implementation("com.google.zxing:core:3.5.3")
                // Native tray (StatusNotifier on Linux). Compose AWT tray is broken on
                // Wayland; dorkbox needs libayatana-appindicator which isn't always present.
                implementation("io.github.kdroidfilter:composenativetray:1.3.3")

                // MCP and Ktor Server Dependencies
                implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")
                implementation("io.ktor:ktor-server-core:3.0.1")
                implementation("io.ktor:ktor-server-cio:3.0.1")
                implementation("io.ktor:ktor-server-netty:3.0.1")
                implementation("io.ktor:ktor-server-sse:3.0.1")
                implementation("io.ktor:ktor-server-double-receive:3.0.1")
                implementation("io.grpc:grpc-api:1.69.0")
                implementation("io.grpc:grpc-core:1.69.0")
                implementation("io.grpc:grpc-netty-shaded:1.69.0")
                implementation("io.grpc:grpc-stub:1.69.0")

                // Add the base JavaCV library
                implementation("org.bytedeco:javacv:1.5.11")

                // Add base library for FFmpeg
                implementation("org.bytedeco:ffmpeg:7.1-1.5.11")

                // Add platform-specific native binaries for FFmpeg
                if (hostPlatform == "all") {
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-arm64")
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-x86_64")
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:windows-x86_64")
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:linux-x86_64")
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:linux-arm64")
                } else {
                    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:$hostPlatform")
                }
            }
        }
        val wasmJsMain by getting {
            dependencies {
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
            }
        }
    }
}

// Use the developer or CI machine's Node installation. Besides avoiding a redundant
// runtime download, this keeps the build compatible with settings-level-only repositories.
extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
    download.set(false)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/screenshotTest/roborazzi"))
}

// Compose Desktop screenshots share one renderer per process. Running them serially
// keeps their viewport, fonts, and image output deterministic on every CI runner.
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    systemProperty("java.awt.headless", "false")
}

compose.desktop {
    application {
        mainClass = "app.andy.desktop.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            modules("java.instrument", "java.management", "java.net.http", "jdk.unsupported")
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

        val entitlementsFile = temporaryDir.resolve("entitlements.plist")
        entitlementsFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>com.apple.security.cs.allow-jit</key>
                <true/>
                <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
                <true/>
                <key>com.apple.security.cs.disable-library-validation</key>
                <true/>
            </dict>
            </plist>
            """.trimIndent()
        )

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

tasks.matching { it.name in setOf("packageReleaseDmg", "notarizeReleaseDmg") }
    .configureEach {
        dependsOn(resignMacReleaseApp)
    }
