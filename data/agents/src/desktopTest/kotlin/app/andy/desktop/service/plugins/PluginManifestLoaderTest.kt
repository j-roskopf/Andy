package app.andy.desktop.service.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginManifestLoaderTest {
    @Test
    fun loadsWebhookSample() {
        val root = File("../../../samples/plugins/webhook-notify").canonicalFile
            .takeIf { it.isDirectory }
            ?: File("samples/plugins/webhook-notify").canonicalFile
        if (!root.isDirectory) return // skipped when cwd is unexpected
        val manifest = PluginManifestLoader.load(root, currentAndyVersion = "2026.0909.2356")
        assertEquals("examples.webhook-notify", manifest.id)
        assertEquals(1, manifest.actions.size)
        assertEquals("pane.agent_status_changed", manifest.events.single().on)
        assertEquals("log", manifest.panes.single().id)
        assertTrue(manifest.warnings.isEmpty())
    }

    @Test
    fun rejectsPopupPlacement() {
        val dir = File.createTempFile("andy-plugin", null).apply {
            delete()
            mkdir()
        }
        File(dir, "andy-plugin.toml").writeText(
            """
            id = "examples.bad-popup"
            name = "Bad"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos"]

            [[panes]]
            id = "x"
            title = "X"
            placement = "popup"
            command = ["echo", "hi"]
            """.trimIndent(),
        )
        val err = assertFailsWith<PluginManifestException> {
            PluginManifestLoader.load(dir, "2026.0909.2356")
        }
        assertTrue(err.message!!.contains("popup"))
        dir.deleteRecursively()
    }

    @Test
    fun warnsOnUnknownEvent() {
        val dir = File.createTempFile("andy-plugin", null).apply {
            delete()
            mkdir()
        }
        File(dir, "andy-plugin.toml").writeText(
            """
            id = "examples.unknown-event"
            name = "Unknown"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos"]

            [[events]]
            on = "not.a.real.event"
            command = ["echo", "hi"]
            """.trimIndent(),
        )
        val manifest = PluginManifestLoader.load(dir, "2026.0909.2356")
        assertTrue(manifest.warnings.any { it.contains("unknown event") })
        dir.deleteRecursively()
    }

    @Test
    fun versionCompare() {
        assertTrue(PluginVersion.compare("0.1.0", "2026.0909.2356") < 0)
        assertEquals(0, PluginVersion.compare("2026.0909.2356", "2026.0909.2356"))
        assertTrue(PluginVersion.compare("2027.0101.0000", "2026.0909.2356") > 0)
    }
}
