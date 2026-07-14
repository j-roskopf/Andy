plugins {
    application
}

group = "app.andy"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("app.andy.weblauncher.Main")
    applicationName = "andy-web-launcher"
}

val syncWebDistribution by tasks.registering(Sync::class) {
    dependsOn(rootProject.tasks.named("wasmJsBrowserDistribution"))
    from(rootProject.layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    into(layout.buildDirectory.dir("generated/web-resources/web"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/web-resources"))
}

tasks.processResources {
    dependsOn(syncWebDistribution)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

val packageWebLauncher by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a platform-native Andy Web launcher app image with jpackage."
    dependsOn(tasks.installDist)
    val destination = layout.buildDirectory.dir("jpackage")
    outputs.dir(destination)
    doFirst { destination.get().asFile.mkdirs() }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "Andy Web",
        "--dest", destination.get().asFile.absolutePath,
        "--input", layout.buildDirectory.dir("install/andy-web-launcher/lib").get().asFile.absolutePath,
        "--main-jar", "web-launcher-${project.version}.jar",
        "--main-class", "app.andy.weblauncher.Main",
        "--add-modules", "jdk.httpserver,java.desktop",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
}

fun registerWebLauncherInstaller(taskName: String, packageType: String, osNeedle: String) =
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = "Builds the $packageType Andy Web launcher installer with jpackage."
        dependsOn(tasks.installDist)
        onlyIf { System.getProperty("os.name").contains(osNeedle, ignoreCase = true) }
        val destination = layout.buildDirectory.dir("jpackage/$packageType")
        outputs.dir(destination)
        doFirst { destination.get().asFile.mkdirs() }
        commandLine(
            "jpackage",
            "--type", packageType,
            "--name", "Andy Web",
            "--dest", destination.get().asFile.absolutePath,
            "--input", layout.buildDirectory.dir("install/andy-web-launcher/lib").get().asFile.absolutePath,
            "--main-jar", "web-launcher-${project.version}.jar",
            "--main-class", "app.andy.weblauncher.Main",
            "--add-modules", "jdk.httpserver,java.desktop",
            "--java-options", "-Dfile.encoding=UTF-8",
        )
    }

registerWebLauncherInstaller("packageWebLauncherDmg", "dmg", "mac")
registerWebLauncherInstaller("packageWebLauncherMsi", "msi", "windows")
registerWebLauncherInstaller("packageWebLauncherDeb", "deb", "linux")
