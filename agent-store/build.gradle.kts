plugins {
    kotlin("jvm")
    alias(libs.plugins.sqldelight)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
}

sqldelight {
    databases {
        create("AndyAgentDatabase") {
            packageName.set("app.andy.store")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
