package app.andy.model

/**
 * One logical Android display that scrcpy can capture with `display_id`.
 *
 * Dual-screen handhelds (AYN Thor, Retroid Pocket, foldables in desktop mode) expose the second
 * panel as an extra logical display. [id] is the `mDisplayId` that both `dumpsys window displays`
 * and scrcpy use.
 */
data class AndroidDisplay(
    val id: Int,
    val width: Int,
    val height: Int,
    val name: String? = null,
    val isEnabled: Boolean = true,
) {
    val isDefault: Boolean get() = id == 0
}

/**
 * A Live mirror target is either a plain device serial (display 0) or a serial plus a secondary
 * display id. Encoding the display in the target id lets dock panes, pop-outs, holds, and GPU
 * pipelines key one mirror per display without changing the device identity used by adb.
 */
object MirrorTarget {
    const val DISPLAY_SEPARATOR = "@display="

    fun id(serial: String, displayId: Int): String =
        if (displayId <= 0) serial else "$serial$DISPLAY_SEPARATOR$displayId"

    fun serial(targetId: String): String = targetId.substringBefore(DISPLAY_SEPARATOR)

    fun displayId(targetId: String): Int =
        targetId.substringAfter(DISPLAY_SEPARATOR, missingDelimiterValue = "")
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 0

    fun isSecondaryDisplay(targetId: String): Boolean = displayId(targetId) > 0
}

private val displayHeaderRegex = Regex("""Display:\s*mDisplayId=(-?\d+)""")
private val displayCurrentSizeRegex = Regex("""\bcur=(\d+)x(\d+)""")
private val displayInitSizeRegex = Regex("""\binit=(\d+)x(\d+)""")
private val displayRealSizeRegex = Regex("""\breal\s+(\d+)\s*x\s*(\d+)""")
private val displayNameRegex = Regex("""DisplayInfo\{"([^"]+)"""")
private val displayStateRegex = Regex("""\bstate=(ON|OFF|DOZE|DOZE_SUSPEND|VR|ON_SUSPEND|UNKNOWN)\b""")

/** Smallest panel worth mirroring; Android reserves tiny overlay displays that are not screens. */
private const val MIN_MIRROR_DISPLAY_EDGE = 240

/**
 * Parses `dumpsys window displays` into the logical displays a user can mirror.
 *
 * The default display is always retained when it has a readable size. Secondary displays must be
 * large enough to be a real panel and not explicitly disabled.
 */
fun parseAndroidDisplays(output: String): List<AndroidDisplay> {
    val headers = displayHeaderRegex.findAll(output).toList()
    if (headers.isEmpty()) return emptyList()
    val displays = headers.mapIndexedNotNull { index, header ->
        val id = header.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
        if (id < 0) return@mapIndexedNotNull null
        val start = header.range.last + 1
        val end = headers.getOrNull(index + 1)?.range?.first ?: output.length
        val block = output.substring(start, end.coerceAtMost(output.length))
        // Off/asleep panels can report cur=0x0; fall back to the configured mode.
        val size = listOf(displayCurrentSizeRegex, displayRealSizeRegex, displayInitSizeRegex)
            .firstNotNullOfOrNull { regex ->
                regex.find(block)?.takeIf {
                    (it.groupValues[1].toIntOrNull() ?: 0) > 0 && (it.groupValues[2].toIntOrNull() ?: 0) > 0
                }
            } ?: return@mapIndexedNotNull null
        val width = size.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
        val height = size.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
        if (width <= 0 || height <= 0) return@mapIndexedNotNull null
        if (id != 0 && (width < MIN_MIRROR_DISPLAY_EDGE || height < MIN_MIRROR_DISPLAY_EDGE)) {
            return@mapIndexedNotNull null
        }
        val state = displayStateRegex.find(block)?.groupValues?.getOrNull(1)
        AndroidDisplay(
            id = id,
            width = width,
            height = height,
            name = displayNameRegex.find(block)?.groupValues?.getOrNull(1),
            isEnabled = state == null || state == "ON" || state == "ON_SUSPEND" || state == "VR",
        )
    }
    return displays.sortedBy { it.id }
}

/** Current size of a specific logical display, falling back to the default-display parser. */
fun parseAndroidDisplaySize(output: String, displayId: Int): Pair<Int, Int>? =
    parseAndroidDisplays(output).firstOrNull { it.id == displayId }?.let { it.width to it.height }
