package app.andy.desktop.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidNativeTest {
    @Test
    fun mapsSupportedArchitecturesToPackagedNatives() {
        assertEquals(
            "andy-mermaid/macos-arm64/libandy_mermaid.dylib",
            MermaidNative.resourcePath("Mac OS X", "aarch64"),
        )
        assertEquals(
            "andy-mermaid/macos-x86_64/libandy_mermaid.dylib",
            MermaidNative.resourcePath("Darwin", "x86_64"),
        )
        assertEquals(
            "andy-mermaid/linux-x86_64/libandy_mermaid.so",
            MermaidNative.resourcePath("Linux", "amd64"),
        )
        assertEquals(
            "andy-mermaid/linux-arm64/libandy_mermaid.so",
            MermaidNative.resourcePath("Linux", "aarch64"),
        )
        assertEquals(
            "andy-mermaid/windows-x86_64/andy_mermaid.dll",
            MermaidNative.resourcePath("Windows 11", "amd64"),
        )
        assertEquals(
            "andy-mermaid/windows-arm64/andy_mermaid.dll",
            MermaidNative.resourcePath("Windows 11", "aarch64"),
        )
        assertNull(MermaidNative.resourcePath("Solaris", "amd64"))
    }

    @Test
    fun rendersFlowchartPngAcrossJni() {
        if (!MermaidNative.isAvailable()) return
        val png = MermaidJni.renderPng(
            source = "flowchart TD\nA-->B",
            dark = true,
        ).getOrThrow()
        assertTrue(png.size > 256, "png too small: ${png.size}")
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @Test
    fun invalidSourceFailsWithoutThrowingThroughCache() {
        if (!MermaidNative.isAvailable()) return
        val result = MermaidJni.renderPng(source = "this is not mermaid", dark = true)
        assertTrue(result.isFailure, result.exceptionOrNull()?.message.orEmpty())
    }
}
