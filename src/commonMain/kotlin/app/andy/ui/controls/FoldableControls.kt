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
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun FoldableControlsPanel(
    hingeAngle: Float,
    enabled: Boolean,
    onPostureSelected: (FoldablePosture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedPosture = foldablePostureForAngle(hingeAngle)
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        background = AndyColors.Neutral900.copy(alpha = 0.44f),
        borderColor = Border.copy(alpha = 0.72f),
        contentPadding = PaddingValues(AndySpace.Space5),
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
        !enabled -> Border.copy(alpha = 0.40f)
        selected -> Rust
        else -> Border.copy(alpha = 0.80f)
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
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round)
        val inset = size.minDimension * 0.12f
        when (posture) {
            FoldablePosture.Closed -> {
                val w = size.width * 0.42f
                val h = size.height * 0.72f
                drawRoundRect(
                    color = color,
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w * 0.18f, w * 0.18f),
                    style = stroke,
                )
            }
            FoldablePosture.Opened -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset * 1.4f),
                    size = Size(size.width - inset * 2f, size.height - inset * 2.8f),
                    cornerRadius = CornerRadius(size.minDimension * 0.08f),
                    style = stroke,
                )
                drawLine(
                    color = color.copy(alpha = 0.55f),
                    start = Offset(size.width / 2f, inset * 1.6f),
                    end = Offset(size.width / 2f, size.height - inset * 1.6f),
                    strokeWidth = stroke.width * 0.7f,
                )
            }
        }
    }
}
