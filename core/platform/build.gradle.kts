plugins {
    id("andy.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":ui:core"))
            implementation(libs.serialization.json)
        }
        desktopMain.dependencies {
            implementation(libs.rsyntaxtextarea)
            compileOnly("junit:junit:4.13.2")
        }
        desktopTest.dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
    }
}
