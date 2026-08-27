plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:agents"))
            implementation(project(":feature:live"))
            implementation(project(":data:mirror"))
        }
    }
}
