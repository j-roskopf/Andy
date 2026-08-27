package app.andy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndySpace

/**
 * Astryx FormLayout direction.
 *
 * - [Vertical] — stack with Space4 gap (default)
 * - [Horizontal] — equal-width columns via [FormLayoutRow]
 * - [HorizontalLabels] — collapses to vertical ≤480dp (label-left reserved for callers)
 */
enum class FormLayoutDirection {
    Vertical,
    Horizontal,
    HorizontalLabels,
}

val LocalFormLayoutDirection = staticCompositionLocalOf { FormLayoutDirection.Vertical }

/**
 * Astryx FormLayout — vertical field stack (gap 16dp). Nest [FormLayoutRow] for horizontal bands.
 */
@Composable
fun FormLayout(
    modifier: Modifier = Modifier,
    direction: FormLayoutDirection = FormLayoutDirection.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalFormLayoutDirection provides direction) {
        when (direction) {
            FormLayoutDirection.Vertical,
            FormLayoutDirection.Horizontal,
            -> {
                Column(
                    modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
                    content = content,
                )
            }
            FormLayoutDirection.HorizontalLabels -> {
                BoxWithConstraints(modifier.fillMaxWidth()) {
                    val gap = if (maxWidth <= 480.dp) AndySpace.Space4 else AndySpace.Space3
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * Astryx FormLayout `direction="horizontal"` — equal-width columns (children use [Modifier.weight]).
 */
@Composable
fun FormLayoutRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalFormLayoutDirection provides FormLayoutDirection.Horizontal) {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space4),
            verticalAlignment = Alignment.Top,
            content = content,
        )
    }
}
