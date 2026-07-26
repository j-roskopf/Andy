package app.andy.ui.controls

import app.andy.model.VirtualDevice

/**
 * Inner (unfolded) and outer (folded) display sizes for a foldable AVD.
 * Used to morph Andy's Live viewport between closed / half / opened.
 */
data class FoldableDisplayProfile(
    val outerWidth: Int,
    val outerHeight: Int,
    val innerWidth: Int,
    val innerHeight: Int,
) {
    val outerAspect: Float get() = outerWidth.toFloat() / outerHeight.toFloat()
    val innerAspect: Float get() = innerWidth.toFloat() / innerHeight.toFloat()

    /** 0 = fully closed (outer), 1 = fully open (inner). */
    fun aspectForOpenAmount(openAmount: Float): Float {
        val t = openAmount.coerceIn(0f, 1f)
        // Smoothstep so open/close feels like the Studio fold animation.
        val s = t * t * (3f - 2f * t)
        return outerAspect + (innerAspect - outerAspect) * s
    }
}

fun foldableOpenAmount(hingeAngleDegrees: Float): Float =
    (hingeAngleDegrees.coerceIn(0f, 180f) / 180f)

fun foldableDisplayProfile(config: Map<String, String>): FoldableDisplayProfile? {
    val innerWidth = config["hw.lcd.width"]?.toIntOrNull() ?: return null
    val innerHeight = config["hw.lcd.height"]?.toIntOrNull() ?: return null
    val outerWidth = config["hw.displayRegion.0.1.width"]?.toIntOrNull() ?: return null
    val outerHeight = config["hw.displayRegion.0.1.height"]?.toIntOrNull() ?: return null
    if (innerWidth <= 0 || innerHeight <= 0 || outerWidth <= 0 || outerHeight <= 0) return null
    if (innerWidth == outerWidth && innerHeight == outerHeight) return null
    return FoldableDisplayProfile(outerWidth, outerHeight, innerWidth, innerHeight)
}

fun foldableDisplayProfile(avd: VirtualDevice?): FoldableDisplayProfile? =
    avd?.let { foldableDisplayProfile(it.config) }

/**
 * When AVD config is missing, infer a profile from the current framebuffer:
 * the wider aspect is treated as inner (open), the narrower as outer (closed).
 */
fun inferredFoldableDisplayProfile(
    frameWidth: Int,
    frameHeight: Int,
): FoldableDisplayProfile? {
    if (frameWidth <= 1 || frameHeight <= 1) return null
    val aspect = frameWidth.toFloat() / frameHeight.toFloat()
    return if (aspect >= 0.75f) {
        // Currently open / inner-like — synthesize a typical outer cover display.
        FoldableDisplayProfile(
            outerWidth = (frameHeight * 1080f / 2364f).toInt().coerceAtLeast(1),
            outerHeight = frameHeight,
            innerWidth = frameWidth,
            innerHeight = frameHeight,
        )
    } else {
        // Currently closed / outer-like — synthesize an inner unfolded display.
        FoldableDisplayProfile(
            outerWidth = frameWidth,
            outerHeight = frameHeight,
            innerWidth = (frameHeight * 2076f / 2152f).toInt().coerceAtLeast(1),
            innerHeight = frameHeight,
        )
    }
}
