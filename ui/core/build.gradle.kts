plugins {
    id("andy.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
    }
}
