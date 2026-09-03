plugins {
    id("andy.ui")
}

compose.resources {
    packageOfResClass = "app.andy.andy.generated.resources"
    publicResClass = true
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
            implementation(libs.coil.compose)
            implementation(libs.coil.svg)
        }
        desktopTest.dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.compose.ui.test.junit4)
        }
    }
}

val andyMermaidCrate = rootProject.layout.projectDirectory.dir("native/andy-mermaid")

fun andyMermaidHostSlice(): Pair<String, String> {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "macos-arm64"
                "x86_64", "amd64" -> "macos-x86_64"
                else -> error("Unsupported macOS arch for andy-mermaid: $arch")
            }
            slice to "libandy_mermaid.dylib"
        }
        os.contains("linux") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "linux-arm64"
                "x86_64", "amd64" -> "linux-x86_64"
                else -> error("Unsupported Linux arch for andy-mermaid: $arch")
            }
            slice to "libandy_mermaid.so"
        }
        os.contains("windows") -> {
            val slice = when (arch) {
                "aarch64", "arm64" -> "windows-arm64"
                "x86_64", "amd64" -> "windows-x86_64"
                else -> error("Unsupported Windows arch for andy-mermaid: $arch")
            }
            slice to "andy_mermaid.dll"
        }
        else -> error("Unsupported OS for andy-mermaid: $os")
    }
}

fun andyMermaidCargoArtifactName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("windows") -> "andy_mermaid.dll"
        os.contains("linux") -> "libandy_mermaid.so"
        else -> "libandy_mermaid.dylib"
    }
}

val buildAndyMermaidNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the mermaid JNI native library via cargo for the host OS."
    workingDir = andyMermaidCrate.asFile
    inputs.files(
        andyMermaidCrate.file("Cargo.toml"),
        andyMermaidCrate.file("Cargo.lock"),
    )
    inputs.dir(andyMermaidCrate.dir("src"))
    val (slice, fileName) = andyMermaidHostSlice()
    val staged = layout.buildDirectory.file("native/andy-mermaid/$slice/$fileName")
    outputs.file(staged)
    commandLine("cargo", "build", "--release")
    doLast {
        val cargoOut = andyMermaidCrate.file("target/release/${andyMermaidCargoArtifactName()}").asFile
        check(cargoOut.isFile) { "cargo did not produce ${cargoOut.absolutePath}" }
        val dest = staged.get().asFile
        dest.parentFile.mkdirs()
        cargoOut.copyTo(dest, overwrite = true)
    }
}

tasks.named<Copy>("desktopProcessResources") {
    dependsOn(buildAndyMermaidNative)
    from(layout.buildDirectory.dir("native/andy-mermaid")) {
        include(
            "**/libandy_mermaid.dylib",
            "**/libandy_mermaid.so",
            "**/andy_mermaid.dll",
        )
        into("andy-mermaid")
    }
}
