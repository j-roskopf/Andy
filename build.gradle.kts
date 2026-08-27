plugins {
    base
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.testRetry) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.metro) apply false
}

import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

plugins.withType<NodeJsPlugin> {
    extensions.configure<NodeJsEnvSpec>("nodejs") {
        version.set("22.22.0")
    }
}
plugins.withType<WasmNodeJsPlugin> {
    extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
        version.set("22.22.0")
        download.set(true)
    }
}

subprojects {
    plugins.withType<NodeJsPlugin> {
        extensions.configure<NodeJsEnvSpec>("nodejs") {
            version.set("22.22.0")
        }
    }
    plugins.withType<WasmNodeJsPlugin> {
        extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
            version.set("22.22.0")
            download.set(true)
        }
    }
}
