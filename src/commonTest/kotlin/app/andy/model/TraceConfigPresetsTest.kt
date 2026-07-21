package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraceConfigPresetsTest {
    @Test
    fun materializeSplicesSizeKbAndDurationMs() {
        val result = TraceConfigPresets.materialize(
            template = TraceConfigPresets.Default.template,
            durationSeconds = 10,
            bufferSizeMb = 128,
        )
        assertTrue(result.contains("size_kb: 131072"))
        assertTrue(result.contains("duration_ms: 10000"))
        assertTrue(result.contains("flush_period_ms: 5000"))
        assertTrue(!result.contains("write_into_file"))
    }

    @Test
    fun materializeManualModeAddsWriteIntoFile() {
        val result = TraceConfigPresets.materialize(
            template = TraceConfigPresets.Empty.template,
            durationSeconds = 0,
            bufferSizeMb = 32,
        )
        assertTrue(result.contains("size_kb: 32768"))
        assertTrue(result.contains("write_into_file: true"))
        assertTrue(result.contains("file_write_period_ms: 2500"))
        assertTrue(result.contains("flush_period_ms: 5000"))
        assertTrue(!result.contains("duration_ms:"))
    }

    @Test
    fun everyTemplateHasBalancedBracesAndBuffers() {
        for (preset in TraceConfigPresets.all) {
            val opens = preset.template.count { it == '{' }
            val closes = preset.template.count { it == '}' }
            assertEquals(opens, closes, "unbalanced braces in ${preset.id}")
            assertTrue(preset.template.contains("buffers"), "${preset.id} missing buffers")
        }
    }

    @Test
    fun byIdRoundTrips() {
        for (preset in TraceConfigPresets.all) {
            assertEquals(preset, TraceConfigPresets.byId(preset.id))
        }
        assertEquals(null, TraceConfigPresets.byId("missing"))
        assertNotNull(TraceConfigPresets.byId("default"))
    }
}
