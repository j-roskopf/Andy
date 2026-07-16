package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

internal fun Modifier.rightBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val x = size.width - strokeWidth / 2f
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
}

internal fun Modifier.bottomBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val y = size.height - strokeWidth / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
}

internal fun Modifier.noiseGridOverlay(alpha: Float = 0.07f): Modifier = drawBehind {
    val grid = 18.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), 1f)
        x += grid
    }
    var y = 0f
    while (y < size.height) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha * 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
        y += grid
    }
}

@Composable
internal fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.lowercase(),
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            value.lowercase(),
            color = if (ok) Green else Rust,
            fontFamily = MonoFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

@Composable
internal fun StatusTag(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.heightIn(min = 22.dp)
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(AndyRadius.R2))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.R2))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(AndyRadius.Pill)))
            Text(label, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name subsystem is represented in navigation and service contracts for v1 expansion.", color = TextSecondary)
    }
}

@Composable
internal fun Toolbar(
    title: String,
    subtitle: String,
    onPrimary: (() -> Unit)? = null,
    primaryLabel: String = "Run",
    primaryEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(Rust, RoundedCornerShape(AndyRadius.R2)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = AndyColors.Neutral100, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp)
                Text(subtitle, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
        if (onPrimary != null) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                colors = primaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(primaryLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AndyRadius.R3)
    Column(
        modifier
            .background(AndyColors.Neutral800.copy(alpha = 0.82f), shape)
            .border(1.dp, accent?.copy(alpha = 0.58f) ?: Border, shape)
            .noiseGridOverlay(0.025f)
            .padding(AndySpace.S4),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(AndyColors.Neutral800, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = AndySpace.S5, vertical = AndySpace.S4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.lowercase(),
            color = TextSecondary,
            fontFamily = MonoFont,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun FilterPill(
    text: String,
    selected: Boolean,
    color: Color,
    toolbar: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(AndyRadius.R2)
    if (toolbar) {
        OutlinedButton(
            onClick = onClick,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) color.copy(alpha = 0.26f) else AndyColors.Neutral850,
                contentColor = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300,
            ),
            border = BorderStroke(1.dp, if (selected) color.copy(alpha = 0.70f) else Border),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                leadingContent?.invoke()
                Text(
                    text.lowercase(),
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    Box(
        Modifier
            .height(28.dp)
            .background(if (selected) color.copy(alpha = 0.26f) else AndyColors.Neutral850, shape)
            .border(1.dp, if (selected) color.copy(alpha = 0.70f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leadingContent?.invoke()
            Text(
                text.lowercase(),
                color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
internal fun ControlRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont)
        Text(value, color = TextPrimary, fontFamily = MonoFont)
    }
}

@Composable
internal fun MetricCard(label: String, value: String) {
    PanelCard(Modifier.width(170.dp).height(96.dp)) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold)
        Text(value, color = TextPrimary, fontSize = 26.sp, fontFamily = MonoFont)
    }
}
