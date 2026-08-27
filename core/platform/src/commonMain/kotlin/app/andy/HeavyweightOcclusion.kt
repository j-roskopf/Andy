package app.andy

import androidx.compose.runtime.compositionLocalOf

/** When true, desktop SwingPanel hosts should leave composition so in-window dialogs can paint. */
val LocalSuppressHeavyweightSurfaces = compositionLocalOf { false }

/** True while the main Andy window is actively being resized (desktop only). */
val LocalWindowResizing = compositionLocalOf { false }
