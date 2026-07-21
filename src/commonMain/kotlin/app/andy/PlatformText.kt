package app.andy

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Clock

internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Local wall-clock display time for epoch millis (empty/"-" for non-positive). */
internal expect fun formatDisplayDateTime(epochMillis: Long): String

internal fun formatDecimal(value: Number, fractionDigits: Int): String {
    require(fractionDigits >= 0)
    val number = value.toDouble()
    val factor = 10.0.pow(fractionDigits)
    val rounded = round(abs(number) * factor).toLong()
    val whole = rounded / factor.toLong()
    if (fractionDigits == 0) return (if (number < 0 && rounded > 0) "-" else "") + whole
    val fraction = (rounded % factor.toLong()).toString().padStart(fractionDigits, '0')
    return buildString {
        if (number < 0 && rounded > 0) append('-')
        append(whole)
        append('.')
        append(fraction)
    }
}

internal fun formatRgbHex(red: Int, green: Int, blue: Int): String =
    "#" + listOf(red, green, blue).joinToString("") {
        it.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    }
