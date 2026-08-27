import andy.libs

plugins {
    id("andy.compose.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":navigation"))
                implementation(project(":core:platform"))
                implementation(project(":ui:core"))
                implementation(project(":ui:components"))
                implementation(libs.findLibrary("coroutines-core").get())
                implementation(libs.findLibrary("jetbrains-navigation3-ui").get())
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
                implementation(libs.findLibrary("compose-ui-test-junit4").get())
            }
        }
    }
}
