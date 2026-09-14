plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:workspace"))
            implementation(libs.serialization.json)
        }
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.serialization.json)
        }
    }
}
