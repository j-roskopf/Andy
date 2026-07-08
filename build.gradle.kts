import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

val andyVersionName = providers.gradleProperty("andy.versionName").orElse("0.1.0").get()
val andyVersionCode = providers.gradleProperty("andy.versionCode").orElse("1").map { it.toInt() }.get()
val andyDebugDistribution = providers.gradleProperty("andy.debugDistribution")
    .orElse(providers.environmentVariable("ANDY_DEBUG_DISTRIBUTION"))
    .map(String::toBoolean)
    .orElse(false)
val andyAppName = "Andy"
val andyPackageId = if (andyDebugDistribution.get()) "com.joetr.andy.debug" else "com.joetr.andy"
val andyMacEntitlementsFile = layout.projectDirectory.file("packaging/macos/entitlements.plist")

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

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAndyBuildInfo)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.andy.desktop.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = andyAppName
            packageVersion = andyJpackagePackageVersion
            description = "Android emulator and device companion"
            vendor = "Andy"
            macOS {
                bundleID = andyPackageId
                iconFile.set(project.file("src/desktopMain/resources/icons/andy.icns"))
                entitlementsFile.set(andyMacEntitlementsFile)
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
                packageName = andyPackageId
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

val resignMacReleaseApp by tasks.registering {
    dependsOn(stripMacReleaseFfmpegExecutables)

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
                    andyMacEntitlementsFile.asFile.absolutePath,
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
