import andy.libs

plugins {
    id("andy.kmp.library")
    kotlin("plugin.serialization")
    id("andy.metro")
}

kotlin {
    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(project(":domain"))
                implementation(libs.findLibrary("coroutines-core").get())
                implementation(libs.findLibrary("serialization-json").get())
            }
        }
        named("commonTest") {
            dependencies {
                implementation(libs.findLibrary("coroutines-test").get())
            }
        }
        named("desktopTest") {
            dependencies {
                implementation(libs.findLibrary("coroutines-test").get())
            }
        }
    }
}
