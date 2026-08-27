import andy.libs

plugins {
    id("andy.compose.library")
}

kotlin {
    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":ui:core"))
                implementation(libs.findLibrary("coroutines-core").get())
                implementation(libs.findLibrary("markdown-renderer").get())
                implementation(libs.findLibrary("markdown-renderer-m3").get())
                implementation(libs.findLibrary("markdown-renderer-code").get())
            }
        }
    }
}
