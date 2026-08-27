plugins {
    id("andy.kmp.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":data:devices"))
                implementation(project(":data:mirror"))
                implementation(project(":data:agents"))
                implementation(project(":data:network"))
                implementation(project(":data:workspace"))
                implementation(project(":data:bug-capture"))
                implementation(project(":data:platform-tools"))
                implementation(project(":data:host"))
                implementation(project(":data:updates"))
                implementation(project(":data:remote"))
                implementation(project(":agent-store"))
            }
        }
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":core:platform"))
        }
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
