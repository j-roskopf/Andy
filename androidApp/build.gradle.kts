plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation(compose.components.resources)

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.websockets)

    debugImplementation(compose.uiTooling)

    testImplementation("junit:junit:4.13.2")
}
