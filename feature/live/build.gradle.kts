plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:controls"))
            implementation(project(":feature:logcat"))
            implementation(project(":data:mirror"))
        }
    }
}
