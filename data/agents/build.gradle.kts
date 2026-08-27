import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec

plugins {
    id("andy.data")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    add("desktopMainImplementation", project(":agent-store"))
}

val andyTerminalEngineCrate = rootProject.layout.projectDirectory.dir("native/andy-terminal-engine")
val andyTerminalEngineUniffiOut =
    layout.buildDirectory.dir("generated/andy-terminal-engine/uniffi")

fun andyTerminalEngineHostSlice(): Pair<String, String> {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "macos-arm64"
                "x86_64", "amd64" -> "macos-x86_64"
                else -> error("Unsupported macOS arch for andy-terminal-engine: $arch")
            }
            slice to "libandy_terminal_engine.dylib"
        }
        os.contains("linux") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "linux-arm64"
                "x86_64", "amd64" -> "linux-x86_64"
                else -> error("Unsupported Linux arch for andy-terminal-engine: $arch")
            }
            slice to "libandy_terminal_engine.so"
        }
        os.contains("windows") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "windows-arm64"
                "x86_64", "amd64" -> "windows-x86_64"
                else -> error("Unsupported Windows arch for andy-terminal-engine: $arch")
            }
            slice to "andy_terminal_engine.dll"
        }
        else -> error("Unsupported OS for andy-terminal-engine: $os")
    }
}

fun andyTerminalEngineCargoArtifactName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("windows") -> "andy_terminal_engine.dll"
        os.contains("linux") -> "libandy_terminal_engine.so"
        else -> "libandy_terminal_engine.dylib"
    }
}

val buildAndyTerminalEngineNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the alacritty_terminal JNI/UniFFI native library via cargo for the host OS."
    workingDir = andyTerminalEngineCrate.asFile
    inputs.files(
        andyTerminalEngineCrate.file("Cargo.toml"),
        andyTerminalEngineCrate.file("Cargo.lock"),
        andyTerminalEngineCrate.file("uniffi-bindgen.rs"),
    )
    inputs.dir(andyTerminalEngineCrate.dir("src"))
    val (slice, fileName) = andyTerminalEngineHostSlice()
    val staged = layout.buildDirectory.file("native/andy-terminal-engine/$slice/$fileName")
    outputs.file(staged)
    commandLine("cargo", "build", "--release", "--features", "uniffi-cli")
    doLast {
        val cargoOut = andyTerminalEngineCrate.file("target/release/${andyTerminalEngineCargoArtifactName()}").asFile
        check(cargoOut.isFile) { "cargo did not produce ${cargoOut.absolutePath}" }
        val dest = staged.get().asFile
        dest.parentFile.mkdirs()
        cargoOut.copyTo(dest, overwrite = true)
    }
}

val generateAndyTerminalEngineUniffi by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates UniFFI Kotlin bindings for the terminal-engine FFI probe."
    dependsOn(buildAndyTerminalEngineNative)
    workingDir = andyTerminalEngineCrate.asFile
    val cargoLib = andyTerminalEngineCrate.file("target/release/${andyTerminalEngineCargoArtifactName()}")
    inputs.file(cargoLib)
    outputs.dir(andyTerminalEngineUniffiOut)
    onlyIf {
        System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
    }
    doFirst {
        andyTerminalEngineUniffiOut.get().asFile.mkdirs()
    }
    commandLine(
        "cargo",
        "run",
        "--release",
        "--features",
        "uniffi-cli",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        cargoLib.asFile.absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        andyTerminalEngineUniffiOut.get().asFile.absolutePath,
        "--no-format",
    )
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:network"))
            implementation(project(":data:platform-tools"))
            implementation(project(":data:workspace"))
            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.acp)
            implementation(libs.pty4j)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.double.receive)
            implementation(libs.ktor.server.websockets)
            implementation(libs.web.push)
            implementation(libs.bouncycastle)
            implementation(libs.httpclient)
            implementation(libs.httpasyncclient)
            implementation(libs.tomlkt)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.ui)
        }
        desktopTest.dependencies {
            implementation(project(":agent-store"))
            implementation(project(":ui:core"))
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        wasmJsMain.dependencies {
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.compose.runtime)
        }
        val desktopTest by getting {
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                kotlin.srcDir("src/desktopTestMacOs/kotlin")
                kotlin.srcDir(andyTerminalEngineUniffiOut)
            }
        }
    }
}

tasks.named("compileTestKotlinDesktop") {
    if (System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }) {
        dependsOn(generateAndyTerminalEngineUniffi)
    }
}

tasks.named<Copy>("desktopProcessResources") {
    dependsOn(buildAndyTerminalEngineNative)
    from(layout.buildDirectory.dir("native/andy-terminal-engine")) {
        include(
            "**/libandy_terminal_engine.dylib",
            "**/libandy_terminal_engine.so",
            "**/andy_terminal_engine.dll",
        )
        into("andy-terminal-engine")
    }
}

tasks.named("desktopTest") {
    dependsOn("desktopProcessResources")
}
