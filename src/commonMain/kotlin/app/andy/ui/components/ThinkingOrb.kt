package app.andy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.andy.currentTimeMillis
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Cyan
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Dotted thought-orb activity marker, adapted from
 * [thinking-orbs](https://github.com/Jakubantalik/thinking-orbs) (MIT).
 *
 * A lat/long dotted sphere with a slow tumble and a radial pulse — reads as a
 * breathing globe rather than orbiting particles. Animation is wall-clock
 * driven at a coarse cadence so multiple instances stay in phase and Skiko
 * invalidation stays cheaper than a full-refresh infinite transition.
 */
@Composable
internal fun ThinkingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = Cyan,
    speed: Float = 1f,
    animate: Boolean = true,
    contentDescription: String = "Working",
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val preset = remember(sizePx) { resolveSpherePreset(sizePx) }
    val dark = !AndyColors.isLight
    var tSec by remember { mutableFloatStateOf(0.6f) }

    LaunchedEffect(animate, speed, preset.speed) {
        if (!animate) {
            tSec = 0.6f
            return@LaunchedEffect
        }
        val eff = preset.speed * speed
        // Wall-clock phase, but never epoch-as-Float — millis since 1970 lose
        // all sub-minute precision in Float32 and the orb looks frozen.
        while (true) {
            val phaseSec = (currentTimeMillis() % 86_400_000L) / 1000.0
            tSec = (phaseSec * eff).toFloat()
            // ~12 fps — coarse enough to avoid the full-refresh Skiko churn that
            // made Material CircularProgressIndicator flicker SwingPanel terminals.
            delay(80)
        }
    }

    val dots = remember(tSec, sizePx, preset) {
        buildSphereDots(sizePx, tSec, preset)
    }

    Canvas(
        modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        paintDots(dots, color, dark, preset.rMin)
    }
}

/** Tuned draw options for the pulsing-sphere mode at a given pixel size. */
internal data class SphereOrbPreset(
    val speed: Float,
    val latRings: Int,
    val lonDensity: Int,
    val rBase: Float,
    val rDepth: Float,
    val inkFar: Float,
    val inkSpan: Float,
    val pulseAmp: Float,
    val pulseFreq: Float,
    val spin: Float,
    val rsPow: Float,
    val rMin: Float,
)

/**
 * Resolve count/radius for inline (~20px) vs avatar (~64px) — denser and
 * slower at avatar scale, sparser with larger dots when tiny.
 */
internal fun resolveSpherePreset(sizePx: Float): SphereOrbPreset {
    val inline = sizePx <= 40f
    // √scale keeps total dots proportional when both lat and lon shrink.
    val countScale = if (inline) 0.28f else 1f
    val rt = kotlin.math.sqrt(countScale)
    val radiusMul = if (inline) 2.2f else 1f

    return SphereOrbPreset(
        speed = if (inline) 2.4f else 1.6f,
        latRings = max(4, round(14f * rt).toInt()),
        lonDensity = max(6, round(32f * rt).toInt()),
        rBase = 0.7f * radiusMul,
        rDepth = 1.6f * radiusMul,
        inkFar = 0.62f,
        inkSpan = 0.54f,
        pulseAmp = 0.14f,
        pulseFreq = 2.2f,
        spin = 0.45f,
        rsPow = 0.6f,
        rMin = 0.3f,
    )
}

internal data class OrbDot(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Float,
    /** Ink value: 0 = darkest on paper; mirrored on dark substrates. */
    val white: Float,
    val a: Float = 1f,
)

private data class Proj(val x: Float, val y: Float, val z: Float)

/**
 * Build one frame of a pulsing dotted sphere: lat/long lattice, gentle yaw,
 * and a global radial breath (+ slight ink swell on the crest).
 */
internal fun buildSphereDots(
    size: Float,
    t: Float,
    o: SphereOrbPreset,
): List<OrbDot> {
    val cx = size / 2f
    val cy = size / 2f
    val pulse = sin(t * o.pulseFreq)
    // Ease the crest a touch so the swell reads as a soft breath, not a bounce.
    val breath = 1f + o.pulseAmp * pulse
    val crest = max(0f, pulse)
    val baseR = (size / 2f) * 0.82f * breath
    val tilt = 0.38f + 0.05f * sin(t * 0.55f)
    val proj = makeProj(t * o.spin, tilt, cx, cy, baseR)
    val rs = radiusScale(size, o.rsPow)
    val dots = ArrayList<OrbDot>(o.latRings * o.lonDensity)
    val twoPi = (2.0 * PI).toFloat()

    for (li in 0..o.latRings) {
        val lat = (-PI / 2.0 + (li.toDouble() / o.latRings) * PI).toFloat()
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, round(abs(cosLat) * o.lonDensity).toInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toFloat() / lonCount) * twoPi
            val p = proj(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val depth = (p.z + 1f) / 2f
            dots += OrbDot(
                x = p.x,
                y = p.y,
                z = p.z,
                r = (o.rBase + o.rDepth * depth) * (1f + 0.22f * crest) * rs,
                white = o.inkFar - o.inkSpan * depth - 0.08f * crest,
                a = 0.55f + 0.45f * depth,
            )
        }
    }
    return dots
}

private fun DrawScope.paintDots(
    dots: List<OrbDot>,
    color: Color,
    dark: Boolean,
    rMin: Float,
) {
    val sorted = dots.sortedBy { it.z }
    for (d in sorted) {
        if (d.a < 0.02f) continue
        val w = min(1f, max(0f, d.white))
        val ink = if (dark) 1f - w else w
        drawCircle(
            color = color.copy(alpha = (ink * d.a).coerceIn(0f, 1f)),
            radius = max(rMin, d.r),
            center = Offset(d.x, d.y),
        )
    }
}

/** Deterministic hash in [0, 1). */
internal fun hashD(a: Float, b: Float): Float {
    val h = sin(a * 12.9898f + b * 78.233f) * 43758.5453f
    return h - floor(h)
}

private fun radiusScale(size: Float, power: Float): Float =
    (size / 300f).toDouble().pow(power.toDouble()).toFloat()

private fun makeProj(
    yaw: Float,
    tilt: Float,
    cx: Float,
    cy: Float,
    scale: Float,
): (Float, Float, Float) -> Proj {
    val st = sin(tilt)
    val ct = cos(tilt)
    val sy = sin(yaw)
    val cyw = cos(yaw)
    return { x, y, z ->
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        Proj(cx + x1 * scale, cy - y1 * scale, z2)
    }
}
