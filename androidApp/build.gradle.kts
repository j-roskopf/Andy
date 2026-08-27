plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.joetr.andy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.joetr.andy"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("andy.versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("andy.versionName").orElse("0.1.0").get()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":ui:shell"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
