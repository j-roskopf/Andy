package com.joetr.andy.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.Button
import app.andy.ui.components.Card
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusDot
import app.andy.ui.components.StatusDotVariant
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.andyTokens
import com.joetr.andy.mobile.data.SavedHost
import com.joetr.andy.mobile.data.networkaccess.ChatDto
import com.joetr.andy.mobile.data.networkaccess.ChatEventDto
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessClient
import com.joetr.andy.mobile.data.networkaccess.NetworkAccessException
import com.joetr.andy.mobile.data.networkaccess.UserInputRequestDto
import com.joetr.andy.mobile.data.networkaccess.coalesceStreams
import com.joetr.andy.mobile.data.networkaccess.isVisibleTranscript
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    chatId: String,
    host: SavedHost,
    client: NetworkAccessClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = andyTokens()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var chat by remember { mutableStateOf<ChatDto?>(null) }
    var events by remember { mutableStateOf<List<ChatEventDto>>(emptyList()) }
    var pendingInput by remember { mutableStateOf<UserInputRequestDto?>(null) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var optimistic by remember { mutableStateOf<String?>(null) }
    val agentId = chat?.agent.orEmpty()
    val slashCommands = rememberSlashCommands(client, agentId)
    val slashToken = remember(draft) { findActiveSlashToken(draft) }

    suspend fun loadOnce() {
        val detail = client.chatDetail(chatId)
        chat = detail.chat
        events = detail.events.coalesceStreams()
        pendingInput = detail.chat.userInputRequest
        loading = false
    }

    LaunchedEffect(chatId) {
        try {
            loadOnce()
        } catch (e: Exception) {
            error = e.message
            loading = false
        }
        // Prefer websocket; fall back to quiet polling if WS fails.
        try {
            client.observeChat(chatId).collect { batch ->
                batch.chat?.let { chat = it }
                if (batch.events.isNotEmpty()) {
                    events = (events + batch.events).coalesceStreams()
                }
                when {
                    batch.clearUserInput -> pendingInput = null
                    batch.userInputRequest != null -> pendingInput = batch.userInputRequest
                }
                optimistic = null
            }
        } catch (_: Exception) {
            while (isActive) {
                delay(2_500)
                runCatching { loadOnce() }
            }
        }
    }

    val visibleCount = events.count { it.isVisibleTranscript() } + if (optimistic != null) 1 else 0
    LaunchedEffect(visibleCount) {
        if (visibleCount > 0) {
            listState.animateScrollToItem(visibleCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.palette.windowBg)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = tokens.palette.textPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    chat?.title?.ifBlank { null } ?: host.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.palette.textPrimary,
                    maxLines = 1,
                )
                Text(
                    listOfNotNull(chat?.agent, chat?.status).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.palette.textTertiary,
                )
            }
        }
        AndyHorizontalDivider(color = tokens.palette.border)

        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            else -> {
                val visible = events.filter { it.isVisibleTranscript() }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(AndySpace.Space4),
                    verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    items(
                        visible,
                        key = { "${it.atMillis}-${it.type}-${it.text.hashCode()}" },
                        contentType = { it.type },
                    ) { event ->
                        TranscriptBubble(event)
                    }
                    optimistic?.let { text ->
                        item(key = "optimistic") {
                            TranscriptBubble(ChatEventDto(type = "user", text = text))
                        }
                    }
                }
            }
        }

        error?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = AndySpace.Space4),
                color = tokens.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        pendingInput?.let { request ->
            PermissionCard(
                request = request,
                onSubmit = { answers ->
                    scope.launch {
                        try {
                            client.respond(chatId, request.id, answers)
                            pendingInput = null
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                },
            )
        }

        if (slashToken != null && agentId.isNotBlank()) {
            SlashCommandSuggestions(
                query = slashToken.query,
                commands = slashCommands,
                onSelect = { command ->
                    draft = insertSlashCommand(draft, slashToken, command.name)
                },
                modifier = Modifier.padding(horizontal = AndySpace.Space3),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.palette.sidebarBg),
        ) {
            AndyHorizontalDivider(color = tokens.palette.border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AndySpace.Space3),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                MobileField(
                    label = "Message",
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "Follow up… Type / for commands",
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val message = draft.trim()
                        if (message.isEmpty() || sending) return@IconButton
                        scope.launch {
                            sending = true
                            error = null
                            optimistic = message
                            draft = ""
                            try {
                                client.reply(chatId, message)
                            } catch (e: NetworkAccessException) {
                                optimistic = null
                                error = e.message
                            } catch (e: Exception) {
                                optimistic = null
                                error = e.message
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank() && !sending) tokens.accent else tokens.palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(event: ChatEventDto) {
    val tokens = andyTokens()
    val isUser = event.type == "user"
    val isThinking = event.type == "thinking"
    val isError = event.type == "error"
    val bg = when {
        isUser -> tokens.accent.copy(alpha = 0.18f)
        isError -> tokens.error.copy(alpha = 0.16f)
        isThinking -> tokens.palette.surfaceHover
        else -> tokens.palette.surfaceRaised
    }
    val fg = when {
        isError -> tokens.error
        isThinking -> tokens.palette.textTertiary
        else -> tokens.palette.textPrimary
    }
    val bubbleShape = RoundedCornerShape(
        topStart = AndyRadius.Chat,
        topEnd = AndyRadius.Chat,
        bottomStart = if (isUser) AndyRadius.Chat else 4.dp,
        bottomEnd = if (isUser) 4.dp else AndyRadius.Chat,
    )
    val borderStroke = when {
        isUser -> BorderStroke(1.dp, tokens.accent.copy(alpha = 0.35f))
        isError -> BorderStroke(1.dp, tokens.error.copy(alpha = 0.4f))
        else -> BorderStroke(1.dp, tokens.palette.border)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = event.text.ifBlank {
                when (event.type) {
                    "permission-resolved" -> "Permission ${if (event.allowed == true) "allowed" else "rejected"}"
                    else -> event.type
                }
            },
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(bubbleShape)
                .border(borderStroke, bubbleShape)
                .background(bg)
                .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
            style = if (isThinking) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = fg,
        )
    }
}

@Composable
private fun PermissionCard(
    request: UserInputRequestDto,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val tokens = andyTokens()
    var answers by remember(request.id) { mutableStateOf(mapOf<String, String>()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AndySpace.Space3),
        shape = AndyShape.Sheet,
        backgroundColor = tokens.palette.surfaceRaised,
        borderColor = tokens.palette.borderMedium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AndySpace.Space3),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                StatusDot(variant = StatusDotVariant.Warning)
                Text(
                    "Needs your input",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.palette.textPrimary,
                )
            }
            request.questions.forEach { question ->
                Text(
                    question.header.ifBlank { question.question },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.palette.textSecondary,
                )
                if (question.options.isNotEmpty()) {
                    question.options.forEach { option ->
                        val selected = answers[question.id] == option.label
                        OutlinedButton(
                            onClick = { answers = answers + (question.id to option.label) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = AndyShape.Interactive,
                        ) {
                            Text(
                                option.label + if (selected) " ✓" else "",
                                color = if (selected) tokens.accent else tokens.palette.textPrimary,
                            )
                        }
                    }
                } else {
                    MobileField(
                        label = "Answer",
                        value = answers[question.id].orEmpty(),
                        onValueChange = { answers = answers + (question.id to it) },
                        placeholder = question.question,
                    )
                }
            }
            Button(
                onClick = { onSubmit(answers) },
                enabled = request.questions.all { answers[it.id].orEmpty().isNotBlank() },
                shape = AndyShape.Interactive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Submit")
            }
        }
    }
}
