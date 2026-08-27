import andy.libs

plugins {
    id("andy.kmp.library")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("serialization-json").get())
                implementation(libs.findLibrary("compose-runtime").get())
            }
        }
    }
}
