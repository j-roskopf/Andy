package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.horizontalResizeCursor
import app.andy.model.LogLevel
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow

@Composable
fun DataTableHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PaneDividerTint),
        )
    }
}

@Composable
fun TableHeader(columns: List<Pair<String, androidx.compose.ui.unit.Dp>>) {
    DataTableHeader {
        columns.forEach { (title, width) ->
            Text(
                title.lowercase(),
                color = TextSecondary.copy(alpha = 0.72f),
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
                modifier = if (width.value == 1f) Modifier.weight(1f) else Modifier.width(width),
            )
        }
    }
}

@Composable
fun TableRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .background(
                when {
                    selected -> AndyColors.SurfaceSelected
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun MonoCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    color: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Text(
        text,
        color = color,
        fontFamily = MonoFont,
        fontSize = if (compact) 11.sp else 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (modifier != Modifier) modifier else Modifier.width(width),
    )
}

@Composable
fun HeaderCell(
    title: String,
    width: androidx.compose.ui.unit.Dp,
    showLeadingDivider: Boolean = false,
    onWidthChange: (Float) -> Unit,
) {
    val latestOnWidthChange by rememberUpdatedState(onWidthChange)
    val latestWidthValue by rememberUpdatedState(width.value)
    val density = LocalDensity.current.density
    var dragStartWidth by remember { mutableStateOf(0f) }
    var dragDelta by remember { mutableStateOf(0f) }
    Row(
        Modifier
            .width(width)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragStartWidth = latestWidthValue
                        dragDelta = 0f
                    },
                ) { _, drag ->
                    dragDelta += drag.x / density
                    latestOnWidthChange(dragStartWidth + dragDelta)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeadingDivider) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.55f)
                    .background(PaneDividerTint),
            )
            Box(Modifier.width(6.dp))
        }
        Text(
            title.lowercase(),
            color = TextSecondary.copy(alpha = 0.72f),
            fontWeight = FontWeight.Medium,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun HeaderTrailingLabel(
    title: String,
    modifier: Modifier = Modifier,
    showLeadingDivider: Boolean = true,
) {
    Row(
        modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeadingDivider) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.55f)
                    .background(PaneDividerTint),
            )
            Box(Modifier.width(6.dp))
        }
        Text(
            title.lowercase(),
            color = TextSecondary.copy(alpha = 0.72f),
            fontWeight = FontWeight.Medium,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
fun LogLevelBadge(level: LogLevel, modifier: Modifier = Modifier) {
    val variant = when (level) {
        LogLevel.Error, LogLevel.Fatal -> BadgeVariant.Error
        LogLevel.Warn -> BadgeVariant.Yellow
        LogLevel.Info -> BadgeVariant.Blue
        LogLevel.Debug, LogLevel.Verbose, LogLevel.Silent -> BadgeVariant.Neutral
    }
    Badge(
        label = logLevelLabel(level),
        modifier = modifier,
        variant = variant,
    )
}

@Composable
fun StatusBadge(
    text: String,
    @Suppress("UNUSED_PARAMETER") color: Color,
    modifier: Modifier = Modifier,
) {
    Badge(
        label = text,
        modifier = modifier,
        variant = BadgeVariant.Neutral,
    )
}

fun logLevelForeground(level: LogLevel): Color = when (level) {
    LogLevel.Verbose -> TextSecondary
    LogLevel.Debug -> Cyan
    LogLevel.Info -> Green
    LogLevel.Warn -> Yellow
    LogLevel.Error, LogLevel.Fatal -> Red
    LogLevel.Silent -> TextSecondary
}

private fun logLevelLabel(level: LogLevel): String = when (level) {
    LogLevel.Verbose -> "V"
    LogLevel.Debug -> "D"
    LogLevel.Info -> "I"
    LogLevel.Warn -> "W"
    LogLevel.Error -> "E"
    LogLevel.Fatal -> "F"
    LogLevel.Silent -> "S"
}
