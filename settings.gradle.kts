pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "Andy"

include(
    ":composeApp",
    ":androidApp",
    ":domain",
    ":navigation",
    ":core:platform",
    ":core:di",
    ":core:testing",
    ":ui:core",
    ":ui:components",
    ":ui:preview",
    ":ui:shell",
    ":data:devices",
    ":data:mirror",
    ":data:agents",
    ":data:network",
    ":data:workspace",
    ":data:bug-capture",
    ":data:platform-tools",
    ":data:host",
    ":data:updates",
    ":data:remote",
    ":feature:devices",
    ":feature:catalog",
    ":feature:live",
    ":feature:apps",
    ":feature:logcat",
    ":feature:intents",
    ":feature:files",
    ":feature:computer-files",
    ":feature:network",
    ":feature:actions",
    ":feature:agents",
    ":feature:snapshots",
    ":feature:controls",
    ":feature:performance",
    ":feature:tracing",
    ":feature:design",
    ":feature:inspector",
    ":feature:bugs",
    ":feature:recordings",
    ":feature:settings",
    ":agent-store",
    ":web-launcher",
)
