plugins {
    id("andy.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
    }
}
