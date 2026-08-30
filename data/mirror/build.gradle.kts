import andy.ffmpegPlatformClassifier
import java.security.MessageDigest
import org.gradle.api.tasks.Exec

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

val verifyScrcpyServer by tasks.registering {
    group = "verification"
    description = "Verifies the pinned upstream scrcpy-server release binary."
    val source = layout.projectDirectory.file("src/commonMain/resources/scrcpy/scrcpy-server")
    inputs.file(source)
    doLast {
        val file = source.asFile
        check(file.isFile && file.length() > 0) { "Pinned scrcpy-server binary is missing at ${source.asFile}" }
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
        }
        val digest = messageDigest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(digest == "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a") {
            "Pinned scrcpy-server SHA-256 mismatch: $digest"
        }
    }
}

val andyMirrorJniSources = listOf(
    rootProject.layout.projectDirectory.file("native/andy-mirror/jni/andy_mirror_jni.m"),
    rootProject.layout.projectDirectory.file("native/andy-mirror/jni/andy_mirror_hub.m"),
    rootProject.layout.projectDirectory.file("native/andy-mirror/jni/andy_ios_sim.m"),
    rootProject.layout.projectDirectory.file("native/andy-mirror/jni/andy_ios_device.m"),
)

val buildAndyMirrorJniMacArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS arm64 JAWT CAMetalLayer bridge used by the native mirror."
    val sources = andyMirrorJniSources
    val output = layout.buildDirectory.file("native/andy-mirror/macos-arm64/andy-mirror-jni.dylib")
    inputs.files(sources)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "arm64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        *sources.map { it.asFile.absolutePath }.toTypedArray(),
        "-framework", "AppKit",
        "-framework", "ApplicationServices",
        "-framework", "MetalKit",
        "-framework", "QuartzCore",
        "-framework", "Metal",
        "-framework", "VideoToolbox",
        "-framework", "CoreMedia",
        "-framework", "CoreVideo",
        "-framework", "AVFoundation",
        "-framework", "CoreMediaIO",
        "-framework", "IOSurface",
        "-L${System.getProperty("java.home")}/lib",
        "-ljawt",
        "-o", output.get().asFile.absolutePath,
    )
}

val buildAndyMirrorJniLinuxX64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Linux x86_64 NVDEC/Vulkan overlay used by the native GPU mirror hub."
    val output = layout.buildDirectory.file("native/andy-mirror/linux-x86_64/libandy-mirror-jni.so")
    val script = rootProject.layout.projectDirectory.file("native/andy-mirror/jni/linux/build.sh")
    inputs.files(script)
    inputs.dir(rootProject.layout.projectDirectory.dir("native/andy-mirror/jni/linux"))
    inputs.dir(rootProject.layout.projectDirectory.dir("native/andy-mirror/third_party"))
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("linux") &&
            System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "bash",
        script.asFile.absolutePath,
        output.get().asFile.absolutePath,
        System.getProperty("java.home"),
    )
}

val buildAndyMirrorJniMacX64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the macOS x64 JAWT CAMetalLayer bridge used by the native mirror."
    val sources = andyMirrorJniSources
    val output = layout.buildDirectory.file("native/andy-mirror/macos-x86_64/andy-mirror-jni.dylib")
    inputs.files(sources)
    outputs.file(output)
    onlyIf {
        System.getProperty("os.name").lowercase().contains("mac") &&
            System.getProperty("os.arch").lowercase() in setOf("x86_64", "amd64")
    }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-arch", "x86_64",
        "-fobjc-arc",
        "-I${System.getProperty("java.home")}/include",
        "-I${System.getProperty("java.home")}/include/darwin",
        *sources.map { it.asFile.absolutePath }.toTypedArray(),
        "-framework", "AppKit",
        "-framework", "ApplicationServices",
        "-framework", "MetalKit",
        "-framework", "QuartzCore",
        "-framework", "Metal",
        "-framework", "VideoToolbox",
        "-framework", "CoreMedia",
        "-framework", "CoreVideo",
        "-framework", "AVFoundation",
        "-framework", "CoreMediaIO",
        "-framework", "IOSurface",
        "-L${System.getProperty("java.home")}/lib",
        "-ljawt",
        "-o", output.get().asFile.absolutePath,
    )
}

tasks.named<org.gradle.api.tasks.Copy>("desktopProcessResources") {
    dependsOn(
        buildAndyMirrorJniMacArm64,
        buildAndyMirrorJniMacX64,
        buildAndyMirrorJniLinuxX64,
        verifyScrcpyServer,
    )
    from(layout.buildDirectory.dir("native/andy-mirror")) {
        include("**/andy-mirror-jni.dylib")
        include("**/libandy-mirror-jni.so")
        into("andy-mirror")
    }
}
