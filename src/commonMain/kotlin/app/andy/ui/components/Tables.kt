package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.horizontalResizeCursor
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

@Composable
internal fun TableHeader(columns: List<Pair<String, androidx.compose.ui.unit.Dp>>) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        columns.forEach { (title, width) ->
            Text(title.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp, modifier = if (width.value == 1f) Modifier.weight(1f) else Modifier.width(width))
        }
    }
}

@Composable
internal fun TableRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(AndyColors.Neutral900.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun MonoCell(text: String, width: androidx.compose.ui.unit.Dp, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (modifier != Modifier) modifier else Modifier.width(width))
}

@Composable
internal fun HeaderCell(title: String, width: androidx.compose.ui.unit.Dp, onWidthChange: (Float) -> Unit) {
    val latestOnWidthChange by rememberUpdatedState(onWidthChange)
    val latestWidthValue by rememberUpdatedState(width.value)
    val density = LocalDensity.current.density
    var dragStartWidth by remember { mutableStateOf(0f) }
    var dragDelta by remember { mutableStateOf(0f) }
    Row(
        Modifier.width(width).fillMaxHeight()
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
        Text(title, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(14.dp).fillMaxHeight(),
        ) {
            Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(Rust.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
        }
    }
}
