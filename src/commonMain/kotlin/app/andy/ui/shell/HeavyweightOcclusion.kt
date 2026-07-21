package app.andy.ui.shell

import androidx.compose.runtime.compositionLocalOf

/**
 * When true, desktop [androidx.compose.ui.awt.SwingPanel] hosts that can sit under chrome
 * menus should leave composition (not merely set child visibility) so those popups can paint.
 * Leaving an invisible Swing interop host still punches a Skia clear-hole and can keep a white
 * JPanel above the menu. Callers may narrow this (e.g. only right-docked terminals).
 */
internal val LocalSuppressHeavyweightSurfaces = compositionLocalOf { false }
