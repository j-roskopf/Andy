plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:agents"))
            implementation(project(":data:platform-tools"))
        }
    }
}
