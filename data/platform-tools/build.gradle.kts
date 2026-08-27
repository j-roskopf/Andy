plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.http)
            implementation(libs.zxing.core)
        }
        desktopTest.dependencies {
            implementation(project(":core:testing"))
        }
    }
}
