plugins {
    kotlin("jvm")
    id("app.cash.sqldelight")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api("app.cash.sqldelight:runtime:2.0.2")
    api("app.cash.sqldelight:sqlite-driver:2.0.2")
}

sqldelight {
    databases {
        create("AndyAgentDatabase") {
            packageName.set("app.andy.store")
        }
    }
}
