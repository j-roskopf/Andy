package app.andy.desktop.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WkBrowserJniResourcePathTest {
    @Test
    fun packagesArm64SliceOnMac() {
        assertEquals(
            "andy-browser/macos-arm64/andy-browser-jni.dylib",
            WkBrowserJni.resourcePath(osName = "Mac OS X", osArch = "aarch64"),
        )
    }

    @Test
    fun packagesX64SliceOnMac() {
        assertEquals(
            "andy-browser/macos-x86_64/andy-browser-jni.dylib",
            WkBrowserJni.resourcePath(osName = "Mac OS X", osArch = "x86_64"),
        )
    }

    @Test
    fun unavailableOffMac() {
        assertNull(WkBrowserJni.resourcePath(osName = "Linux", osArch = "amd64"))
        assertNull(WkBrowserJni.resourcePath(osName = "Windows 11", osArch = "amd64"))
    }
}
