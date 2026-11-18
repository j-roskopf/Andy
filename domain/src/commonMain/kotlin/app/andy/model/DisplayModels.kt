package app.andy.model

/** How Android classifies a logical display (`type=` in `dumpsys display`). */
enum class AndroidDisplayType {
    Internal,
    External,
    Virtual,
    Overlay,
    Wifi,
    Unknown,
    ;

    companion object {
        fun fromToken(token: String?): AndroidDisplayType = when (token?.uppercase()) {
            "INTERNAL" -> Internal
            "EXTERNAL" -> External
            "VIRTUAL" -> Virtual
            "OVERLAY" -> Overlay
            "WIFI" -> Wifi
            else -> Unknown
        }
    }
}

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
    val type: AndroidDisplayType = AndroidDisplayType.Unknown,
) {
    val isDefault: Boolean get() = id == 0

    /**
     * True for real panels (built-in or externally attached). Scrcpy's own virtual display and
     * cast/overlay displays must not trigger automatic dual-pane mirroring.
     */
    val isPhysicalPanel: Boolean
        get() = when (type) {
            AndroidDisplayType.Internal, AndroidDisplayType.External, AndroidDisplayType.Unknown -> true
            AndroidDisplayType.Virtual, AndroidDisplayType.Overlay, AndroidDisplayType.Wifi -> false
        }

    /** Scrcpy's own capture display; mirroring it would recurse into the running session. */
    val isScrcpyVirtualDisplay: Boolean
        get() = type == AndroidDisplayType.Virtual && name.equals("scrcpy", ignoreCase = true)
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

private val displayInfoLineRegex = Regex(
    """DisplayInfo\{"([^"]*)", displayId (\d+),[^\n]*?\breal (\d+) x (\d+),[^\n]*?\bstate (\w+),[^\n]*?\btype ([A-Z_]+)""",
)

/**
 * Parses `dumpsys display` into logical displays with their Android display type.
 *
 * `dumpsys window displays` omits the type, so every app-created virtual display (notably
 * scrcpy's own `virtual:com.android.shell ... scrcpy` display) looked like a real second panel
 * and triggered an automatic dual-pane split. Prefer this parser when the type matters.
 */
fun parseAndroidDisplaysFromDumpsysDisplay(output: String): List<AndroidDisplay> =
    displayInfoLineRegex.findAll(output).mapNotNull { match ->
        val id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        if (id < 0) return@mapNotNull null
        val width = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
        val height = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
        if (width <= 0 || height <= 0) return@mapNotNull null
        if (id != 0 && (width < MIN_MIRROR_DISPLAY_EDGE || height < MIN_MIRROR_DISPLAY_EDGE)) {
            return@mapNotNull null
        }
        val state = match.groupValues[5]
        AndroidDisplay(
            id = id,
            width = width,
            height = height,
            name = match.groupValues[1].takeIf { it.isNotBlank() },
            isEnabled = state == "ON" || state == "ON_SUSPEND" || state == "VR",
            type = AndroidDisplayType.fromToken(match.groupValues[6]),
        )
    }
        // `mBaseDisplayInfo` precedes `mOverrideDisplayInfo` for the same id; keep the base entry.
        .distinctBy { it.id }
        .sortedBy { it.id }
        .toList()
