package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.andyTokens

enum class CardVariant {
    Default,
    Transparent,
    Muted,
}

enum class CardElevation {
    None,
    Low,
    Med,
}

/**
 * Astryx Card — container radius, optional border (default variant), low elevation shadow.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    variant: CardVariant = CardVariant.Default,
    elevation: CardElevation = CardElevation.None,
    shape: Shape = AndyShape.Sheet,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(AndySpace.Space5),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = andyTokens()
    val background = backgroundColor ?: when (variant) {
        CardVariant.Default -> AndyColors.SurfaceRaised
        CardVariant.Transparent -> Color.Transparent
        CardVariant.Muted -> tokens.neutralFill
    }
    val border = when (borderColor) {
        Color.Transparent -> null
        null -> if (variant == CardVariant.Default) PaneDividerTint else null
        else -> borderColor
    }
    val shadowElevation = when (elevation) {
        CardElevation.None -> 0.dp
        CardElevation.Low -> 2.dp
        CardElevation.Med -> 4.dp
    }
    Column(
        modifier
            .then(if (shadowElevation > 0.dp) Modifier.shadow(shadowElevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(background, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
