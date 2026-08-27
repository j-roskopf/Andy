plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:devices"))
            implementation(project(":data:platform-tools"))
        }
        desktopTest.dependencies {
            implementation(project(":core:testing"))
            implementation(project(":data:workspace"))
        }
    }
}
