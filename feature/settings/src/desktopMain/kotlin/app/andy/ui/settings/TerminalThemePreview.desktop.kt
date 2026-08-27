package app.andy.ui.settings

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import app.andy.model.TerminalFontFamily

@OptIn(ExperimentalTextApi::class)
internal actual fun TerminalFontFamily.resolveComposeFont(): FontFamily {
    val name = awtName ?: return FontFamily.Monospace
    return FontFamily(name)
}
