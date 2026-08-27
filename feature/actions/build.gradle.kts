plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:agents"))
            implementation(project(":feature:live"))
        }
        desktopMain.dependencies {
            implementation(project(":data:agents"))
            implementation(project(":data:workspace"))
        }
    }
}
