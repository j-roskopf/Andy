plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":data:agents"))
            implementation(project(":data:remote"))
            implementation(project(":data:workspace"))
        }
    }
}
