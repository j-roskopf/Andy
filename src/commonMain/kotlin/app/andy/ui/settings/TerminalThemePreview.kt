package app.andy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont

data class TerminalThemeColors(
    val background: Color,
    val foreground: Color,
    val selectionBg: Color,
    val prompt: Color,
    val success: Color,
    val command: Color,
    val keyword: Color,
    val muted: Color,
    val error: Color,
)

val TerminalThemePreset.colors: TerminalThemeColors
    get() = when (this) {
        TerminalThemePreset.OneDark -> TerminalThemeColors(
            background = Color(0xFF1E2127),
            foreground = Color(0xFFABB2BF),
            selectionBg = Color(0xFF404859),
            prompt = Color(0xFF56B6C2),
            success = Color(0xFF98C379),
            command = Color(0xFF61AFEF),
            keyword = Color(0xFFE5C07B),
            muted = Color(0xFF5C6370),
            error = Color(0xFFE06C75),
        )
        TerminalThemePreset.Nord -> TerminalThemeColors(
            background = Color(0xFF2E3440),
            foreground = Color(0xFFD8DEE9),
            selectionBg = Color(0xFF434C5E),
            prompt = Color(0xFF88C0D0),
            success = Color(0xFFA3BE8C),
            command = Color(0xFF81A1C1),
            keyword = Color(0xFFEBCB8B),
            muted = Color(0xFF616E88),
            error = Color(0xFFBF616A),
        )
        TerminalThemePreset.TokyoNight -> TerminalThemeColors(
            background = Color(0xFF1A1B26),
            foreground = Color(0xFFA9B1D6),
            selectionBg = Color(0xFF28344E),
            prompt = Color(0xFF7DCFFF),
            success = Color(0xFF9ECE6A),
            command = Color(0xFF7AA2F7),
            keyword = Color(0xFFE0AF68),
            muted = Color(0xFF565F89),
            error = Color(0xFFF7768E),
        )
        TerminalThemePreset.Everforest -> TerminalThemeColors(
            background = Color(0xFF2D353B),
            foreground = Color(0xFFD3C6AA),
            selectionBg = Color(0xFF475258),
            prompt = Color(0xFF83C092),
            success = Color(0xFFA7C080),
            command = Color(0xFF7FBBB3),
            keyword = Color(0xFFDBE07B),
            muted = Color(0xFF859289),
            error = Color(0xFFE67E80),
        )
        TerminalThemePreset.Campbell -> TerminalThemeColors(
            background = Color(0xFF0C0C0C),
            foreground = Color(0xFFCCCCCC),
            selectionBg = Color(0xFF3A3A3A),
            prompt = Color(0xFF3A96DD),
            success = Color(0xFF13A10E),
            command = Color(0xFFCCCCCC),
            keyword = Color(0xFFC19C00),
            muted = Color(0xFF767676),
            error = Color(0xFFC50F1F),
        )
    }

internal expect fun TerminalFontFamily.resolveComposeFont(): FontFamily

@Composable
internal fun TerminalThemePreview(
    terminalThemeId: String,
    fontFamilyId: String,
    fontSize: Float,
    modifier: Modifier = Modifier,
) {
    val preset = remember(terminalThemeId) { TerminalThemePreset.fromId(terminalThemeId) }
    val fontFamily = remember(fontFamilyId) { TerminalFontFamily.fromId(fontFamilyId) }
    val coercedFontSize = remember(fontSize) { TerminalThemePreset.coerceFontSize(fontSize) }

    val themeColors = preset.colors
    val font = fontFamily.resolveComposeFont()
    val sizeSp = coercedFontSize.sp
    val lineHeightSp = (coercedFontSize * 1.35f).sp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .background(themeColors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(themeColors.background.copy(alpha = 0.95f))
                .border(1.dp, Border.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(themeColors.error.copy(alpha = 0.8f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(themeColors.keyword.copy(alpha = 0.8f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(themeColors.success.copy(alpha = 0.8f)))
            }
            Text(
                "terminal — ${preset.label} — ${coercedFontSize.toInt()}pt",
                color = themeColors.muted,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(26.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val line1 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.prompt, fontWeight = FontWeight.Bold)) { append("user@andy") }
                    withStyle(SpanStyle(color = themeColors.foreground)) { append(" project") }
                    withStyle(SpanStyle(color = themeColors.muted)) { append(" % ") }
                    withStyle(SpanStyle(color = themeColors.command)) { append("./gradlew test") }
                }
            }
            val line2 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.success, fontWeight = FontWeight.Bold)) { append("BUILD SUCCESSFUL") }
                    withStyle(SpanStyle(color = themeColors.muted)) { append(" in 1.4s") }
                }
            }
            val line3 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.muted)) { append("37 actionable tasks: 37 up-to-date") }
                }
            }
            val line4 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.prompt, fontWeight = FontWeight.Bold)) { append("user@andy") }
                    withStyle(SpanStyle(color = themeColors.foreground)) { append(" project") }
                    withStyle(SpanStyle(color = themeColors.muted)) { append(" % ") }
                    withStyle(SpanStyle(color = themeColors.foreground)) { append("git status ") }
                    withStyle(SpanStyle(color = themeColors.keyword)) { append("--short") }
                }
            }
            val line5 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.keyword)) { append(" M ") }
                    withStyle(SpanStyle(color = themeColors.foreground)) { append("src/commonMain/kotlin/app/andy/ui/settings/SettingsScreen.kt") }
                }
            }
            val line6 = remember(themeColors) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = themeColors.prompt, fontWeight = FontWeight.Bold)) { append("user@andy") }
                    withStyle(SpanStyle(color = themeColors.foreground)) { append(" project") }
                    withStyle(SpanStyle(color = themeColors.muted)) { append(" % ") }
                    withStyle(SpanStyle(color = themeColors.prompt)) { append("█") }
                }
            }

            listOf(line1, line2, line3, line4, line5, line6).forEach { text ->
                Text(
                    text = text,
                    fontFamily = font,
                    fontSize = sizeSp,
                    lineHeight = lineHeightSp,
                )
            }
        }
    }
}
