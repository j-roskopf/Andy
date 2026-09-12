plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.metro)
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

    signingConfigs {
        create("release") {
            val keystorePath = providers.gradleProperty("andy.android.keystore.path")
                .orNull ?: providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            val keystoreFile = keystorePath?.let { file(it) }
            val keystorePassword = providers.gradleProperty("andy.android.keystore.password")
                .orNull ?: providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.gradleProperty("andy.android.key.alias")
                .orNull ?: providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
            val keyPassword = providers.gradleProperty("andy.android.key.password")
                .orNull ?: providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

            enableV1Signing = true
            enableV2Signing = true

            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":ui:core"))
    implementation(project(":ui:components"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jetbrains.navigation3.ui)
    // Kept for one-shot migration from EncryptedSharedPreferences.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.components.resources)

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.websockets)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
