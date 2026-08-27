plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:agents"))
            implementation(project(":data:network"))
            implementation(project(":data:workspace"))
            implementation(project(":data:devices"))
            implementation(project(":data:mirror"))
            implementation(project(":data:platform-tools"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.http)
            implementation(libs.web.push)
            implementation(libs.bouncycastle)
            implementation(libs.httpclient)
            implementation(libs.httpasyncclient)
        }
        desktopTest.dependencies {
            implementation(project(":data:agents"))
            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}
