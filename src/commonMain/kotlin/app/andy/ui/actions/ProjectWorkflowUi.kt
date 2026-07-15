package app.andy.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.ActionProject
import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectBuildPairDraft
import app.andy.model.ProjectPlanSnapshot
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectSpecDraft
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectWorkflowState
import app.andy.model.defaultSandboxMode
import app.andy.model.labelFor
import app.andy.model.sandboxControlLabel
import app.andy.formatDecimal
import app.andy.service.AndyServices
import app.andy.ui.agents.AgentBadge
import app.andy.ui.agents.AgentProviderModelProfileControls
import app.andy.ui.agents.AgentUserInputCard
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.MarkdownPreview
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TextField
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class BuildEditorSeed(
    val buildTaskId: String? = null,
    val plan: ProjectPlanSnapshot? = null,
)

@Composable
internal fun ProjectWorkflowList(
    workflow: ProjectWorkflowState,
    selectedTaskId: String?,
    onSelectTask: (String) -> Unit,
    onNewSpec: () -> Unit,
    onNewBuild: () -> Unit,
    onProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember(workflow.projectId) { mutableStateMapOf<String, Boolean>() }
    val specs = workflow.tasks.filter { it.kind == ProjectTaskKind.Spec }.sortedByDescending { it.updatedAtMillis }
    val builds = workflow.tasks.filter { it.kind == ProjectTaskKind.Build }
    val standaloneBuilds = builds.filter { it.linkedSpecTaskId == null }.sortedByDescending { it.updatedAtMillis }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Column {
                Text("Task workflows", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Specs feed build + optional review + verification loops", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onProfiles) { Text("Profiles") }
                OutlinedButton(onClick = onNewBuild) { Text("New build") }
                Button(onClick = onNewSpec, colors = primaryButtonColors()) { Text("New spec") }
            }
        }
        if (workflow.tasks.isEmpty()) {
            EmptyState("Create a spec, or start a build from an external plan")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(specs, key = { "spec-group-${it.id}" }) { spec ->
                    val childBuilds = builds.filter { it.linkedSpecTaskId == spec.id }.sortedByDescending { it.updatedAtMillis }
                    val open = expanded[spec.id] ?: true
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, if (selectedTaskId == spec.id) AndyColors.OrangeBorder else Border, RoundedCornerShape(AndyRadius.R3))
                            .animateContentSize(tween(180)),
                    ) {
                        WorkflowRow(
                            task = spec,
                            selected = selectedTaskId == spec.id,
                            depth = 0,
                            meta = spec.planVersions.lastOrNull()?.let { "plan v${it.version}" } ?: "plan not run",
                            expanded = open,
                            expandable = childBuilds.isNotEmpty(),
                            onToggle = { expanded[spec.id] = !open },
                            onClick = { onSelectTask(spec.id) },
                        )
                        AnimatedVisibility(
                            visible = open,
                            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
                        ) {
                            Column {
                                childBuilds.forEach { build ->
                                    WorkflowBuildPairRows(workflow, build, selectedTaskId, onSelectTask)
                                }
                            }
                        }
                    }
                }
                if (standaloneBuilds.isNotEmpty()) {
                    item(key = "standalone-builds-label") {
                        Text("STANDALONE BUILDS", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    items(standaloneBuilds, key = { "standalone-build-${it.id}" }) { build ->
                        Column(Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))) {
                            WorkflowBuildPairRows(workflow, build, selectedTaskId, onSelectTask)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowBuildPairRows(
    workflow: ProjectWorkflowState,
    build: ProjectTask,
    selectedTaskId: String?,
    onSelectTask: (String) -> Unit,
) {
    val review = workflow.tasks.firstOrNull { it.id == build.linkedReviewTaskId }
    val verification = workflow.tasks.firstOrNull { it.id == build.linkedVerificationTaskId }
    WorkflowRow(
        task = build,
        selected = selectedTaskId == build.id,
        depth = 1,
        meta = "${build.attempts.size} build attempt${if (build.attempts.size == 1) "" else "s"}",
        onClick = { onSelectTask(build.id) },
    )
    review?.let { item ->
        val latest = item.reviewVerdicts.lastOrNull()
        WorkflowRow(
            task = item,
            selected = selectedTaskId == item.id,
            depth = 2,
            meta = when {
                item.state == ProjectTaskState.Disabled -> "disabled · ${item.attempts.size} attempt${if (item.attempts.size == 1) "" else "s"} retained"
                latest != null -> "${reviewStatusLabel(latest.status)} · ${item.attempts.size} attempt${if (item.attempts.size == 1) "" else "s"}"
                else -> "${item.attempts.size} attempt${if (item.attempts.size == 1) "" else "s"} · no verdict"
            },
            onClick = { onSelectTask(item.id) },
        )
    }
    verification?.let { verify ->
        WorkflowRow(
            task = verify,
            selected = selectedTaskId == verify.id,
            depth = 2,
            meta = verify.verdicts.lastOrNull()?.let { "${it.status.name.lowercase()} · attempt ${verify.attempts.size}/5" }
                ?: "attempt ${verify.attempts.size}/5 · no verdict",
            onClick = { onSelectTask(verify.id) },
        )
    }
}

@Composable
private fun WorkflowRow(
    task: ProjectTask,
    selected: Boolean,
    depth: Int,
    meta: String,
    expanded: Boolean = false,
    expandable: Boolean = false,
    onToggle: () -> Unit = {},
    onClick: () -> Unit,
) {
    val accent = taskAccent(task)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.12f) else AndyColors.Neutral900.copy(alpha = 0.68f))
            .clickable(onClick = onClick)
            .padding(start = (10 + depth * 22).dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (expandable) {
            Text(if (expanded) "v" else ">", color = TextSecondary, fontFamily = MonoFont, modifier = Modifier.width(10.dp).clickable(onClick = onToggle))
        } else {
            Box(Modifier.width(3.dp).height(30.dp).background(accent, RoundedCornerShape(2.dp)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(task.kind.label.uppercase(), color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Text(task.title, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(meta, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusTag(taskStateLabel(task.state), taskStateColor(task.state))
    }
}

@Composable
internal fun ProjectWorkflowDetail(
    services: AndyServices,
    workflow: ProjectWorkflowState,
    task: ProjectTask?,
    agentTasks: List<AgentTask>,
    onNewBuildFromPlan: (ProjectPlanSnapshot) -> Unit,
    onOpenRun: (String) -> Unit,
    onEdit: (ProjectTask) -> Unit,
    onDelete: (ProjectTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    if (task == null) {
        EmptyState("Select a workflow task to inspect its inputs, outputs, and attempts")
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(4.dp).height(48.dp).background(taskAccent(task), RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text(task.kind.label.uppercase(), color = taskAccent(task), fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(task.title, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
                Text(profileSummary(task.profile), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
            }
            StatusTag(taskStateLabel(task.state), taskStateColor(task.state))
        }
        task.lastError?.let { WorkflowNotice(it, Red) }
        task.attempts.asReversed().firstNotNullOfOrNull { attempt ->
            agentTasks.firstOrNull { it.id == attempt.runId }?.userInputRequest
                ?.let { request -> attempt.runId to request }
        }?.let { (runId, request) ->
            AgentUserInputCard(
                request = request,
                onSubmit = { answers -> services.agentRuns.respondToUserInput(runId, request.id, answers) },
            )
        }
        when (task.kind) {
            ProjectTaskKind.Spec -> SpecDetail(task, onNewBuildFromPlan) {
                scope.launch { services.projectWorkflows.runSpec(task.id) }
            }
            ProjectTaskKind.Build -> BuildDetail(services, workflow, task, agentTasks)
            ProjectTaskKind.Review -> ReviewDetail(task, agentTasks)
            ProjectTaskKind.Verification -> VerificationDetail(task)
        }
        if (task.instructions.isNotBlank() && task.kind == ProjectTaskKind.Spec) DetailBlock("BRIEF", task.instructions)
        if (task.attempts.isNotEmpty()) {
            Text("RUN ACTIVITY", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            task.attempts.sortedByDescending { it.createdAtMillis }.forEach { attempt ->
                val run = agentTasks.firstOrNull { it.id == attempt.runId }
                Row(
                    Modifier.fillMaxWidth().background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3)).clickable { onOpenRun(attempt.runId) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentBadge(attempt.profile.agent)
                    Column(Modifier.weight(1f)) {
                        Text("${attempt.stage.name.lowercase()} attempt ${attempt.attempt}", color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        Text(run?.status?.name?.lowercase() ?: "run removed", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                    run?.totalCostUsd?.let { Text("$${formatDecimal(it, 3)}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp) }
                    Text("Open", color = Cyan, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
        }
        val pairActive = when (task.kind) {
            ProjectTaskKind.Build -> listOfNotNull(task.linkedReviewTaskId, task.linkedVerificationTaskId)
                .any { id -> workflow.tasks.firstOrNull { it.id == id }?.isActive == true }
            ProjectTaskKind.Review, ProjectTaskKind.Verification -> {
                val build = workflow.tasks.firstOrNull { it.id == task.linkedBuildTaskId }
                build?.isActive == true || listOfNotNull(build?.linkedReviewTaskId, build?.linkedVerificationTaskId)
                    .any { id -> workflow.tasks.firstOrNull { it.id == id }?.isActive == true }
            }
            ProjectTaskKind.Spec -> workflow.tasks.filter { it.linkedSpecTaskId == task.id }.any { child ->
                child.isActive || listOfNotNull(child.linkedReviewTaskId, child.linkedVerificationTaskId)
                    .any { id -> workflow.tasks.firstOrNull { it.id == id }?.isActive == true }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(task) }, enabled = !task.isActive && !pairActive) { Text("Edit") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { onDelete(task) }, enabled = !task.isActive && !pairActive) { Text("Delete", color = Red) }
        }
    }
}

@Composable
private fun SpecDetail(task: ProjectTask, onNewBuildFromPlan: (ProjectPlanSnapshot) -> Unit, onRun: () -> Unit) {
    var selectedVersion by remember(task.id, task.planVersions.size) { mutableStateOf(task.planVersions.lastOrNull()?.version) }
    val version = task.planVersions.firstOrNull { it.version == selectedVersion } ?: task.planVersions.lastOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRun, enabled = !task.isActive, colors = primaryButtonColors()) { Text(if (task.planVersions.isEmpty()) "Run spec" else "Revise spec") }
        if (task.grillMeEnabled) StatusTag("grill-me", Rust)
        Spacer(Modifier.weight(1f))
        version?.let { plan ->
            OutlinedButton(onClick = {
                onNewBuildFromPlan(ProjectPlanSnapshot(plan.text, task.id, plan.version, "${task.title} · v${plan.version}"))
            }) { Text("New build from this plan") }
        }
    }
    if (task.planVersions.isEmpty()) {
        WorkflowNotice("No implementation plan yet. Spec runs are always fresh, read-only planning sessions.", Cyan)
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            task.planVersions.sortedByDescending { it.version }.forEach { plan ->
                FilterPill("v${plan.version}", selectedVersion == plan.version, Rust) { selectedVersion = plan.version }
            }
        }
        version?.let { DetailBlock("IMPLEMENTATION PLAN · V${it.version}", it.text, selectable = true) }
    }
}

@Composable
private fun BuildDetail(services: AndyServices, workflow: ProjectWorkflowState, build: ProjectTask, runs: List<AgentTask>) {
    val scope = rememberCoroutineScope()
    val review = workflow.tasks.firstOrNull { it.id == build.linkedReviewTaskId }
    val verification = workflow.tasks.firstOrNull { it.id == build.linkedVerificationTaskId }
    val runIds = (build.attempts + review?.attempts.orEmpty() + verification?.attempts.orEmpty()).map { it.runId }.toSet()
    val cost = runs.filter { it.id in runIds }.sumOf { it.totalCostUsd ?: 0.0 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            build.isActive || review?.isActive == true || verification?.isActive == true -> {
                OutlinedButton(onClick = { services.projectWorkflows.pauseBuildPair(build.id) }) { Text("Pause after current") }
                OutlinedButton(onClick = { services.projectWorkflows.stopBuildPair(build.id) }) { Text("Stop current", color = Red) }
            }
            build.state == ProjectTaskState.Draft -> Button(onClick = { scope.launch { services.projectWorkflows.startBuildPair(build.id) } }, colors = primaryButtonColors()) { Text("Start pair") }
            build.state != ProjectTaskState.Completed -> Button(onClick = { scope.launch { services.projectWorkflows.resumeBuildPair(build.id) } }, colors = primaryButtonColors()) { Text("Resume pair") }
        }
        Spacer(Modifier.weight(1f))
        Text(
            build.maxBudgetUsd?.let { "$${formatDecimal(cost, 3)} / $${formatDecimal(it, 2)} reported" } ?: "$${formatDecimal(cost, 3)} reported",
            color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp,
        )
    }
    DetailBlock("FROZEN PLAN · ${build.planSnapshot?.sourceLabel.orEmpty()}", build.planSnapshot?.text.orEmpty(), selectable = true)
    if (build.buildNotes.isNotBlank()) DetailBlock("BUILD NOTES", build.buildNotes)
    if (build.reviewEnabled) {
        val failureCount = review?.reviewVerdicts?.count {
            it.status == ProjectReviewStatus.ChangesRequested && it.reviewGeneration == build.reviewGeneration
        } ?: 0
        DetailBlock(
            "REVIEW GATE · GENERATION ${build.reviewGeneration}",
            buildString {
                append("Enabled · ").append(failureCount).append('/').append(build.maxReviewFailures).append(" blocking verdicts")
                build.reviewInstructions.takeIf { it.isNotBlank() }?.let { append("\n\nCustom instructions\n").append(it) }
            },
        )
    } else if (review != null) {
        DetailBlock("REVIEW GATE", "Disabled · prior review history is retained")
    }
    DetailBlock("VERIFICATION CRITERIA", build.verificationInstructions)
    build.workspacePath?.let { DetailBlock("WORKSPACE", listOfNotNull(it, build.branchName).joinToString("\n")) }
}

@Composable
private fun ReviewDetail(task: ProjectTask, runs: List<AgentTask>) {
    DetailBlock(
        "STANDARD RUBRIC",
        "Correctness · plan alignment · maintainability · security · scope",
    )
    if (task.reviewInstructions.isNotBlank()) DetailBlock("CUSTOM REVIEW INSTRUCTIONS", task.reviewInstructions)
    if (task.state == ProjectTaskState.Disabled) WorkflowNotice("Review is disabled. Prior findings and transcripts remain available for audit.", TextSecondary)
    val latestRun = task.attempts.maxByOrNull { it.createdAtMillis }?.runId?.let { runId -> runs.firstOrNull { it.id == runId } }
    latestRun?.completedChanges?.summary?.files?.takeIf { it.isNotEmpty() }?.let { files ->
        DetailBlock("REVIEW WORKSPACE CHANGES", files.joinToString("\n") { "${it.path} (+${it.additions} -${it.deletions})" })
    }
    if (task.reviewVerdicts.isEmpty()) {
        WorkflowNotice("No structured review verdict yet.", AndyColors.Blue)
    } else {
        task.reviewVerdicts.sortedByDescending { it.createdAtMillis }.forEachIndexed { index, verdict ->
            WorkflowNotice(
                "${if (index == 0) "Latest · " else ""}${reviewStatusLabel(verdict.status)} · generation ${verdict.reviewGeneration}\n${verdict.summary}",
                if (verdict.status == ProjectReviewStatus.Approved) Green else Red,
            )
            ProjectReviewFindingSeverity.entries.forEach { severity ->
                verdict.findings.filter { it.severity == severity }.takeIf { it.isNotEmpty() }?.let { findings ->
                    DetailBlock(
                        severity.name.uppercase(),
                        findings.joinToString("\n\n") { finding ->
                            buildString {
                                append("• ").append(finding.title).append(" — ").append(finding.details)
                                finding.file?.let { file ->
                                    append("\n  ").append(file)
                                    finding.line?.let { append(':').append(it) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationDetail(task: ProjectTask) {
    DetailBlock("VERIFICATION INSTRUCTIONS", task.verificationInstructions)
    task.verdicts.lastOrNull()?.let { verdict ->
        WorkflowNotice(verdict.summary, if (verdict.status == ProjectVerificationStatus.Passed) Green else Red)
        if (verdict.evidence.isNotEmpty()) DetailBlock("EVIDENCE", verdict.evidence.joinToString("\n") { "• $it" })
        if (verdict.failures.isNotEmpty()) DetailBlock("FAILURES", verdict.failures.joinToString("\n") { "• $it" })
    } ?: WorkflowNotice("No structured verifier verdict yet.", Cyan)
}

@Composable
private fun DetailBlock(label: String, value: String, selectable: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        if (selectable) SelectionContainer { Text(value, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 17.sp) }
        else Text(value, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun WorkflowNotice(text: String, color: Color) {
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.09f), RoundedCornerShape(AndyRadius.R3)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.R3)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).height(28.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text, color = TextPrimary, fontFamily = MonoFont, fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ProjectScratchpadEditor(
    services: AndyServices,
    projectId: String,
    persistedText: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var text by remember(projectId) { mutableStateOf(persistedText) }
    var focused by remember(projectId) { mutableStateOf(false) }
    var previewing by remember(projectId) { mutableStateOf(persistedText.isNotBlank()) }
    LaunchedEffect(projectId, persistedText) {
        if (!focused && text != persistedText) text = persistedText
    }
    LaunchedEffect(projectId, text) {
        if (text == persistedText) return@LaunchedEffect
        delay(500)
        services.projectWorkflows.updateScratchpad(projectId, text)
    }
    DisposableEffect(projectId) {
        onDispose {
            if (text != persistedText) scope.launch { services.projectWorkflows.updateScratchpad(projectId, text) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Project scratchpad", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterPill("Edit", !previewing, Cyan) { previewing = false }
                    FilterPill("Preview", previewing, Rust) {
                        previewing = true
                        focused = false
                        if (text != persistedText) scope.launch { services.projectWorkflows.updateScratchpad(projectId, text) }
                    }
                }
            }
            Text("Loose context, constraints, and reminders. Tasks snapshot this only when enabled.", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        }
        if (previewing) {
            MarkdownPreview(text = text, modifier = Modifier.fillMaxSize())
        } else {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                minLines = 18,
                modifier = Modifier.fillMaxSize().onFocusChanged { state ->
                    val wasFocused = focused
                    focused = state.isFocused
                    if (wasFocused && !state.isFocused && text != persistedText) {
                        scope.launch { services.projectWorkflows.updateScratchpad(projectId, text) }
                    }
                },
                placeholder = { Text("Paste notes, constraints, commands, or half-formed thinking here…", color = TextSecondary, fontFamily = MonoFont) },
            )
        }
    }
}

@Composable
internal fun SpecTaskDialog(
    services: AndyServices,
    project: ActionProject,
    workflow: ProjectWorkflowState,
    existing: ProjectTask?,
    cliStatuses: List<AgentCliStatus>,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initialProfile = existing?.profile ?: workflow.profiles[ProjectTaskKind.Spec] ?: ProjectAgentProfile()
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var brief by remember(existing?.id) { mutableStateOf(existing?.instructions.orEmpty()) }
    var profile by remember(existing?.id) { mutableStateOf(initialProfile) }
    var includeScratchpad by remember(existing?.id) { mutableStateOf(existing?.includeScratchpad ?: false) }
    val installedSkills by services.agentRuns.skills(profile.agent, project.contextDir).collectAsState()
    val grillAvailable = installedSkills.any { it.name == "grill-me" }
    var grillMe by remember(existing?.id, grillAvailable) { mutableStateOf(existing?.grillMeEnabled == true && grillAvailable) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (existing == null) "New spec" else "Edit spec", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(760.dp).heightIn(max = 690.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth())
                LabeledField("Brief", brief, { brief = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 170.dp)
                ProjectAgentProfileEditor("SPEC PROFILE", profile, { profile = it }, cliStatuses, ProjectTaskKind.Spec)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Include scratchpad", includeScratchpad, Cyan) { includeScratchpad = !includeScratchpad }
                    if (grillAvailable) FilterPill("Use grill-me", grillMe, Rust) { grillMe = !grillMe }
                    else Text("grill-me not installed for ${profile.agent.label}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                WorkflowNotice("Plan mode and read-only safety are locked for every Spec run.", Green)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val id = services.projectWorkflows.saveSpec(ProjectSpecDraft(project.id, title, brief, profile, includeScratchpad, grillMe, existing?.id))
                    onSaved(id)
                }
            }, enabled = title.isNotBlank() && brief.isNotBlank(), colors = primaryButtonColors()) { Text("Save draft") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun BuildPairDialog(
    services: AndyServices,
    project: ActionProject,
    workflow: ProjectWorkflowState,
    seed: BuildEditorSeed,
    cliStatuses: List<AgentCliStatus>,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing = seed.buildTaskId?.let { id -> workflow.tasks.firstOrNull { it.id == id } }
    val linkedReview = existing?.linkedReviewTaskId?.let { id -> workflow.tasks.firstOrNull { it.id == id } }
    val linkedVerify = existing?.linkedVerificationTaskId?.let { id -> workflow.tasks.firstOrNull { it.id == id } }
    val availablePlans = workflow.tasks.filter { it.kind == ProjectTaskKind.Spec }.flatMap { spec ->
        spec.planVersions.map { version -> version.toSnapshot(spec) }
    }.sortedByDescending { it.sourceVersion }
    val initialPlan = seed.plan ?: existing?.planSnapshot ?: availablePlans.firstOrNull() ?: ProjectPlanSnapshot("")
    var title by remember(existing?.id, seed.plan) { mutableStateOf(existing?.title.orEmpty()) }
    var plan by remember(existing?.id, seed.plan) { mutableStateOf(initialPlan) }
    var externalPlan by remember(existing?.id, seed.plan) { mutableStateOf(initialPlan.sourceSpecTaskId == null) }
    var buildNotes by remember(existing?.id) { mutableStateOf(existing?.buildNotes.orEmpty()) }
    var verificationInstructions by remember(existing?.id) { mutableStateOf(existing?.verificationInstructions.orEmpty()) }
    var buildProfile by remember(existing?.id) { mutableStateOf(existing?.profile ?: workflow.profiles[ProjectTaskKind.Build] ?: ProjectAgentProfile()) }
    var reviewEnabled by remember(existing?.id) { mutableStateOf(existing?.reviewEnabled ?: false) }
    var reviewInstructions by remember(existing?.id) { mutableStateOf(existing?.reviewInstructions.orEmpty()) }
    var reviewProfile by remember(existing?.id) { mutableStateOf(linkedReview?.profile ?: workflow.profiles[ProjectTaskKind.Review] ?: ProjectAgentProfile()) }
    var verifyProfile by remember(existing?.id) { mutableStateOf(linkedVerify?.profile ?: workflow.profiles[ProjectTaskKind.Verification] ?: ProjectAgentProfile()) }
    var includeBuildScratchpad by remember(existing?.id) { mutableStateOf(existing?.includeScratchpad ?: false) }
    var includeReviewScratchpad by remember(existing?.id) { mutableStateOf(linkedReview?.includeScratchpad ?: false) }
    var includeVerifyScratchpad by remember(existing?.id) { mutableStateOf(linkedVerify?.includeScratchpad ?: false) }
    var budgetText by remember(existing?.id) { mutableStateOf(existing?.maxBudgetUsd?.toString().orEmpty()) }
    var planMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (existing == null) "New build + verification" else "Edit build + verification", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(820.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth())
                if (existing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterPill("Spec plan", !externalPlan, Cyan) { if (availablePlans.isNotEmpty()) { externalPlan = false; plan = availablePlans.first() } }
                        FilterPill("Pasted plan", externalPlan, Rust) { externalPlan = true; plan = ProjectPlanSnapshot(plan.text.takeIf { plan.sourceSpecTaskId == null }.orEmpty()) }
                        if (!externalPlan) Box {
                            OutlinedButton(onClick = { planMenu = true }) { Text(plan.sourceLabel) }
                            DropdownMenu(expanded = planMenu, onDismissRequest = { planMenu = false }, containerColor = PanelSoft) {
                                availablePlans.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.sourceLabel, color = TextPrimary) }, onClick = { plan = candidate; planMenu = false }) }
                            }
                        }
                    }
                    if (externalPlan) LabeledField("Implementation plan", plan.text, { plan = ProjectPlanSnapshot(it) }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 190.dp)
                    else DetailBlock("FROZEN WHEN SAVED · ${plan.sourceLabel}", plan.text, selectable = true)
                } else {
                    DetailBlock("FROZEN PLAN · ${plan.sourceLabel}", plan.text, selectable = true)
                }
                LabeledField("Build notes (optional)", buildNotes, { buildNotes = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 90.dp)
                LabeledField("Verification instructions", verificationInstructions, { verificationInstructions = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 130.dp)
                LabeledField("Reported-cost guardrail in USD (optional)", budgetText, { budgetText = it.filter { char -> char.isDigit() || char == '.' } }, Modifier.fillMaxWidth())
                ProjectAgentProfileEditor("BUILD PROFILE", buildProfile, { buildProfile = it }, cliStatuses, ProjectTaskKind.Build)
                FilterPill("Build gets scratchpad snapshot", includeBuildScratchpad, Cyan) { includeBuildScratchpad = !includeBuildScratchpad }
                FilterPill("Run review before verification", reviewEnabled, AndyColors.Blue) { reviewEnabled = !reviewEnabled }
                AnimatedVisibility(reviewEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledField("Custom review instructions (optional)", reviewInstructions, { reviewInstructions = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 100.dp)
                        ProjectAgentProfileEditor("REVIEW PROFILE · BUILD WORKSPACE INHERITED", reviewProfile, { reviewProfile = it }, cliStatuses, ProjectTaskKind.Review)
                        FilterPill("Reviewer gets scratchpad snapshot", includeReviewScratchpad, AndyColors.Blue) { includeReviewScratchpad = !includeReviewScratchpad }
                    }
                }
                ProjectAgentProfileEditor("VERIFICATION PROFILE", verifyProfile, { verifyProfile = it }, cliStatuses, ProjectTaskKind.Verification)
                FilterPill("Verifier gets scratchpad snapshot", includeVerifyScratchpad, Cyan) { includeVerifyScratchpad = !includeVerifyScratchpad }
                WorkflowNotice("Saving never launches a run. Review changes pause existing workflows until you explicitly resume.", Green)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val id = services.projectWorkflows.saveBuildPair(
                        ProjectBuildPairDraft(
                            projectId = project.id,
                            title = title,
                            plan = plan,
                            buildNotes = buildNotes,
                            verificationInstructions = verificationInstructions,
                            buildProfile = buildProfile,
                            verificationProfile = verifyProfile,
                            includeScratchpadInBuild = includeBuildScratchpad,
                            includeScratchpadInVerification = includeVerifyScratchpad,
                            maxBudgetUsd = budgetText.toDoubleOrNull(),
                            buildTaskId = existing?.id,
                            reviewEnabled = reviewEnabled,
                            reviewInstructions = reviewInstructions,
                            reviewProfile = reviewProfile,
                            includeScratchpadInReview = includeReviewScratchpad,
                        ),
                    )
                    onSaved(id)
                }
            }, enabled = title.isNotBlank() && plan.text.isNotBlank() && verificationInstructions.isNotBlank(), colors = primaryButtonColors()) { Text("Save pair") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ProjectProfilesDialog(
    services: AndyServices,
    workflow: ProjectWorkflowState,
    cliStatuses: List<AgentCliStatus>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var spec by remember(workflow.projectId) { mutableStateOf(workflow.profiles[ProjectTaskKind.Spec] ?: ProjectAgentProfile()) }
    var build by remember(workflow.projectId) { mutableStateOf(workflow.profiles[ProjectTaskKind.Build] ?: ProjectAgentProfile()) }
    var review by remember(workflow.projectId) { mutableStateOf(workflow.profiles[ProjectTaskKind.Review] ?: ProjectAgentProfile()) }
    var verify by remember(workflow.projectId) { mutableStateOf(workflow.profiles[ProjectTaskKind.Verification] ?: ProjectAgentProfile()) }
    var selectedRole by remember(workflow.projectId) { mutableStateOf(ProjectTaskKind.Spec) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Project role profiles", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(820.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectTaskKind.entries.forEach { role ->
                        FilterPill(role.label.replaceFirstChar { it.uppercase() }, selectedRole == role, taskKindAccent(role)) {
                            selectedRole = role
                        }
                    }
                }
                when (selectedRole) {
                    ProjectTaskKind.Spec -> ProjectAgentProfileEditor("SPEC · PLAN + READ ONLY LOCKED", spec, { spec = it }, cliStatuses, ProjectTaskKind.Spec)
                    ProjectTaskKind.Build -> ProjectAgentProfileEditor("BUILD", build, { build = it }, cliStatuses, ProjectTaskKind.Build)
                    ProjectTaskKind.Review -> ProjectAgentProfileEditor("REVIEW · BUILD WORKSPACE INHERITED", review, { review = it }, cliStatuses, ProjectTaskKind.Review)
                    ProjectTaskKind.Verification -> ProjectAgentProfileEditor("VERIFY · BUILD WORKSPACE INHERITED", verify, { verify = it }, cliStatuses, ProjectTaskKind.Verification)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    services.projectWorkflows.updateProfile(workflow.projectId, ProjectTaskKind.Spec, spec)
                    services.projectWorkflows.updateProfile(workflow.projectId, ProjectTaskKind.Build, build)
                    services.projectWorkflows.updateProfile(workflow.projectId, ProjectTaskKind.Review, review)
                    services.projectWorkflows.updateProfile(workflow.projectId, ProjectTaskKind.Verification, verify)
                    onDismiss()
                }
            }, colors = primaryButtonColors()) { Text("Save profiles") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ProjectAgentProfileEditor(
    label: String,
    profile: ProjectAgentProfile,
    onChange: (ProjectAgentProfile) -> Unit,
    cliStatuses: List<AgentCliStatus>,
    role: ProjectTaskKind,
) {
    Column(Modifier.fillMaxWidth().background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        AgentProviderModelProfileControls(
            profile = profile,
            onChange = onChange,
            cliStatuses = cliStatuses,
            showUnavailableAsPills = true,
            showProviderIcons = false,
            showModelControls = false,
            wrapOptions = true,
        )
        ProjectProfileSection(number = "01", title = "Model") {
            AgentProviderModelProfileControls(
                profile = profile,
                onChange = onChange,
                cliStatuses = cliStatuses,
                showProviderControls = false,
                showUnavailableAsPills = true,
                showProviderIcons = false,
                showModelLabel = false,
                wrapOptions = true,
            )
        }
        if (role != ProjectTaskKind.Spec) {
            ProjectProfileSection(number = "02", title = "Access") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AgentAutonomy.entries.forEach { autonomy -> FilterPill(autonomy.label, profile.autonomy == autonomy, Cyan) { onChange(profile.copy(autonomy = autonomy)) } }
                    AgentSandboxMode.entries.forEach { mode ->
                        FilterPill(mode.labelFor(profile.agent), (profile.sandboxMode ?: profile.autonomy.defaultSandboxMode()) == mode, if (mode == AgentSandboxMode.None) Rust else Cyan) { onChange(profile.copy(sandboxMode = mode)) }
                    }
                }
            }
            ProjectProfileSection(number = "03", title = "Workspace") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (role == ProjectTaskKind.Build) FilterPill("isolated worktree", profile.useWorktree, Rust) { onChange(profile.copy(useWorktree = !profile.useWorktree)) }
                    FilterPill("Andy MCP", profile.attachAndyMcp, Cyan) { onChange(profile.copy(attachAndyMcp = !profile.attachAndyMcp)) }
                }
            }
            Text("${profile.agent.sandboxControlLabel()}: ${(profile.sandboxMode ?: profile.autonomy.defaultSandboxMode()).labelFor(profile.agent)}", color = TextSecondary, fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ProjectProfileSection(
    number: String,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(number, color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(title.uppercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = Border.copy(alpha = 0.7f))
        }
        content()
    }
}

private fun ProjectPlanVersion.toSnapshot(spec: ProjectTask) = ProjectPlanSnapshot(
    text = text,
    sourceSpecTaskId = spec.id,
    sourceVersion = version,
    sourceLabel = "${spec.title} · v$version",
)

private fun profileSummary(profile: ProjectAgentProfile): String = buildString {
    append(profile.agent.label)
    profile.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    profile.reasoningEffort?.let { append(" · ").append(it.label) }
    if (profile.fastMode) append(" · fast")
}

private fun taskAccent(task: ProjectTask): Color = when (task.kind) {
    ProjectTaskKind.Spec -> Rust
    ProjectTaskKind.Build -> Cyan
    ProjectTaskKind.Review -> when (task.reviewVerdicts.lastOrNull()?.status) {
        ProjectReviewStatus.ChangesRequested -> Red
        else -> AndyColors.Blue
    }
    ProjectTaskKind.Verification -> if (task.verdicts.lastOrNull()?.status == ProjectVerificationStatus.Failed) Red else Green
}

private fun taskKindAccent(kind: ProjectTaskKind): Color = when (kind) {
    ProjectTaskKind.Spec -> Rust
    ProjectTaskKind.Build -> Cyan
    ProjectTaskKind.Review -> AndyColors.Blue
    ProjectTaskKind.Verification -> Green
}

private fun reviewStatusLabel(status: ProjectReviewStatus): String = when (status) {
    ProjectReviewStatus.Approved -> "approved"
    ProjectReviewStatus.ChangesRequested -> "changes requested"
}

private fun taskStateLabel(state: ProjectTaskState): String = when (state) {
    ProjectTaskState.NeedsAttention -> "attention"
    else -> state.name.lowercase()
}

private fun taskStateColor(state: ProjectTaskState): Color = when (state) {
    ProjectTaskState.Completed -> Green
    ProjectTaskState.Running -> Cyan
    ProjectTaskState.Queued, ProjectTaskState.Waiting -> Rust
    ProjectTaskState.NeedsAttention, ProjectTaskState.Failed -> Red
    else -> TextSecondary
}
