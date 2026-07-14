package app.andy.desktop.service.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndyMirrorBinaryLocatorTest {
    @Test
    fun mapsSupportedPackageTargetsToStagedResources() {
        assertEquals("andy-mirror/macos-arm64/andy-mirror", AndyMirrorBinaryLocator.resourcePath("Mac OS X", "aarch64"))
        assertEquals("andy-mirror/macos-x86_64/andy-mirror", AndyMirrorBinaryLocator.resourcePath("Darwin", "x86_64"))
        assertEquals("andy-mirror/windows-x86_64/andy-mirror.exe", AndyMirrorBinaryLocator.resourcePath("Windows 11", "amd64"))
        assertEquals("andy-mirror/linux-arm64/andy-mirror", AndyMirrorBinaryLocator.resourcePath("Linux", "arm64"))
    }

    @Test
    fun rejectsUnsupportedDesktopTargets() {
        assertNull(AndyMirrorBinaryLocator.resourcePath("Solaris", "sparc"))
    }
}
