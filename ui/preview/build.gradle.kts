plugins {
    id("andy.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:core"))
            implementation(project(":ui:components"))
            implementation(project(":domain"))
        }
    }
}
