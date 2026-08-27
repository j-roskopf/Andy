plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:live"))
        }
    }
}
