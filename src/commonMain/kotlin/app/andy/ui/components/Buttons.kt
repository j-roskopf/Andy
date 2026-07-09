package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.TextPrimary

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    colors: ButtonColors = primaryButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary, disabledContentColor = AndyColors.Neutral500),
    border: BorderStroke? = BorderStroke(1.dp, AndyColors.Neutral100.copy(alpha = 0.16f)),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
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
    containerColor = AndyColors.OrangeSubtle,
    contentColor = AndyColors.Neutral100,
    disabledContainerColor = AndyColors.Neutral600,
    disabledContentColor = AndyColors.Neutral400,
)

@Composable
internal fun secondaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.Neutral850,
    contentColor = TextPrimary,
    disabledContainerColor = AndyColors.Neutral700,
    disabledContentColor = AndyColors.Neutral500,
)
