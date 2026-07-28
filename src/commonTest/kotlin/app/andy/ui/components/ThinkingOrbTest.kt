package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingOrbTest {
    @Test
    fun hashDIsDeterministicAndUnitInterval() {
        val a = hashD(3f, 1.7f)
        val b = hashD(3f, 1.7f)
        assertEquals(a, b)
        assertTrue(a in 0f..<1f)
        assertTrue(hashD(0f, 5.2f) in 0f..<1f)
    }

    @Test
    fun inlinePresetIsSparserThanAvatar() {
        val inline = resolveSpherePreset(16f)
        val avatar = resolveSpherePreset(64f)
        assertTrue(inline.latRings < avatar.latRings)
        assertTrue(inline.lonDensity < avatar.lonDensity)
        assertTrue(inline.rBase > avatar.rBase)
        assertTrue(inline.speed > avatar.speed)
    }

    @Test
    fun sphereDotsAreNonEmptyAndFinite() {
        val preset = resolveSpherePreset(20f)
        val dots = buildSphereDots(20f, t = 1.2f, o = preset)
        assertTrue(dots.isNotEmpty())
        assertTrue(dots.all { it.r.isFinite() && it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun dayModuloPhaseKeepsSubSecondResolution() {
        // Epoch millis cast to Float32 collapses; day-modulo keeps 80ms ticks distinct.
        val a = (1_753_718_760_000L % 86_400_000L) / 1000.0
        val b = (1_753_718_760_080L % 86_400_000L) / 1000.0
        assertTrue((b - a) in 0.07..0.09)
        val frozenEpoch = 1_753_718_760_000L / 1000f
        val frozenEpochNext = 1_753_718_760_080L / 1000f
        assertEquals(frozenEpoch, frozenEpochNext)
    }

    @Test
    fun spherePulsesAndSpinsAcrossFrames() {
        val preset = resolveSpherePreset(20f)
        val a = buildSphereDots(20f, t = 1.0f, o = preset)
        val b = buildSphereDots(20f, t = 1.08f, o = preset)
        assertTrue(a.zip(b).any { (d0, d1) -> d0.x != d1.x || d0.y != d1.y || d0.r != d1.r })
        // Crest of the pulse enlarges dots vs the trough.
        val crest = buildSphereDots(20f, t = (PI_HALF / preset.pulseFreq), o = preset)
        val trough = buildSphereDots(20f, t = (3f * PI_HALF / preset.pulseFreq), o = preset)
        val crestSpan = crest.maxOf { it.x } - crest.minOf { it.x }
        val troughSpan = trough.maxOf { it.x } - trough.minOf { it.x }
        assertTrue(crestSpan > troughSpan)
    }
}

private const val PI_HALF = (kotlin.math.PI / 2.0).toFloat()
