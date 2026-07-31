package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.TextPrimary

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = AndyShape.Interactive,
    colors: ButtonColors = primaryButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space5, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .height(AndyLayout.ControlHeightMd)
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = AndyShape.Interactive,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = TextPrimary,
        disabledContentColor = AndyColors.TextDisabled,
    ),
    border: BorderStroke? = BorderStroke(1.dp, Border),
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space5, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(AndyLayout.ControlHeightMd)
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
internal fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.Orange,
    contentColor = if (AndyColors.isLight) Color.White else Color(0xFF0A0A0A),
    disabledContainerColor = AndyColors.SurfaceHover,
    disabledContentColor = AndyColors.TextDisabled,
)

@Composable
internal fun secondaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.SurfaceHover,
    contentColor = TextPrimary,
    disabledContainerColor = AndyColors.PaneBg,
    disabledContentColor = AndyColors.TextDisabled,
)
