package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.modelConfigurationLabel
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun AgentTaskDetail(
    services: AndyServices,
    task: AgentTask,
    nowMillis: Long,
    onDelete: (AgentTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val events by services.agentRuns.events(task.id).collectAsState()
    val availableSkills by services.agentRuns.availableSkills.collectAsState()
    var followUp by remember(task.id) { mutableStateOf("") }
    var skillMenuDismissed by remember(task.id) { mutableStateOf(false) }
    var diffSummary by remember(task.id) { mutableStateOf<String?>(null) }
    var changeSummary by remember(task.id) { mutableStateOf<AgentChangeSummary?>(null) }
    var showAllChangedFiles by remember(task.id) { mutableStateOf(false) }
    var copiedHint by remember(task.id) { mutableStateOf(false) }
    var showOriginalPrompt by remember(task.id) { mutableStateOf(false) }
    var followUpImagePaths by remember(task.id) { mutableStateOf<List<String>>(emptyList()) }
    var followUpImageDragActive by remember(task.id) { mutableStateOf(false) }

    val supportsResume = task.vendorSessionId != null && task.agent != AgentKind.Antigravity
    val canSendFollowUp = followUp.isNotBlank() || followUpImagePaths.isNotEmpty()
    val slashCommand = findActiveSlashCommand(followUp)
    val matchingSkills = slashCommand?.let { command ->
        availableSkills.filter { skill ->
            skill.name.contains(command.query, ignoreCase = true) ||
                skill.description.contains(command.query, ignoreCase = true)
        }.take(8)
    }.orEmpty()
    val selectedSkills = remember(followUp, availableSkills) {
        availableSkills.filter { skill -> followUp.referencesSkill(skill) }
    }

    fun selectSkill(skill: AgentSkill) {
        val command = findActiveSlashCommand(followUp) ?: return
        followUp = followUp.replaceRange(command.start, command.end, "/${skill.name} ")
        skillMenuDismissed = true
    }

    fun submitFollowUp() {
        if (!supportsResume || !canSendFollowUp) return
        services.agentRuns.resume(task.id, followUp.trim(), followUpImagePaths, selectedSkills)
        followUp = ""
        followUpImagePaths = emptyList()
    }

    LaunchedEffect(task.id, task.status) {
        if (task.worktreePath != null) {
            diffSummary = services.agentRuns.worktreeDiffSummary(task.id)
        }
        changeSummary = if (task.isActive) null else services.agentRuns.changeSummary(task.id)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentBadge(task.agent)
            Column(Modifier.weight(1f)) {
                Text(task.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(task.cwd ?: "no project context", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(task.modelConfigurationLabel(), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            formatElapsed(task.startedAtMillis, task.finishedAtMillis, nowMillis)?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            }
            formatCost(task.totalCostUsd, task.costIsEstimated)?.let {
                Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
            }
            StatusTag(agentStatusLabel(task.status), agentStatusColor(task.status))
            if (task.isActive) {
                Button(
                    onClick = { services.agentRuns.stop(task.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("stop", fontSize = 11.sp) }
            }
            if (task.status == AgentTaskStatus.Failed) {
                Button(
                    onClick = { scope.launch { services.agentRuns.retry(task.id) } },
                    colors = primaryButtonColors(),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("retry", fontSize = 11.sp) }
            }
            OutlinedButton(
                onClick = { onDelete(task) },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("del", fontSize = 11.sp) }
        }

        task.errorMessage?.let { error ->
            Text(error, color = app.andy.ui.theme.Red, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (task.status == AgentTaskStatus.Unknown) {
            Text(
                "interrupted by an app restart — continue interactively to pick the session back up",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showOriginalPrompt = !showOriginalPrompt },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text(if (showOriginalPrompt) "hide original prompt" else "original prompt", fontSize = 11.sp) }
            OutlinedButton(
                onClick = { clipboardManager.setText(AnnotatedString(task.prompt)) },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("copy prompt", fontSize = 11.sp) }
        }
        if (showOriginalPrompt) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(task.prompt, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp)
                if (task.imagePaths.isNotEmpty()) {
                    Text("attached images", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                    task.imagePaths.forEach { path ->
                        Text(path, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
                if (task.skills.isNotEmpty()) {
                    Text("selected skills", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        task.skills.forEach { skill ->
                            Text(
                                "/${skill.name}",
                                color = Cyan,
                                fontFamily = MonoFont,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { scope.launch { services.agentRuns.openSkill(skill.path) } },
                            )
                        }
                    }
                }
            }
        }

        AgentTranscript(
            events,
            isActive = task.isActive,
            onSkillOpen = { skill -> scope.launch { services.agentRuns.openSkill(skill.path) } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        changeSummary?.takeIf { it.files.isNotEmpty() }?.let { summary ->
            AgentChangeSummaryCard(
                summary = summary,
                showAllFiles = showAllChangedFiles,
                onShowAllFilesChange = { showAllChangedFiles = it },
            )
        }

        if (!task.isActive) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (supportsResume) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth()) {
                            TextField(
                                followUp,
                                {
                                    followUp = it
                                    skillMenuDismissed = false
                                },
                                singleLine = false,
                                minLines = 2,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 54.dp, max = 160.dp)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        if (event.key == Key.Tab && matchingSkills.isNotEmpty()) {
                                            selectSkill(matchingSkills.first())
                                            return@onPreviewKeyEvent true
                                        }
                                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                                        if (event.isShiftPressed) return@onPreviewKeyEvent false
                                        if (canSendFollowUp) submitFollowUp()
                                        true
                                    }
                                    .onImageFilesDropped(
                                        onFiles = { dropped -> followUpImagePaths = (followUpImagePaths + dropped).distinct() },
                                        onDragActiveChange = { active -> followUpImageDragActive = active },
                                    )
                                    .border(
                                        if (followUpImageDragActive) 2.dp else 1.dp,
                                        if (followUpImageDragActive) Cyan else Border,
                                        RoundedCornerShape(AndyRadius.R3),
                                    ),
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp),
                                colors = fieldColors(),
                                placeholder = {
                                    Text(
                                        if (followUpImageDragActive) {
                                            "release to attach image"
                                        } else {
                                            "follow-up prompt — type / for skills, enter to send"
                                        },
                                        color = if (followUpImageDragActive) Cyan else TextSecondary,
                                        fontFamily = MonoFont,
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                            DropdownMenu(
                                expanded = slashCommand != null && !skillMenuDismissed,
                                onDismissRequest = { skillMenuDismissed = true },
                                modifier = Modifier.widthIn(min = 300.dp, max = 460.dp),
                                properties = PopupProperties(focusable = false),
                            ) {
                                Text(
                                    if (matchingSkills.isEmpty()) "no skills matching /${slashCommand?.query.orEmpty()}" else "skills matching /${slashCommand?.query.orEmpty()}",
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                                matchingSkills.forEach { skill ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("/${skill.name}", color = Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                                                skill.description.takeIf { it.isNotBlank() }?.let { description ->
                                                    Text(description, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        },
                                        onClick = { selectSkill(skill) },
                                    )
                                }
                            }
                        }
                        if (selectedSkills.isNotEmpty()) {
                            Text("selected skills", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                selectedSkills.forEach { skill ->
                                    FilterPill("/${skill.name} ×", true, Cyan) {
                                        followUp = followUp.removeSelectedSkill(skill)
                                    }
                                }
                            }
                        }
                        }
                        Button(
                            onClick = { submitFollowUp() },
                            enabled = canSendFollowUp,
                            colors = primaryButtonColors(),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("send", fontSize = 11.sp) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = {
                            services.agentRuns.interactiveResumeCommand(task.id)?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                copiedHint = true
                            }
                            scope.launch { services.agentRuns.openInTerminal(task.id) }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) { Text(if (copiedHint) "opened" else "terminal", fontSize = 11.sp) }
                }
                if (followUpImagePaths.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        followUpImagePaths.forEach { path ->
                            OutlinedButton(
                                onClick = { followUpImagePaths = followUpImagePaths.filterNot { it == path } },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                            ) { Text("${path.substringAfterLast('/').substringAfterLast('\\')} ×", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }

        if (task.worktreePath != null) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("worktree ${task.branchName.orEmpty()}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedButton(
                        onClick = { clipboardManager.setText(AnnotatedString(task.worktreePath.orEmpty())) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("copy path", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = {
                            val branch = task.branchName ?: return@OutlinedButton
                            val originDir = task.originDir ?: return@OutlinedButton
                            clipboardManager.setText(AnnotatedString("git -C '$originDir' merge '$branch'"))
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("copy merge cmd", fontSize = 10.sp) }
                    OutlinedButton(
                        onClick = { scope.launch { diffSummary = services.agentRuns.worktreeDiffSummary(task.id) } },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) { Text("refresh diff", fontSize = 10.sp) }
                }
                Text(
                    diffSummary ?: "loading diff…",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private data class SlashCommand(val start: Int, val end: Int, val query: String)

/** Finds a slash token only while the cursor is effectively at the end of the prompt. */
private fun findActiveSlashCommand(text: String): SlashCommand? {
    val match = Regex("(?:^|\\s)/([A-Za-z0-9:_-]*)$").find(text) ?: return null
    val tokenStart = match.range.first + if (match.value.startsWith('/') ) 0 else 1
    return SlashCommand(start = tokenStart, end = text.length, query = match.groupValues[1])
}

private fun String.referencesSkill(skill: AgentSkill): Boolean =
    Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)").containsMatchIn(this)

private fun String.removeSelectedSkill(skill: AgentSkill): String =
    replace(Regex("(?:^|\\s)/${Regex.escape(skill.name)}(?=\\s|$)"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

@Composable
private fun AgentChangeSummaryCard(
    summary: AgentChangeSummary,
    showAllFiles: Boolean,
    onShowAllFilesChange: (Boolean) -> Unit,
) {
    val displayedFiles = if (showAllFiles) summary.files else summary.files.take(3)
    val remaining = summary.files.size - displayedFiles.size
    Column(
        Modifier.fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("▣", color = Cyan, fontFamily = MonoFont, fontSize = 15.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    "Edited ${summary.files.size} ${if (summary.files.size == 1) "file" else "files"}",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+${summary.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                    Text("-${summary.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
        }
        displayedFiles.forEach { file ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    file.path,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("+${file.additions}", color = Green, fontFamily = MonoFont, fontSize = 11.sp)
                Text("-${file.deletions}", color = Red, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
        if (remaining > 0 || showAllFiles) {
            OutlinedButton(
                onClick = { onShowAllFilesChange(!showAllFiles) },
                modifier = Modifier.padding(horizontal = 8.dp).height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 1.dp),
            ) {
                Text(if (showAllFiles) "show fewer files" else "show $remaining more files", fontSize = 10.sp)
            }
        }
    }
}
