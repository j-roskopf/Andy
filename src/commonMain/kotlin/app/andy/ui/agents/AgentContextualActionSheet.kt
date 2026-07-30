package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.AgentChatAttachAction
import app.andy.domain.AgentChatEligibility
import app.andy.domain.eligibleAgentChatsForContext
import app.andy.model.AgentEvidencePreview
import app.andy.model.ContextualActionRequest
import app.andy.model.EvidenceMaterializeRequest
import app.andy.model.EvidencePreviewRequest
import app.andy.model.ManagedEvidenceBundle
import app.andy.model.attachesAndyMcpByDefault
import app.andy.model.promptDraft
import app.andy.model.questionWithExcerpt
import app.andy.service.AndyServices
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.shell.SuppressHeavyweightSurfacesWhileOpen
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Confirmation sheet for a contextual agent action (§5). Shows the editable prompt alongside
 * exactly what evidence would be attached — selected events, time window, size, exclusions, and
 * redactions — and only materializes a bundle or contacts a provider once Start is pressed.
 */
@Composable
internal fun AgentContextualActionSheet(
    request: ContextualActionRequest,
    services: AndyServices,
    onDismiss: () -> Unit,
    onLaunched: (OpenAgentTaskRequest) -> Unit = {},
) {
    SuppressHeavyweightSurfacesWhileOpen()
    val scope = rememberCoroutineScope()

    val tasks by services.agentRuns.tasks.collectAsState()
    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    val lastUsedAgent by services.agentRuns.lastUsedAgent.collectAsState()
    val providerDefaults by services.agentRuns.providerDefaults.collectAsState()

    val eligibleChats = remember(tasks) { eligibleAgentChatsForContext(tasks, projectId = null).take(6) }
    val agent = contextualAgentKind(lastUsedAgent, cliStatuses)
    val defaults = agent?.let { providerDefaults[it] }

    var prompt by remember(request) { mutableStateOf(request.promptDraft()) }
    var preview by remember(request) { mutableStateOf<AgentEvidencePreview?>(null) }
    var previewing by remember(request) { mutableStateOf(request.hasEvidence) }
    var target by remember(request) { mutableStateOf<ContextualLaunchTarget>(ContextualLaunchTarget.NewTask) }
    var launching by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        val evidence = request.evidence ?: return@LaunchedEffect
        previewing = true
        val result = runCatching {
            services.evidence.preview(
                EvidencePreviewRequest(
                    question = request.questionWithExcerpt(),
                    evidence = evidence,
                    provenance = request.provenance,
                ),
            )
        }
        result.onSuccess { evidencePreview ->
            preview = evidencePreview
            prompt = evidencePreview.promptDraft
        }.onFailure { failure ->
            error = failure.message ?: "Could not preview investigation evidence."
        }
        previewing = false
    }

    fun confirm() {
        val selectedAgent = agent ?: return
        launching = true
        error = null
        scope.launch {
            val bundle: ManagedEvidenceBundle? = request.evidence?.let { evidence ->
                runCatching {
                    services.evidence.materialize(EvidenceMaterializeRequest(evidence, request.provenance))
                }.getOrElse { failure ->
                    error = "Evidence bundle failed (${failure.message ?: "unknown error"}); sending the prompt alone."
                    null
                }
            }
            val bundleIds = listOfNotNull(bundle?.id)
            val launched = runCatching {
                when (val chosen = target) {
                    ContextualLaunchTarget.NewTask -> {
                        val task = services.agentRuns.createAndStart(
                            contextualTaskDraft(request, prompt, selectedAgent, defaults, bundleIds),
                        )
                        OpenAgentTaskRequest(task.id, task.projectId)
                    }
                    is ContextualLaunchTarget.ExistingChat -> {
                        val chat = eligibleChats.firstOrNull { it.taskId == chosen.taskId }
                        when (chat?.action) {
                            AgentChatAttachAction.QueueFollowUp -> services.agentRuns.queueFollowUp(
                                taskId = chosen.taskId,
                                followUp = prompt,
                                contextBundleIds = bundleIds,
                                provenance = request.provenance,
                            )
                            AgentChatAttachAction.Resume -> services.agentRuns.resume(
                                taskId = chosen.taskId,
                                followUp = prompt,
                                contextBundleIds = bundleIds,
                                provenance = request.provenance,
                            )
                            else -> return@runCatching null
                        }
                        OpenAgentTaskRequest(chosen.taskId, chat.projectId)
                    }
                }
            }
            launching = false
            launched.fold(
                onSuccess = { opened ->
                    if (opened != null) {
                        onLaunched(opened)
                        onDismiss()
                    }
                },
                onFailure = { failure -> error = failure.message ?: "Could not start the chat." },
            )
        }
    }

    val targetBlocked = (target as? ContextualLaunchTarget.ExistingChat)?.let { chosen ->
        eligibleChats.firstOrNull { it.taskId == chosen.taskId }?.action == AgentChatAttachAction.Blocked
    } == true
    val confirmEnabled = !launching && !previewing && prompt.isNotBlank() && agent != null && !targetBlocked

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .heightIn(max = 720.dp)
                .background(Panel, RoundedCornerShape(AndyRadius.R4))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    request.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }

            LabeledField(
                label = "PROMPT",
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = false,
                minHeight = 150.dp,
                placeholder = "What should the agent look into?",
            )

            EvidenceSummary(request, preview, previewing)

            TargetPicker(
                chats = eligibleChats,
                target = target,
                onTargetChange = { target = it },
            )

            Text(
                launchSettingsLine(request, agent?.label, defaults?.model),
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            if (agent == null) {
                Text(
                    "No agent CLI is ready. Install or configure one in Settings first.",
                    color = Rust,
                    fontSize = 12.sp,
                )
            }
            error?.let { message ->
                Text(message, color = Rust, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = ::confirm,
                    enabled = confirmEnabled,
                    colors = primaryButtonColors(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        when {
                            launching -> "Starting…"
                            target is ContextualLaunchTarget.ExistingChat -> "Send to chat"
                            else -> "Start chat"
                        },
                    )
                }
                if (launching) {
                    CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp, color = Rust)
                }
            }
        }
    }
}

@Composable
private fun EvidenceSummary(
    request: ContextualActionRequest,
    preview: AgentEvidencePreview?,
    previewing: Boolean,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.R2))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("EVIDENCE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        when {
            previewing -> Text("Reading investigation…", color = TextSecondary, fontSize = 12.sp)
            !request.hasEvidence -> Text(
                "No saved investigation holds this context, so only the prompt above is sent.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            preview == null -> Text("Evidence preview is unavailable.", color = TextSecondary, fontSize = 12.sp)
            else -> {
                Text(preview.selectedEventsSummary, color = TextPrimary, fontSize = 12.sp)
                Text(
                    "Window ${formatWindow(preview.windowStartMillis, preview.windowEndMillis)} · " +
                        "${preview.artifacts.size} file(s) · ${formatEvidenceBytes(preview.totalBytes)}",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                redactionLine(preview)?.let { line ->
                    Text(line, color = TextSecondary, fontSize = 11.sp)
                }
                preview.exclusions.take(3).forEach { exclusion ->
                    Text(
                        "Excluded: $exclusion",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(
    chats: List<AgentChatEligibility>,
    target: ContextualLaunchTarget,
    onTargetChange: (ContextualLaunchTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SEND TO", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        FilterPill("New chat", target is ContextualLaunchTarget.NewTask, Rust) {
            onTargetChange(ContextualLaunchTarget.NewTask)
        }
        chats.forEach { chat ->
            val blocked = chat.action == AgentChatAttachAction.Blocked
            val selected = (target as? ContextualLaunchTarget.ExistingChat)?.taskId == chat.taskId
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterPill(
                    "${chat.title.ifBlank { chat.taskId }} · ${chat.action.label()}",
                    selected,
                    Rust,
                    enabled = !blocked,
                ) {
                    onTargetChange(ContextualLaunchTarget.ExistingChat(chat.taskId))
                }
                chat.blockedReason?.let { reason ->
                    Text(reason, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun AgentChatAttachAction.label(): String = when (this) {
    AgentChatAttachAction.NewTask -> "new"
    AgentChatAttachAction.QueueFollowUp -> "queue follow-up"
    AgentChatAttachAction.Resume -> "resume"
    AgentChatAttachAction.Blocked -> "blocked"
}

private fun launchSettingsLine(request: ContextualActionRequest, agentLabel: String?, model: String?): String = listOfNotNull(
    agentLabel ?: "no provider",
    model ?: "provider default model",
    "read-only",
    "no worktree",
    if (request.kind.attachesAndyMcpByDefault()) "Andy MCP on" else "Andy MCP off",
).joinToString(" · ")

private fun redactionLine(preview: AgentEvidencePreview): String? {
    val report = preview.redactionReport
    if (report.isEmpty) return null
    return buildList {
        report.redactedHeaderNames.takeIf { it.isNotEmpty() }?.let {
            add("headers redacted: ${it.joinToString(", ")}")
        }
        if (report.redactedNodeCount > 0) add("${report.redactedNodeCount} password node(s) redacted")
        report.truncatedFields.size.takeIf { it > 0 }?.let { add("$it field(s) truncated") }
    }.ifEmpty { null }?.joinToString(" · ")
}

private fun formatWindow(startMillis: Long, endMillis: Long): String {
    val seconds = (endMillis - startMillis).coerceAtLeast(0L) / 1000.0
    return "${app.andy.formatDecimal(seconds, 1)}s"
}

private fun formatEvidenceBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${app.andy.formatDecimal(kb, 1)} KB"
    return "${app.andy.formatDecimal(kb / 1024.0, 1)} MB"
}
