package com.joetr.andy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.SlashCommandDto

/** Active `/token` at the end of the composer text, if any. */
data class ActiveSlashToken(val start: Int, val end: Int, val query: String)

fun findActiveSlashToken(text: String): ActiveSlashToken? {
    val match = Regex("""(?:^|\s)/([^\s]*)$""").find(text) ?: return null
    val group = match.groups[1] ?: return null
    return ActiveSlashToken(
        start = group.range.first - 1, // include leading '/'
        end = text.length,
        query = group.value,
    )
}

fun insertSlashCommand(text: String, token: ActiveSlashToken, name: String): String {
    val insertion = "/$name "
    return text.replaceRange(token.start, token.end, insertion)
}

@Composable
fun rememberSlashCommands(
    client: NetworkAccessClient,
    agentId: String,
    projectDirectory: String? = null,
): List<SlashCommandDto> {
    var commands by remember(agentId, projectDirectory) { mutableStateOf<List<SlashCommandDto>>(emptyList()) }
    LaunchedEffect(agentId, projectDirectory) {
        if (agentId.isBlank()) {
            commands = emptyList()
            return@LaunchedEffect
        }
        commands = runCatching {
            client.listSlashCommands(agentId, projectDirectory)
        }.getOrDefault(emptyList())
    }
    return commands
}

@Composable
fun SlashCommandSuggestions(
    query: String,
    commands: List<SlashCommandDto>,
    onSelect: (SlashCommandDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val filtered = remember(query, commands) {
        val q = query.trim().lowercase()
        commands.filter { command ->
            q.isEmpty() ||
                command.name.lowercase().contains(q) ||
                command.description.lowercase().contains(q)
        }.take(12)
    }
    if (filtered.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(AndyShape.Sheet)
            .border(1.dp, tokens.palette.borderMedium, AndyShape.Sheet)
            .background(tokens.palette.surfaceRaised)
            .verticalScroll(rememberScrollState())
            .padding(vertical = AndySpace.Space1),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        filtered.forEach { command ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(command) }
                    .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
            ) {
                Text(
                    "/${command.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = MonoFont,
                    color = tokens.accent,
                )
                if (command.description.isNotBlank()) {
                    Text(
                        command.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.palette.textTertiary,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
