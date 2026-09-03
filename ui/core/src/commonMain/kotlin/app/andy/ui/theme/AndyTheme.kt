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
 * Andy design system — Airtable-aligned product UI (Compose M3 + app tokens).
 *
 * Visual source: Airtable's editorial light system and its dark surface counterpart.
 *
 * **Shape:** element 6dp · container 12dp · chat/composer 10dp · pill 999dp.
 *
 * **Color:** paper `#FFFFFF` / void `#08090A`, elevated bone `#E5E5E6` / graphite `#23252A`.
 *
 * **Typography:** modest system sans weights; mono remains reserved for paths/commands.
 *
 * Tokens flow: [AndySemanticTokens] → CompositionLocal → atoms → molecules → screens.
 */
enum class AndyTint(val id: String, val label: String, val color: Color) {
    // Keep the persisted id for existing workspaces and preserve the established default tint.
    Default("andy-blue", "Airtable blue", Color(0xFF1B61C9)),
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
 * Dark and light use the supplied neutral token family.
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

/** Named color tokens supplied for the Andy light and dark visual systems. */
object AndyPalette {
    val Background = Color(0xFF181818)
    val Tertiary = Color(0xFF1F1F1F)
    val Surface = Color(0xFF2B2B2B)

    val Void = Background
    val Carbon = Tertiary
    val Obsidian = Background
    val Graphite = Surface
    val Smoke = Color(0xFF383B3F)
    val Ash = Color(0xFF62666D)
    val Fog = Color(0xFF8A8F98)
    val Mist = Color(0xFFD0D6E0)
    val Bone = Color(0xFFE5E5E6)
    val Paper = Color(0xFFFFFFFF)
    val AcidLime = Color(0xFFE4F222)
    val PulseGreen = Color(0xFF27A644)
    val CoralRed = Color(0xFFEB5757)
    val SignalTeal = Color(0xFF02B8CC)
    val IrisViolet = Color(0xFF6366F1)
    val Lavender = Color(0xFF8B5CF6)
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
    /** Popover / chat composer — elevated surface token. */
    val surfacePopover: Color,
    /** Input and emphasized chrome — strong border token. */
    val borderEmphasized: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
) {
    val background: Color get() = windowBg
    val surface: Color get() = surfacePopover
    val tertiary: Color get() = sidebarBg

    companion object {
        fun from(tint: Color, surfaceMode: AndySurfaceMode): AndyTonalPalette = when (surfaceMode) {
            AndySurfaceMode.Light -> light()
            AndySurfaceMode.PitchBlack -> dark()
            AndySurfaceMode.Tinted -> tinted(tint)
        }

        /**
         * Accent-washed surfaces for the existing Tinted mode. Hue and saturation come from
         * the selected tint while the tonal hierarchy stays quiet.
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
                borderMedium = border.copy(alpha = 0.10f),
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

        /** Dark palette — void window, carbon rail, obsidian content, graphite chat chrome. */
        fun dark() = AndyTonalPalette(
            neutral100 = AndyPalette.Paper,
            neutral200 = AndyPalette.Bone,
            neutral300 = AndyPalette.Mist,
            neutral400 = AndyPalette.Fog,
            neutral500 = AndyPalette.Ash,
            neutral600 = AndyPalette.Smoke,
            neutral700 = AndyPalette.Graphite,
            neutral750 = AndyPalette.Graphite,
            neutral800 = AndyPalette.Obsidian,
            neutral850 = AndyPalette.Carbon,
            neutral900 = AndyPalette.Void,
            border = AndyPalette.Smoke,
            // Quiet hairlines for pane/rail dividers; inputs keep borderEmphasized.
            borderMedium = AndyPalette.Ash.copy(alpha = 0.20f),
            windowBg = AndyPalette.Void,
            sidebarBg = AndyPalette.Carbon,
            paneBg = AndyPalette.Obsidian,
            contentBg = AndyPalette.Obsidian,
            surfaceHover = AndyPalette.Graphite,
            surfaceSelected = AndyPalette.Smoke,
            surfaceRaised = AndyPalette.Graphite,
            surfacePopover = AndyPalette.Graphite,
            borderEmphasized = AndyPalette.Ash,
            textPrimary = AndyPalette.Paper,
            textSecondary = AndyPalette.Mist,
            textTertiary = AndyPalette.Fog,
            textDisabled = AndyPalette.Ash,
        )

        /** Light palette — paper canvas with bone and mist surfaces. */
        fun light() = AndyTonalPalette(
            neutral100 = AndyPalette.Void,
            neutral200 = AndyPalette.Carbon,
            neutral300 = AndyPalette.Obsidian,
            neutral400 = AndyPalette.Graphite,
            neutral500 = AndyPalette.Smoke,
            neutral600 = AndyPalette.Ash,
            neutral700 = AndyPalette.Paper,
            neutral750 = AndyPalette.Bone,
            neutral800 = AndyPalette.Paper,
            neutral850 = AndyPalette.Paper,
            neutral900 = AndyPalette.Paper,
            border = AndyPalette.Smoke,
            // Quiet hairlines for pane/rail dividers; inputs keep borderEmphasized.
            borderMedium = AndyPalette.Smoke.copy(alpha = 0.12f),
            windowBg = AndyPalette.Paper,
            sidebarBg = AndyPalette.Bone,
            paneBg = AndyPalette.Paper,
            contentBg = AndyPalette.Paper,
            surfaceHover = AndyPalette.Bone,
            surfaceSelected = AndyPalette.Mist,
            surfaceRaised = AndyPalette.Paper,
            surfacePopover = AndyPalette.Paper,
            borderEmphasized = AndyPalette.Ash,
            textPrimary = AndyPalette.Void,
            textSecondary = AndyPalette.Obsidian,
            textTertiary = AndyPalette.Smoke,
            textDisabled = AndyPalette.Ash,
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
                !isLight && colors.selectedTint == AndyTint.Default -> Color(0xFF458FFF)
                else -> colors.selectedTint.color
            }
            val accentMuted = accent.copy(alpha = if (isLight) 0.12f else 0.20f)
            val neutralFill = colors.tonalPalette.surfaceHover
            val skeleton = colors.tonalPalette.surfaceHover
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
                onAccent = AndyPalette.Paper,
                success = AndyPalette.PulseGreen,
                successSoft = AndyPalette.PulseGreen,
                successSubtle = AndyPalette.PulseGreen.copy(alpha = if (isLight) 0.20f else 0.24f),
                warning = AndyPalette.AcidLime,
                error = AndyPalette.CoralRed,
                diffAddBg = AndyPalette.PulseGreen.copy(alpha = 0.10f),
                diffRemoveBg = AndyPalette.CoralRed.copy(alpha = 0.10f),
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
    val Background get() = state.tonalPalette.windowBg
    val Surface get() = state.tonalPalette.surfacePopover
    val Tertiary get() = state.tonalPalette.sidebarBg
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
    val Green = AndyPalette.PulseGreen
    val GreenSoft = AndyPalette.PulseGreen
    val GreenSubtle get() = AndyPalette.PulseGreen.copy(alpha = if (isLight) 0.20f else 0.24f)
    val Blue get() = if (isLight) state.selectedTint.color else {
        if (state.selectedTint == AndyTint.Default) Color(0xFF458FFF) else state.selectedTint.color
    }
    val Warning = AndyPalette.AcidLime
    val Error = AndyPalette.CoralRed

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

/** Shared spacing scale (4/8/12/16/24/32…). */
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

/** Airtable radius — element 6 · container 12 · chat 10. */
object AndyRadius {
    val Interactive = 6.dp
    val Control = 6.dp
    val Row = 6.dp
    val Menu = 10.dp
    /** Cards, panels, sheets — Airtable container radius. */
    val Sheet = 12.dp
    /** Chat composer and user turns — Airtable compact rounded radius. */
    val Chat = 10.dp
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
    val ListWidth = 252.dp
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
    val ChatContentMaxWidth = 800.dp
    /** Leading accent bar width for active navigation rows. */
    val NavAccentBar = 3.dp
}

/** Shared motion — fast interaction transition and standard easing. */
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
    /** Hairline separators between panes and rows. */
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
val Background get() = AndyColors.Background
val Surface get() = AndyColors.Surface
val Tertiary get() = AndyColors.Tertiary
/** Emphasized chrome for inputs, toggles, and focused controls. */
val Border get() = AndyColors.BorderEmphasized
/** Quiet hairlines for pane/rail/card dividers and structural borders. */
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
            onSecondary = AndyPalette.Void,
            secondaryContainer = tokens.successSubtle,
            onSecondaryContainer = tokens.success,
            tertiary = palette.sidebarBg,
            onTertiary = palette.textPrimary,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.surfacePopover,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = tokens.accent,
            outline = palette.border,
            outlineVariant = palette.borderMedium,
            error = tokens.error,
            onError = AndyPalette.Paper,
            errorContainer = tokens.error.copy(alpha = 0.12f),
            onErrorContainer = tokens.error,
            scrim = AndyPalette.Void.copy(alpha = 0.45f),
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
            onSecondary = AndyPalette.Void,
            secondaryContainer = tokens.successSubtle,
            onSecondaryContainer = tokens.success,
            tertiary = palette.sidebarBg,
            onTertiary = palette.textPrimary,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.surfacePopover,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.textSecondary,
            surfaceTint = tokens.accent,
            outline = palette.border,
            outlineVariant = palette.borderMedium,
            error = tokens.error,
            onError = AndyPalette.Void,
            errorContainer = tokens.error.copy(alpha = 0.12f),
            onErrorContainer = tokens.error,
            scrim = AndyPalette.Void.copy(alpha = 0.55f),
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
