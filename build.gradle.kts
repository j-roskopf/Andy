plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

val andyVersionName = providers.gradleProperty("andy.versionName").orElse("0.1.0").get()

group = "app.andy"
version = andyVersionName

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
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
                implementation("org.bytedeco:javacv-platform:1.5.11")
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
            packageName = "Andy"
            packageVersion = andyVersionName
            description = "Android emulator and device companion"
            vendor = "Andy"
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icons/andy.icns"))
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
