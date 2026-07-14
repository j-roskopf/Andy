plugins {
    // AGP 9.0 remains compatible with Andy's checked-in Gradle 9.3 wrapper.
    id("com.android.application") version "9.0.0"
}

android {
    namespace = "app.andy.latencyprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.andy.latencyprobe"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}
