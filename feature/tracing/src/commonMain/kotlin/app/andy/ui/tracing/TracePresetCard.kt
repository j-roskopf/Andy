package app.andy.ui.tracing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.TracePreset
import app.andy.ui.components.PanelCard
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun TracePresetCard(
    preset: TracePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.clickable(onClick = onClick),
        background = if (selected) AndyColors.OrangeSubtle.copy(alpha = 0.35f) else AndyColors.Neutral900.copy(alpha = 0.55f),
        borderColor = if (selected) Rust else PaneDividerTint,
        contentPadding = PaddingValues(AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Text(preset.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(preset.subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
internal fun TraceImportConfigCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.height(56.dp).clickable(onClick = onClick),
        background = AndyColors.Neutral900.copy(alpha = 0.45f),
        contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text("Import…", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun TraceStatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(AndyRadius.Control))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
