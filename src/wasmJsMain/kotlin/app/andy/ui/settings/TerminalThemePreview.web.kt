package app.andy.ui.settings

import androidx.compose.ui.text.font.FontFamily
import app.andy.model.TerminalFontFamily

internal actual fun TerminalFontFamily.resolveComposeFont(): FontFamily {
    return FontFamily.Monospace
}
