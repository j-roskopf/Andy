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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import app.andy.ui.components.Button
import app.andy.ui.components.Card
import app.andy.ui.components.CardVariant
import app.andy.ui.components.OutlinedButton
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.andyTokens
import app.andy.updates.AndyBuildInfo
import kotlinx.coroutines.launch

private const val ProjectGitHubUrl = "https://github.com/j-roskopf/Andy"
private const val ReleasesGitHubUrl = "https://github.com/j-roskopf/Andy/releases"

@Composable
fun SettingsScreen(
    updates: AppUpdateService,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by updates.state.collectAsStateWithLifecycle()

    Column(modifier.background(tokens.palette.windowBg)) {
        MobileHeader(
            title = "Settings",
            subtitle = "About this build and GitHub updates",
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
            item(key = "about") {
                Card(
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
                    Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, ProjectGitHubUrl.toUri()),
                                )
                            },
                            shape = AndyShape.Interactive,
                        ) {
                            Text("GitHub")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, ReleasesGitHubUrl.toUri()),
                                )
                            },
                            shape = AndyShape.Interactive,
                        ) {
                            Text("Releases")
                            Spacer(Modifier.width(AndySpace.Space1))
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
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
            shape = AndyShape.Interactive,
        ) {
            Text(buttonLabel)
        }
    }
}
