plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            api(project(":data:network"))
            api(project(":data:mirror"))
            api(project(":data:bug-capture"))
            api(project(":data:devices"))
            api(project(":data:platform-tools"))
        }
    }
}
