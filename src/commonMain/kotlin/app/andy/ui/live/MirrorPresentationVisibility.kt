package app.andy.ui.live

import androidx.compose.runtime.Composable

/** Keeps the desktop Metal overlay hidden while the mirror surface is still loading. */
@Composable
internal expect fun MirrorPresentationVisibilityEffect(visible: Boolean, enabled: Boolean)
