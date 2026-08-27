package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

/** Astryx SegmentedControlItem-style settings chip. */
@Composable
fun ChoicePill(
    label: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: Boolean = false,
) {
    val tokens = andyTokens()
    val filled = selected || menu
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(AndyLayout.ControlHeightSm)
            .background(
                when {
                    filled && selected -> AndyColors.SurfaceRaised
                    filled -> tokens.neutralFill
                    else -> Color.Transparent
                },
                shape,
            )
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = AndySpace.Space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (menu) "$label ▾" else label,
            color = if (selected || menu) TextPrimary else TextSecondary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
