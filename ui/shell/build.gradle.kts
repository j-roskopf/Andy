plugins {
    id("andy.compose.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":navigation"))
            implementation(project(":ui:core"))
            implementation(project(":ui:components"))
            implementation(project(":core:platform"))
            implementation(project(":data:mirror"))
            implementation(project(":feature:live"))
            implementation(project(":feature:devices"))
            implementation(project(":feature:catalog"))
            implementation(project(":feature:apps"))
            implementation(project(":feature:logcat"))
            implementation(project(":feature:intents"))
            implementation(project(":feature:files"))
            implementation(project(":feature:computer-files"))
            implementation(project(":feature:network"))
            implementation(project(":feature:actions"))
            implementation(project(":feature:agents"))
            implementation(project(":feature:snapshots"))
            implementation(project(":feature:controls"))
            implementation(project(":feature:performance"))
            implementation(project(":feature:tracing"))
            implementation(project(":feature:design"))
            implementation(project(":feature:inspector"))
            implementation(project(":feature:bugs"))
            implementation(project(":feature:recordings"))
            implementation(project(":feature:settings"))
        }
    }
}
