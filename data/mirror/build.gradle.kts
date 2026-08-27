import andy.ffmpegPlatformClassifier

plugins {
    id("andy.compose.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":ui:components"))
        }
        wasmJsMain.dependencies {
            implementation(project(":core:platform"))
            implementation(libs.serialization.json)
        }
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:platform-tools"))
            implementation(libs.javacv)
            implementation(libs.ffmpeg)
            implementation("${libs.ffmpeg.get()}:${ffmpegPlatformClassifier()}")
            implementation(libs.grpc.api)
            implementation(libs.grpc.core)
            implementation(libs.grpc.netty.shaded)
            implementation(libs.grpc.stub)
        }
        desktopTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
            implementation(project(":core:testing"))
            implementation(project(":data:devices"))
            implementation(project(":feature:controls"))
        }
    }
}
