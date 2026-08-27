import andy.ffmpegPlatformClassifier

plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:agents"))
            implementation(project(":data:mirror"))
            implementation(project(":data:platform-tools"))
            implementation(libs.javacv)
            implementation(libs.ffmpeg)
            implementation("${libs.ffmpeg.get()}:${ffmpegPlatformClassifier()}")
        }
        desktopTest.dependencies {
            implementation(project(":data:updates"))
        }
    }
}
