import andy.configureAndyKmp
import andy.libraryNamespace
import andy.libs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
}

kotlin {
    configureAndyKmp(this)

    android {
        namespace = libraryNamespace()
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = false
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("coroutines-core").get())
            }
        }
    }
}
