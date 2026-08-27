plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        desktopMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":data:mirror"))
            implementation(project(":data:platform-tools"))
            implementation(libs.grpc.api)
            implementation(libs.grpc.core)
            implementation(libs.grpc.netty.shaded)
            implementation(libs.grpc.stub)
            implementation(libs.zxing.core)
        }
    }
}
