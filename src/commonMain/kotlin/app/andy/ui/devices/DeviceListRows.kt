package app.andy.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.StatusTag
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun ConnectedDeviceRow(
    title: String,
    subtitle: String,
    isActive: Boolean,
    statusLabel: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    compactDetails: String? = null,
    apiLevel: String? = null,
    abi: String? = null,
    storageSummary: String? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    extraTags: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val rowShape = RoundedCornerShape(AndyRadius.Row)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showStorage = maxWidth >= 900.dp
        val showSpecs = maxWidth >= 720.dp
        Row(
            Modifier.fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = 76.dp)
                .background(
                    if (isActive) AndyColors.GreenSubtle else AndyColors.Neutral900.copy(alpha = 0.7f),
                    rowShape,
                )
                .border(
                    1.dp,
                    if (isActive) Green.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                    rowShape,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(if (isActive) AndyColors.GreenSoft else TextSecondary, RoundedCornerShape(AndyRadius.Control)),
            )
            Column(Modifier.weight(1f).widthIn(min = 120.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    titleTrailing?.invoke()
                }
                Text(
                    subtitle,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                if (!showSpecs && !compactDetails.isNullOrBlank()) {
                    Text(
                        compactDetails,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
            if (showSpecs) {
                Text(
                    "API ${apiLevel ?: "-"}\n${abi ?: "-"}",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.widthIn(max = 140.dp),
                    maxLines = 2,
                )
            }
            if (showStorage) {
                Text(
                    storageSummary ?: "-",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.widthIn(max = 140.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Row(
                Modifier.wrapContentWidth(unbounded = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusTag(statusLabel, statusColor)
                extraTags?.invoke(this)
                actions()
            }
        }
    }
}

@Composable
internal fun VirtualDeviceRow(
    name: String,
    subtitle: String,
    statusLabel: String,
    statusColor: Color,
    typeLabel: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 68.dp)
            .background(PanelSoft, RoundedCornerShape(AndyRadius.Row))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.Row))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail.orEmpty(),
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 16.dp),
            )
        }
        Text(
            statusLabel,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            typeLabel,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}
