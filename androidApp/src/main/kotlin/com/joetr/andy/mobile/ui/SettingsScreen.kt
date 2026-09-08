package com.joetr.andy.mobile.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.ui.components.Card
import app.andy.ui.components.CardVariant
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.andyTokens
import app.andy.updates.AndyBuildInfo
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.TranscriptSettingsDto
import kotlinx.coroutines.launch

private const val ProjectGitHubUrl = "https://github.com/j-roskopf/Andy"
private const val ReleasesGitHubUrl = "https://github.com/j-roskopf/Andy/releases"

/** Material-ish control radius — avoids the desktop/macOS pill look. */
private val SettingsControlShape = RoundedCornerShape(12.dp)

@Composable
fun SettingsScreen(
    updates: AppUpdateService,
    networkClient: NetworkAccessClient? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by updates.state.collectAsStateWithLifecycle()
    var transcriptPrefs by remember { mutableStateOf(TranscriptSettingsDto()) }
    var transcriptError by remember { mutableStateOf<String?>(null) }
    var transcriptLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(networkClient) {
        transcriptLoaded = false
        transcriptError = null
        if (networkClient == null) return@LaunchedEffect
        runCatching { networkClient.getTranscriptSettings() }
            .onSuccess {
                transcriptPrefs = it
                transcriptLoaded = true
            }
            .onFailure { transcriptError = friendlyTranscriptError(it) }
    }

    Column(modifier.background(tokens.palette.windowBg)) {
        MobileHeader(
            title = "Settings",
            subtitle = "Transcript options, about, and updates",
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AndySpace.Space4,
                end = AndySpace.Space4,
                top = AndySpace.Space2,
                bottom = 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        ) {
            if (networkClient != null) {
                item(key = "transcript") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        variant = CardVariant.Default,
                        shape = AndyShape.Sheet,
                        backgroundColor = tokens.palette.surfaceRaised,
                        borderColor = tokens.palette.borderMedium,
                        contentPadding = PaddingValues(AndySpace.Space4),
                        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                    ) {
                        Text(
                            "Transcript",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.palette.textPrimary,
                        )
                        Text(
                            "How thinking steps and tool calls appear in agent chats. Shared with Andy Desktop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.palette.textSecondary,
                        )
                        if (transcriptError != null) {
                            Text(
                                transcriptError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.error,
                            )
                        }
                        TranscriptToggleRow(
                            label = "Show thinking on timeline",
                            description = "Keeps each thinking step as its own expanded row. Thoughts are not folded into the collapsed tool activity summary.",
                            checked = transcriptPrefs.showThinkingOnTimeline,
                            enabled = networkClient != null,
                            onCheckedChange = { value ->
                                scope.launch {
                                    runCatching {
                                        networkClient.patchTranscriptSettings(showThinkingOnTimeline = value)
                                    }.onSuccess {
                                        transcriptPrefs = it
                                        transcriptError = null
                                        transcriptLoaded = true
                                    }.onFailure { transcriptError = friendlyTranscriptError(it) }
                                }
                            },
                        )
                        TranscriptToggleRow(
                            label = "Auto-expand tool sections",
                            description = "Opens each tool call and file edit when it appears. You can still collapse sections manually.",
                            checked = transcriptPrefs.autoExpandToolSections,
                            enabled = networkClient != null,
                            onCheckedChange = { value ->
                                scope.launch {
                                    runCatching {
                                        networkClient.patchTranscriptSettings(autoExpandToolSections = value)
                                    }.onSuccess {
                                        transcriptPrefs = it
                                        transcriptError = null
                                        transcriptLoaded = true
                                    }.onFailure { transcriptError = friendlyTranscriptError(it) }
                                }
                            },
                        )
                        TranscriptToggleRow(
                            label = "Collapse activity between messages",
                            description = "Groups consecutive tool steps into one block between user and assistant messages. Thinking stays separate when shown on the timeline.",
                            checked = transcriptPrefs.collapseActivityBetweenMessages,
                            enabled = networkClient != null,
                            onCheckedChange = { value ->
                                scope.launch {
                                    runCatching {
                                        networkClient.patchTranscriptSettings(
                                            collapseActivityBetweenMessages = value,
                                        )
                                    }.onSuccess {
                                        transcriptPrefs = it
                                        transcriptError = null
                                        transcriptLoaded = true
                                    }.onFailure { transcriptError = friendlyTranscriptError(it) }
                                }
                            },
                        )
                    }
                }
            }
            item(key = "about") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    variant = CardVariant.Default,
                    shape = AndyShape.Sheet,
                    backgroundColor = tokens.palette.surfaceRaised,
                    borderColor = tokens.palette.borderMedium,
                    contentPadding = PaddingValues(AndySpace.Space4),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                ) {
                    Text(
                        "About Andy",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.palette.textPrimary,
                    )
                    Text(
                        "Remote companion for Andy Desktop. Version and updates come from GitHub Releases.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.palette.textSecondary,
                    )
                    AboutDetailBlock(
                        version = AndyBuildInfo.versionName,
                        repository = "${AndyBuildInfo.githubOwner}/${AndyBuildInfo.githubRepo}",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    ) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, ProjectGitHubUrl.toUri()),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = SettingsControlShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = tokens.palette.textPrimary,
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(tokens.palette.borderMedium),
                            ),
                        ) {
                            Text("GitHub")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, ReleasesGitHubUrl.toUri()),
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = SettingsControlShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = tokens.palette.textPrimary,
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(tokens.palette.borderMedium),
                            ),
                        ) {
                            Text("Releases")
                            Spacer(Modifier.width(AndySpace.Space1))
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            item(key = "updates") {
                UpdatesCard(
                    updateState = updateState,
                    onCheckForUpdates = {
                        scope.launch { updates.checkForUpdates() }
                    },
                    onInstallUpdate = {
                        scope.launch { updates.installAvailableUpdate() }
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutDetailBlock(
    version: String,
    repository: String,
) {
    val tokens = andyTokens()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tokens.palette.border, AndyShape.Sheet)
            .background(tokens.palette.sidebarBg, AndyShape.Sheet)
            .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
    ) {
        AboutDetailRow(label = "Version", value = version)
        AboutDetailRow(label = "Repository", value = repository)
    }
}

@Composable
private fun AboutDetailRow(
    label: String,
    value: String,
) {
    val tokens = andyTokens()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AndySpace.Space2),
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.palette.textTertiary,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            color = tokens.palette.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UpdatesCard(
    updateState: AppUpdateState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val tokens = andyTokens()
    val checking = updateState == AppUpdateState.Checking
    val installing = updateState is AppUpdateState.Installing
    val subtitle = when (updateState) {
        AppUpdateState.Idle -> "Check GitHub releases for a newer build"
        AppUpdateState.Checking -> "Checking GitHub releases…"
        AppUpdateState.Current -> "Andy is up to date"
        is AppUpdateState.Available -> "Version ${updateState.update.versionName} is available"
        is AppUpdateState.Installing -> updateState.message
        is AppUpdateState.Failed -> updateState.message
    }
    val statusColor = when (updateState) {
        is AppUpdateState.Available -> Rust
        is AppUpdateState.Failed -> Rust
        AppUpdateState.Current -> Green
        else -> tokens.palette.textSecondary
    }
    val buttonLabel = when (updateState) {
        AppUpdateState.Checking -> "Checking…"
        is AppUpdateState.Available -> "Update"
        is AppUpdateState.Installing -> "Updating…"
        else -> "Check for updates"
    }
    val onClick = if (updateState is AppUpdateState.Available) onInstallUpdate else onCheckForUpdates
    val progress = (updateState as? AppUpdateState.Installing)?.progress

    Card(
        modifier = Modifier.fillMaxWidth(),
        variant = CardVariant.Default,
        shape = AndyShape.Sheet,
        backgroundColor = tokens.palette.surfaceRaised,
        borderColor = tokens.palette.borderMedium,
        contentPadding = PaddingValues(AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Text(
            "Updates",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold,
            color = tokens.palette.textPrimary,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (updateState is AppUpdateState.Available) {
            val notes = updateState.update.releaseNotes?.trim()?.takeIf { it.isNotEmpty() }
            if (notes != null) {
                Text(
                    notes.take(600) + if (notes.length > 600) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.palette.textTertiary,
                )
            }
        }
        if (installing && progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = tokens.accent,
                trackColor = tokens.palette.border,
            )
        } else if (installing || checking) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = tokens.accent,
                trackColor = tokens.palette.border,
            )
        }
        Button(
            onClick = onClick,
            enabled = !checking && !installing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = SettingsControlShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                disabledContainerColor = tokens.palette.border,
                disabledContentColor = tokens.palette.textTertiary,
            ),
        ) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun TranscriptToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = andyTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.palette.textPrimary,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.palette.textSecondary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.onAccent,
                checkedTrackColor = tokens.accent,
                checkedBorderColor = tokens.accent,
                uncheckedThumbColor = tokens.palette.textSecondary,
                uncheckedTrackColor = tokens.palette.surfaceHover,
                uncheckedBorderColor = tokens.palette.borderMedium,
                disabledCheckedThumbColor = tokens.palette.textTertiary,
                disabledCheckedTrackColor = tokens.palette.border,
                disabledUncheckedThumbColor = tokens.palette.textTertiary,
                disabledUncheckedTrackColor = tokens.palette.border,
            ),
        )
    }
}

private fun friendlyTranscriptError(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("<!DOCTYPE", ignoreCase = true) ||
            raw.contains("<html", ignoreCase = true) ||
            raw.contains("web page", ignoreCase = true) ||
            raw.contains("not found", ignoreCase = true) ->
            "This host doesn’t expose transcript settings yet. Update Andy Desktop (or andyd), restart Network Access, and reopen Settings."
        raw.isBlank() -> "Failed to load transcript settings."
        else -> raw
    }
}
