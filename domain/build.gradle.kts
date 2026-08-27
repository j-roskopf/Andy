plugins {
    id("andy.domain")
}

val generateAndyBuildInfo = tasks.register("generateAndyBuildInfo") {
    val version = providers.gradleProperty("andy.versionName").orElse("0.0.0-dev")
    val outputDir = layout.buildDirectory.dir("generated/andyBuildInfo")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("app/andy/updates/AndyBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package app.andy.updates

            object AndyBuildInfo {
                const val versionName = "${version.get()}"
                const val githubOwner = "j-roskopf"
                const val githubRepo = "Andy"
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/andyBuildInfo"))
        }
    }
}

tasks.matching { it.name.startsWith("compile") && ("Kotlin" in it.name || it.name.endsWith("AndroidMain")) }.configureEach {
    dependsOn(generateAndyBuildInfo)
}
