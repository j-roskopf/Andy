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
val andyPackageId = if (andyDebugDistribution.get()) "com.joetr.andy.debug" else "com.joetr.andy"

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
            packageName = andyPackageId
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
