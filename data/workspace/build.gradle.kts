plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":ui:core"))
            implementation(libs.tomlkt)
        }
    }
}
