package app.andy.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon

@Composable
fun FoldableControlsPanel(
    hingeAngle: Float,
    enabled: Boolean,
    onPostureSelected: (FoldablePosture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedPosture = foldablePostureForAngle(hingeAngle)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Text(
            "Foldable controls",
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FoldablePosture.entries.forEach { posture ->
                PosturePresetButton(
                    posture = posture,
                    selected = posture == selectedPosture,
                    enabled = enabled,
                    onClick = { onPostureSelected(posture) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PosturePresetButton(
    posture: FoldablePosture,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AndyRadius.Control)
    val borderColor = when {
        !enabled -> PaneDividerTint.copy(alpha = 0.40f)
        selected -> Rust
        else -> PaneDividerTint
    }
    val container = when {
        !enabled -> AndyColors.Neutral850.copy(alpha = 0.35f)
        selected -> AndyColors.Neutral750.copy(alpha = 0.92f)
        else -> AndyColors.Neutral850.copy(alpha = 0.72f)
    }
    Column(
        modifier
            .height(92.dp)
            .background(container, shape)
            .border(1.dp, borderColor, shape)
            .semantics { this.selected = selected }
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PostureIcon(
            posture = posture,
            color = if (enabled) TextPrimary else TextSecondary,
            modifier = Modifier.size(34.dp),
        )
        Text(
            posture.label,
            color = if (enabled) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PostureIcon(
    posture: FoldablePosture,
    color: Color,
    modifier: Modifier = Modifier,
) {
    LucideIcon(
        if (posture == FoldablePosture.Closed) Lucide.Smartphone else Lucide.Tablet,
        color,
        modifier,
    )
}
