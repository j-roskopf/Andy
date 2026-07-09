package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.MonoFont
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
