package app.andy.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon

fun actionLucidePath(icon: String): String = when (icon.trim().lowercase()) {
    "run" -> Lucide.Play
    "terminal" -> Lucide.SquareTerminal
    "test" -> Lucide.FlaskConical
    "debug" -> Lucide.Bug
    "build" -> Lucide.Hammer
    "server" -> Lucide.Server
    "deploy" -> Lucide.Rocket
    else -> Lucide.Asterisk
}

@Composable
fun ActionIcon(
    icon: String,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    LucideIcon(
        path = actionLucidePath(icon),
        tint = tint,
        modifier = modifier,
        contentDescription = contentDescription,
    )
}
