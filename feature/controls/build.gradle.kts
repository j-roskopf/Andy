plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":data:devices"))
            implementation(project(":ui:components"))
            
        }
    }
}
