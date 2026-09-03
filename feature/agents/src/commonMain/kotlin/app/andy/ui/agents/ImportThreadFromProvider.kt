package app.andy.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.andy.ui.components.contrastPrimaryButtonColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentPickerOption
import app.andy.model.ImportableAgentKinds
import app.andy.model.canImportVendorThread
import app.andy.model.comboReady
import app.andy.model.importIdHelper
import app.andy.model.importIdNoun
import app.andy.model.importIdPlaceholder
import app.andy.model.importTileLabel
import app.andy.ui.components.Button
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon

@Composable
internal fun ImportThreadFromProviderPane(
    initialAgent: AgentKind,
    cliStatuses: List<AgentCliStatus>,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onImport: (AgentKind, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var agent by remember(initialAgent) {
        mutableStateOf(initialAgent.takeIf { it.canImportVendorThread } ?: AgentKind.Codex)
    }
    var sessionId by remember { mutableStateOf("") }
    val selectedReady = AgentPickerOption(agent).comboReady(cliStatuses, emptyMap())
    val canImport = sessionId.isNotBlank() && selectedReady

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PanelCard(
            modifier = Modifier
                .widthIn(min = 560.dp, max = 800.dp)
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space6),
            background = AndyColors.SurfaceRaised,
            shape = AndyShape.Sheet,
            contentPadding = PaddingValues(AndySpace.Space5),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space5),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
            ) {
                ImportBackButton(onClick = onBack)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Import thread from provider",
                        color = TextPrimary,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        "Create a local Andy thread and resume it from an existing provider id.",
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 12.sp,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                Text(
                    "Provider",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    ImportableAgentKinds.forEach { kind ->
                        val ready = AgentPickerOption(kind).comboReady(cliStatuses, emptyMap())
                        ImportProviderTile(
                            agent = kind,
                            selected = kind == agent,
                            enabled = ready,
                            onClick = { agent = kind },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                Text(
                    "${agent.importIdNoun.replaceFirstChar { it.uppercase() }} ID",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                TextField(
                    value = sessionId,
                    onValueChange = { sessionId = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            agent.importIdPlaceholder(),
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                        )
                    },
                )
                Text(
                    agent.importIdHelper(),
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                )
                if (!selectedReady) {
                    Text(
                        "${agent.importTileLabel} CLI is not available yet.",
                        color = TextSecondary,
                        fontFamily = DisplayFont,
                        fontSize = 11.sp,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Cancel",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(AndyShape.Interactive)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
                )
                Spacer(Modifier.width(AndySpace.Space2))
                Button(
                    onClick = { onImport(agent, sessionId.trim()) },
                    enabled = canImport,
                    shape = RoundedCornerShape(AndyRadius.Pill),
                    colors = contrastPrimaryButtonColors(),
                ) {
                    Text("Import", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ImportBackButton(onClick: () -> Unit) {
    val color = TextSecondary
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(Lucide.ChevronLeft, color, Modifier.size(14.dp))
    }
}

@Composable
private fun ImportProviderTile(
    agent: AgentKind,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = AndyShape.Interactive
    val background = when {
        selected -> AndyColors.SurfaceSelected
        else -> AndyColors.PaneBg
    }
    Column(
        Modifier
            .width(84.dp)
            .height(72.dp)
            .clip(shape)
            .background(background, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        AgentPillIcon(agent, Modifier.size(20.dp))
        Text(
            agent.importTileLabel,
            color = if (enabled) TextPrimary else AndyColors.TextDisabled,
            fontFamily = DisplayFont,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
