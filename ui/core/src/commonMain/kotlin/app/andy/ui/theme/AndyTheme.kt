package app.andy.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Andy design system — Astryx-aligned product UI (Compose M3 + app tokens).
 *
 * Visual source: [Meta Astryx](https://astryx.atmeta.com/components) (`facebook/astryx` tokens).
 *
 * **Shape:** element 8dp · container 12dp · chat/composer 28dp · pill 999dp.
 *
 * **Color:** body `#F1F4F7` / `#111112`, surface `#FFFFFF` / `#1F1F22`, accent `#0064E0` / `#2694FE`.
 *
 * **Typography:** 14px body/label, system sans; mono for paths/commands.
 *
 * Tokens flow: [AndySemanticTokens] → CompositionLocal → atoms → molecules → screens.
 */
enum class AndyTint(val id: String, val label: String, val color: Color) {
    Default("andy-blue", "Astryx blue", Color(0xFF0064E0)),
    Sky("sky", "Sky", Color(0xFF38BDF8)),
    Azure("azure", "Azure", Color(0xFF3B82F6)),
    Indigo("indigo", "Indigo", Color(0xFF6366F1)),
    Violet("violet", "Violet", Color(0xFF8B5CF6)),
    Purple("purple", "Purple", Color(0xFFA855F7)),
    Fuchsia("fuchsia", "Fuchsia", Color(0xFFD946EF)),
    Pink("pink", "Pink", Color(0xFFEC4899)),
    Rose("rose", "Rose", Color(0xFFFB7185)),
    Coral("coral", "Coral", Color(0xFFFB7C65)),
    Orange("orange", "Orange", Color(0xFFFB923C)),
    Amber("amber", "Amber", Color(0xFFFBBF24)),
    Gold("gold", "Gold", Color(0xFFEAB308)),
    Lime("lime", "Lime", Color(0xFFA3E635)),
    Green("green", "Green", Color(0xFF4ADE80)),
    Emerald("emerald", "Emerald", Color(0xFF34D399)),
    Teal("teal", "Teal", Color(0xFF14B8A6)),
    Aqua("aqua", "Aqua", Color(0xFF22D3EE)),
    Steel("steel", "Steel", Color(0xFF94A3B8)),
    White("white", "Silver", Color(0xFFE2E8F0));

    companion object {
        fun fromId(id: String): AndyTint = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Background treatment independent of accent tint.
 * Tinted washes surfaces with the selected accent hue.
 * Dark uses the DNA dark neutrals. Light uses the DNA light palette.
 */
enum class AndySurfaceMode(val id: String, val label: String) {
    Tinted("tinted", "Tinted"),
    PitchBlack("pitch-black", "Dark"),
    Light("light", "Light");

    val isLight: Boolean get() = this == Light

    companion object {
        fun fromId(id: String): AndySurfaceMode = entries.firstOrNull { it.id == id } ?: Tinted
    }
}

private data class HslColor(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): HslColor {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (delta == 0f) return HslColor(0f, 0f, lightness)
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (maximum) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return HslColor(hue, saturation, lightness)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val normalizedHue = ((hue % 360f) + 360f) % 360f
    val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val secondary = chroma * (1f - kotlin.math.abs((normalizedHue / 60f) % 2f - 1f))
    val (red, green, blue) = when {
        normalizedHue < 60f -> Triple(chroma, secondary, 0f)
        normalizedHue < 120f -> Triple(secondary, chroma, 0f)
        normalizedHue < 180f -> Triple(0f, chroma, secondary)
        normalizedHue < 240f -> Triple(0f, secondary, chroma)
        normalizedHue < 300f -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val match = lightness - chroma / 2f
    return Color(
        (red + match).coerceIn(0f, 1f),
        (green + match).coerceIn(0f, 1f),
        (blue + match).coerceIn(0f, 1f),
    )
}

data class AndyTonalPalette(
    val neutral100: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral750: Color,
    val neutral800: Color,
    val neutral850: Color,
    val neutral900: Color,
    val border: Color,
    val borderMedium: Color,
    val windowBg: Color,
    val sidebarBg: Color,
    val paneBg: Color,
    val contentBg: Color,
    val surfaceHover: Color,
    val surfaceSelected: Color,
    val surfaceRaised: Color,
    /** Popover / chat composer — Astryx `--color-background-popover`. */
    val surfacePopover: Color,
    /** Input and emphasized chrome — Astryx `--color-border-emphasized`. */
    val borderEmphasized: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
) {
    companion object {
        fun from(tint: Color, surfaceMode: AndySurfaceMode): AndyTonalPalette = when (surfaceMode) {
            AndySurfaceMode.Light -> light()
            AndySurfaceMode.PitchBlack -> dark()
            AndySurfaceMode.Tinted -> tinted(tint)
        }

        /**
         * Accent-washed dark surfaces. Lightness follows the DNA dark ladder so
         * hierarchy stays familiar; hue/saturation come from the selected tint.
         */
        fun tinted(tint: Color): AndyTonalPalette {
            val hsl = tint.toHsl()
            val surfaceSaturation = hsl.saturation.coerceIn(0.10f, 0.28f)
            fun surface(lightness: Float, saturation: Float = surfaceSaturation) =
                hslColor(hsl.hue, saturation.coerceAtMost(surfaceSaturation), lightness)
            val window = surface(0.040f, 0.22f)
            val content = surface(0.040f, 0.24f)
            val pane = surface(0.060f, 0.20f)
            val sidebar = surface(0.068f, 0.18f)
            val raised = surface(0.095f, 0.16f)
            val hover = surface(0.105f, 0.14f)
            val selected = surface(0.125f, 0.12f)
            val border = hslColor(hsl.hue + 180f, 0.10f, 0.62f).copy(alpha = 0.10f)
            return AndyTonalPalette(
                neutral100 = Color.White.copy(alpha = 0.92f),
                neutral200 = Color.White.copy(alpha = 0.92f),
                neutral300 = Color.White.copy(alpha = 0.68f),
                neutral400 = Color.White.copy(alpha = 0.44f),
                neutral500 = Color.White.copy(alpha = 0.26f),
                neutral600 = hover,
                neutral700 = raised,
                neutral750 = sidebar,
                neutral800 = pane,
                neutral850 = content,
                neutral900 = window,
                border = border,
                borderMedium = border.copy(alpha = 0.14f),
                windowBg = window,
                sidebarBg = sidebar,
                paneBg = pane,
                contentBg = content,
                surfaceHover = hover,
                surfaceSelected = selected,
                surfaceRaised = raised,
                surfacePopover = surface(0.110f, 0.14f),
                borderEmphasized = Color(0xFF494D53),
                textPrimary = Color.White.copy(alpha = 0.92f),
                textSecondary = Color.White.copy(alpha = 0.68f),
                textTertiary = Color.White.copy(alpha = 0.44f),
                textDisabled = Color.White.copy(alpha = 0.26f),
            )
        }

        /** Astryx dark palette — body `#111112`, surface `#1F1F22`, popover `#28292C`. */
        fun dark() = AndyTonalPalette(
            neutral100 = Color(0xFFDFE2E5),
            neutral200 = Color(0xFFDFE2E5),
            neutral300 = Color(0xFFAAAFB5),
            neutral400 = Color(0xFF6F747C),
            neutral500 = Color(0xFF494D53),
            neutral600 = Color(0xFF33363B),
            neutral700 = Color(0xFF28292C),
            neutral750 = Color(0xFF1F1F22),
            neutral800 = Color(0xFF181819),
            neutral850 = Color(0xFF141415),
            neutral900 = Color(0xFF111112),
            border = Color(0xFFF2F4F6).copy(alpha = 0.10f),
            borderMedium = Color(0xFF494D53),
            windowBg = Color(0xFF111112),
            sidebarBg = Color(0xFF1F1F22),
            paneBg = Color(0xFF111112),
            contentBg = Color(0xFF111112),
            surfaceHover = Color(0xFFDFE2E5).copy(alpha = 0.08f),
            surfaceSelected = Color(0xFFDFE2E5).copy(alpha = 0.14f),
            surfaceRaised = Color(0xFF1F1F22),
            surfacePopover = Color(0xFF28292C),
            borderEmphasized = Color(0xFF494D53),
            textPrimary = Color(0xFFDFE2E5),
            textSecondary = Color(0xFFAAAFB5),
            textTertiary = Color(0xFF6F747C),
            textDisabled = Color(0xFF6F747C),
        )

        /** Astryx light palette — body `#F1F4F7`, surface `#FFFFFF`. */
        fun light() = AndyTonalPalette(
            neutral100 = Color(0xFF0A1317),
            neutral200 = Color(0xFF0A1317),
            neutral300 = Color(0xFF4E606F),
            neutral400 = Color(0xFFA4B0BC),
            neutral500 = Color(0xFFCCD3DB),
            neutral600 = Color(0xFFE8ECF0),
            neutral700 = Color(0xFFFFFFFF),
            neutral750 = Color(0xFFF1F4F7),
            neutral800 = Color(0xFFF1F4F7),
            neutral850 = Color(0xFFF1F4F7),
            neutral900 = Color(0xFFF1F4F7),
            border = Color(0xFF053659).copy(alpha = 0.10f),
            borderMedium = Color(0xFFCCD3DB),
            windowBg = Color(0xFFF1F4F7),
            sidebarBg = Color(0xFFFFFFFF),
            paneBg = Color(0xFFF1F4F7),
            contentBg = Color(0xFFF1F4F7),
            surfaceHover = Color(0xFF053659).copy(alpha = 0.05f),
            surfaceSelected = Color(0xFF053659).copy(alpha = 0.10f),
            surfaceRaised = Color(0xFFFFFFFF),
            surfacePopover = Color(0xFFFFFFFF),
            borderEmphasized = Color(0xFFCCD3DB),
            textPrimary = Color(0xFF0A1317),
            textSecondary = Color(0xFF4E606F),
            textTertiary = Color(0xFFA4B0BC),
            textDisabled = Color(0xFFA4B0BC),
        )
    }
}

fun windowBackgroundForTint(
    tintId: String,
    surfaceModeId: String = AndySurfaceMode.PitchBlack.id,
): Color = AndyTonalPalette.from(
    AndyTint.fromId(tintId).color,
    AndySurfaceMode.fromId(surfaceModeId),
).windowBg

/** Snapshot of semantic colors for CompositionLocal propagation (atomic token layer). */
data class AndySemanticTokens(
    val palette: AndyTonalPalette,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentSubtle: Color,
    val accentBorder: Color,
    val accentMuted: Color,
    val neutralFill: Color,
    val skeleton: Color,
    val onAccent: Color,
    val success: Color,
    val successSoft: Color,
    val successSubtle: Color,
    val warning: Color,
    val error: Color,
    val diffAddBg: Color,
    val diffRemoveBg: Color,
    val isLight: Boolean,
) {
    companion object {
        fun from(colors: AndyColorsState): AndySemanticTokens {
            val isLight = colors.isLight
            val accent = when {
                !isLight && colors.selectedTint == AndyTint.Default ->
                    Color(0xFF2694FE)
                else -> colors.selectedTint.color
            }
            val accentMuted = if (isLight) Color(0x330082FB) else Color(0x3F0082FB)
            val neutralFill = if (isLight) {
                Color(0xFF053659).copy(alpha = 0.10f)
            } else {
                Color(0xFFDFE2E5).copy(alpha = 0.20f)
            }
            val skeleton = if (isLight) Color(0xFFCCD3DB) else Color(0xFF5A5E66)
            return AndySemanticTokens(
                palette = colors.tonalPalette,
                accent = accent,
                accentHover = accent.copy(alpha = 0.88f),
                accentPressed = accent.copy(alpha = 0.68f),
                accentSubtle = accentMuted,
                accentBorder = accent,
                accentMuted = accentMuted,
                neutralFill = neutralFill,
                skeleton = skeleton,
                onAccent = Color.White,
                success = Color(0xFF0D8626),
                successSoft = Color(0xFF26A756),
                successSubtle = if (isLight) Color(0x330B991F) else Color(0x3F0B991F),
                warning = if (isLight) Color(0xFFE9AF08) else Color(0xFFF2C00B),
                error = if (isLight) Color(0xFFE3193B) else Color(0xFFF5394F),
                diffAddBg = Color(0xFF0D8626).copy(alpha = 0.10f),
                diffRemoveBg = (if (isLight) Color(0xFFE3193B) else Color(0xFFF5394F)).copy(alpha = 0.10f),
                isLight = isLight,
            )
        }
    }
}

internal val LocalAndyTokens = compositionLocalOf<AndySemanticTokens> {
    error("AndyTheme not applied")
}

@Composable
fun andyTokens(): AndySemanticTokens = LocalAndyTokens.current

/** Mutable theme state — updated by [AndyTheme]; exposed via [LocalAndyTokens]. */
data class AndyColorsState(
    val selectedTint: AndyTint,
    val selectedSurfaceMode: AndySurfaceMode,
    val tonalPalette: AndyTonalPalette,
) {
    val isLight: Boolean get() = selectedSurfaceMode.isLight
}

object AndyColors {
    private var state by mutableStateOf(
        AndyColorsState(
            selectedTint = AndyTint.Default,
            selectedSurfaceMode = AndySurfaceMode.PitchBlack,
            tonalPalette = AndyTonalPalette.from(AndyTint.Default.color, AndySurfaceMode.PitchBlack),
        ),
    )

    fun selectTint(id: String, surfaceModeId: String = AndySurfaceMode.PitchBlack.id): AndySemanticTokens {
        val tint = AndyTint.fromId(id)
        val surfaceMode = AndySurfaceMode.fromId(surfaceModeId)
        if (state.selectedTint != tint || state.selectedSurfaceMode != surfaceMode) {
            state = AndyColorsState(
                selectedTint = tint,
                selectedSurfaceMode = surfaceMode,
                tonalPalette = AndyTonalPalette.from(tint.color, surfaceMode),
            )
        }
        return AndySemanticTokens.from(state)
    }

    internal fun currentState(): AndyColorsState = state

    val isLight: Boolean get() = state.isLight

    val Neutral100 get() = state.tonalPalette.neutral100
    val Neutral200 get() = state.tonalPalette.neutral200
    val Neutral300 get() = state.tonalPalette.neutral300
    val Neutral400 get() = state.tonalPalette.neutral400
    val Neutral500 get() = state.tonalPalette.neutral500
    val Neutral600 get() = state.tonalPalette.neutral600
    val Neutral700 get() = state.tonalPalette.neutral700
    val Neutral750 get() = state.tonalPalette.neutral750
    val Neutral800 get() = state.tonalPalette.neutral800
    val Neutral850 get() = state.tonalPalette.neutral850
    val Neutral900 get() = state.tonalPalette.neutral900
    val tonalPaletteBorder get() = state.tonalPalette.border
    val BorderMedium get() = state.tonalPalette.borderMedium

    val WindowBg get() = state.tonalPalette.windowBg
    val SidebarBg get() = state.tonalPalette.sidebarBg
    val PaneBg get() = state.tonalPalette.paneBg
    val ContentBg get() = state.tonalPalette.contentBg
    val SurfaceHover get() = state.tonalPalette.surfaceHover
    val SurfaceSelected get() = state.tonalPalette.surfaceSelected
    val SurfaceRaised get() = state.tonalPalette.surfaceRaised
    val SurfacePopover get() = state.tonalPalette.surfacePopover
    val BorderEmphasized get() = state.tonalPalette.borderEmphasized
    val TextPrimaryToken get() = state.tonalPalette.textPrimary
    val TextSecondaryToken get() = state.tonalPalette.textSecondary
    val TextTertiary get() = state.tonalPalette.textTertiary
    val TextDisabled get() = state.tonalPalette.textDisabled

    val Orange get() = state.selectedTint.color
    val OrangeHover get() = state.selectedTint.color.copy(alpha = 0.88f)
    val OrangePressed get() = state.selectedTint.color.copy(alpha = 0.68f)
    val OrangeSubtle get() = state.selectedTint.color.copy(alpha = if (isLight) 0.12f else 0.16f)
    val OrangeBorder get() = state.selectedTint.color.copy(alpha = if (isLight) 0.40f else 0.48f)
    val Green = Color(0xFF0D8626)
    val GreenSoft = Color(0xFF26A756)
    val GreenSubtle get() = if (isLight) Color(0x330B991F) else Color(0x3F0B991F)
    val Blue get() = if (isLight) state.selectedTint.color else {
        if (state.selectedTint == AndyTint.Default) Color(0xFF2694FE) else state.selectedTint.color
    }
    val Warning get() = if (isLight) Color(0xFFE9AF08) else Color(0xFFF2C00B)
    val Error get() = if (isLight) Color(0xFFE3193B) else Color(0xFFF5394F)

    /** Full-row diff tinting — added/removed lines and diff-stat chips share these. */
    val DiffAddBg get() = Green.copy(alpha = 0.10f)
    val DiffRemoveBg get() = Error.copy(alpha = 0.10f)
}

/** Translucency scale for surfaces stacked over other content (raised panels, popovers). */
object AndyOverlay {
    val Subtle = 0.55f
    val Medium = 0.72f
    val Strong = 0.90f
}

/** Harness spacing scale — dense workbench rhythm (4/8/12/16…). */
object AndySpace {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space7 = 28.dp
    val Space8 = 32.dp
}

/** Astryx radius — element 8 · container 12 · chat 28. */
object AndyRadius {
    val Interactive = 8.dp
    val Control = 8.dp
    val Row = 8.dp
    val Menu = 12.dp
    /** Cards, panels, sheets — Astryx `--radius-container`. */
    val Sheet = 12.dp
    /** Chat composer and bubbles — Astryx `--radius-chat`. */
    val Chat = 28.dp
    val Window = 14.dp
    val Pill = 999.dp
}

/** Shared corner shapes — prefer these over ad-hoc [RoundedCornerShape] at call sites. */
object AndyShape {
    val Interactive = RoundedCornerShape(AndyRadius.Interactive)
    val Row = RoundedCornerShape(AndyRadius.Row)
    val Menu = RoundedCornerShape(AndyRadius.Menu)
    val Sheet = RoundedCornerShape(AndyRadius.Sheet)
    val Chat = RoundedCornerShape(AndyRadius.Chat)
    val Window = RoundedCornerShape(AndyRadius.Window)
}

/** Harness layout + control metrics. */
object AndyLayout {
    val ToolbarHeight = 48.dp
    val SidebarWidth = 220.dp
    val SidebarCollapsedWidth = 52.dp
    val ListWidth = 300.dp
    val SidebarRowHeight = 36.dp
    val ControlHeightXs = 26.dp
    val ControlHeightSm = 28.dp
    val ControlHeightMd = 32.dp
    val ControlHeightLg = 36.dp
    /** Compact single-line text field — squarish-rounded, readable at a glance. */
    val FieldHeight = 32.dp
    val ToolbarButtonSize = 30.dp
    val IconSm = 13.dp
    val IconMd = 15.dp
    val IconLg = 17.dp
    val IconHitArea = 30.dp
    val ContentMaxWidth = 760.dp
    /** Leading accent bar width for active navigation rows. */
    val NavAccentBar = 3.dp
}

/** Astryx motion — `--duration-fast` 175ms, `--ease-standard`. */
object AndyMotion {
    const val FastMs = 175
    const val StandardMs = 175
    const val SpatialMs = 410
    const val MediumMs = 410
    const val MicroMinMs = 130
    const val MicroMaxMs = 230
    const val SmallMinMs = 175
    const val SmallMaxMs = 230

    val StandardEasing = CubicBezierEasing(0.24f, 1f, 0.4f, 1f)

    fun <T> standardTween(durationMillis: Int = StandardMs) = tween<T>(
        durationMillis = durationMillis,
        easing = StandardEasing,
    )
}

object AndyStroke {
    /** Hairline separators between panes and rows (DNA border-subtle weight). */
    val Hairline = 1.dp
    /** Invisible drag target width for vertical pane resize handles. */
    val PaneHandleHitWidth = 10.dp
    /** Invisible drag target height for horizontal pane resize handles. */
    val PaneHandleHitHeight = 10.dp
}

/**
 * System UI face for chrome and labels. Paths, commands, and runtime details
 * stay monospaced so dense developer information remains scannable.
 */
val DisplayFont = FontFamily.SansSerif
val MonoFont = FontFamily.Monospace

val Ink get() = AndyColors.WindowBg
val Panel get() = AndyColors.PaneBg
val PanelSoft get() = AndyColors.SurfaceRaised
val Border get() = AndyColors.BorderEmphasized
val PaneDividerTint get() = AndyColors.BorderMedium
val TextPrimary get() = AndyColors.TextPrimaryToken
val TextSecondary get() = AndyColors.TextSecondaryToken
val Rust get() = AndyColors.Orange
val Green = AndyColors.Green
val Cyan get() = AndyColors.Blue
val Yellow = AndyColors.Warning
val Red = AndyColors.Error

@Composable
fun AndyTheme(
    tintId: String = AndyTint.Default.id,
    surfaceModeId: String = AndySurfaceMode.PitchBlack.id,
    content: @Composable () -> Unit,
) {
    val tokens = remember(tintId, surfaceModeId) { AndyColors.selectTint(tintId, surfaceModeId) }
    val palette = tokens.palette
    val onAccent = tokens.onAccent
    val colorScheme = if (tokens.isLight) {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = onAccent,
            primaryContainer = tokens.accentSubtle,
            onPrimaryContainer = tokens.accent,
            secondary = tokens.success,
            onSecondary = Color.White,
            secondaryContainer = tokens.successSubtle,
            onSecondaryContainer = tokens.success,
            tertiary = palette.textSecondary,
            onTertiary = palette.textPrimary,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.paneBg,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = tokens.accent,
            outline = palette.border,
            outlineVariant = palette.borderMedium,
            error = tokens.error,
            onError = Color.White,
            errorContainer = tokens.error.copy(alpha = 0.12f),
            onErrorContainer = tokens.error,
            scrim = Color(0xFF09090B).copy(alpha = 0.45f),
            inverseSurface = palette.textPrimary,
            inverseOnSurface = palette.windowBg,
            inversePrimary = tokens.accent,
        )
    } else {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = onAccent,
            primaryContainer = tokens.accentSubtle,
            onPrimaryContainer = tokens.accent,
            secondary = tokens.success,
            onSecondary = Color(0xFF09090B),
            secondaryContainer = tokens.successSubtle,
            onSecondaryContainer = tokens.success,
            tertiary = palette.textSecondary,
            onTertiary = palette.textPrimary,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.paneBg,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = tokens.accent,
            outline = palette.border,
            outlineVariant = palette.borderMedium,
            error = tokens.error,
            onError = Color(0xFF09090B),
            errorContainer = tokens.error.copy(alpha = 0.12f),
            onErrorContainer = tokens.error,
            scrim = Color(0xFF09090B).copy(alpha = 0.55f),
            inverseSurface = palette.textPrimary,
            inverseOnSurface = palette.windowBg,
            inversePrimary = tokens.accent,
        )
    }
    CompositionLocalProvider(LocalAndyTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(
                displayLarge = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                displayMedium = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                ),
                headlineLarge = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                ),
                headlineMedium = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                ),
                titleLarge = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                titleMedium = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                titleSmall = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                bodyLarge = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
                bodyMedium = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
                bodySmall = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
                labelLarge = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                labelMedium = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                ),
                labelSmall = LocalTextStyle.current.copy(
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            ),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(AndyRadius.Interactive),
                small = RoundedCornerShape(AndyRadius.Interactive),
                medium = RoundedCornerShape(AndyRadius.Menu),
                large = RoundedCornerShape(AndyRadius.Sheet),
                extraLarge = RoundedCornerShape(AndyRadius.Chat),
            ),
            content = content,
        )
    }
}
