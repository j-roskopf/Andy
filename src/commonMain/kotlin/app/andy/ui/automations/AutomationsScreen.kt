package app.andy.ui.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.formatDisplayDateTime
import app.andy.hostTimeZoneId
import app.andy.model.ActionProject
import app.andy.model.AgentKind
import app.andy.model.AgentAutonomy
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentReasoningEffort
import app.andy.model.Automation
import app.andy.model.AutomationDraft
import app.andy.model.AutomationFailurePolicy
import app.andy.model.AutomationIntervalUnit
import app.andy.model.AutomationLaunchSnapshot
import app.andy.model.AutomationMaxIterations
import app.andy.model.AutomationMode
import app.andy.model.AutomationNotify
import app.andy.model.AutomationSchedule
import app.andy.model.AutomationTemplates
import app.andy.model.automationTimeZoneLabel
import app.andy.model.automationTimeZonePickerOptions
import app.andy.model.cadenceLabel
import app.andy.model.clockToHour24
import app.andy.model.groupedByModelFamily
import app.andy.model.hour24ToClock
import app.andy.model.resolveAutomationTimeZoneId
import app.andy.service.AndyServices
import app.andy.ui.agents.AgentPillIcon
import app.andy.ui.components.AndyCheckbox
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.Button
import app.andy.ui.components.ComposerChip
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun AutomationsScreen(
    services: AndyServices,
    project: ActionProject,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val automations by services.automations.automations.collectAsState()
    val tasks by services.agentRuns.tasks.collectAsState()
    val defaults by services.agentRuns.providerDefaults.collectAsState()
    val lastUsed by services.agentRuns.lastUsedAgent.collectAsState()
    val providerModels by services.agentRuns.providerModels.collectAsState()
    val scope = rememberCoroutineScope()
    val projectAutomations = remember(automations, project.id) {
        automations.filter { it.projectId == project.id }.sortedByDescending { it.updatedAtMillis }
    }
    var editor by remember { mutableStateOf<Automation?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize().background(AndyColors.ContentBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AndySpace.Space6, vertical = AndySpace.Space5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Automations", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { creating = true; editor = null; error = null }) {
                    Text("New automation")
                }
            }
            AndyHorizontalDivider()
            if (projectAutomations.isEmpty() && !creating) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(AndySpace.Space7).widthIn(max = 420.dp),
                    ) {
                        Text("No automations yet", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Schedule a prompt to run on its own, or wake an existing thread on a loop.",
                            color = TextSecondary,
                            fontFamily = DisplayFont,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else if (!creating && editor == null) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AndySpace.Space6),
                ) {
                    projectAutomations.forEachIndexed { index, item ->
                        AutomationRow(
                            automation = item,
                            onOpen = { editor = item; creating = false },
                            onOpenChat = {
                                val chatId = item.lastTaskId ?: item.boundTaskId ?: item.heartbeatTaskId
                                if (chatId != null) onOpenChat(chatId)
                            },
                            onPause = { scope.launch { runCatching { services.automations.pause(item.id) } } },
                            onResume = { scope.launch { runCatching { services.automations.resume(item.id) } } },
                            onRun = { scope.launch { runCatching { services.automations.runNow(item.id) }.onFailure { error = it.message } } },
                            onDelete = { scope.launch { runCatching { services.automations.delete(item.id) } } },
                        )
                        if (index < projectAutomations.lastIndex) {
                            AndyHorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(AndySpace.Space6))
                }
            }
        }
        if (creating || editor != null) {
            AutomationEditor(
                project = project,
                existing = editor,
                chats = tasks.filter { it.projectId == project.id && !it.archived },
                defaultAgent = lastUsed ?: AgentKind.Codex,
                defaultModel = lastUsed?.let { defaults[it]?.model },
                defaultEffort = lastUsed?.let { defaults[it]?.reasoningEffort },
                defaultAutonomy = lastUsed?.let { defaults[it]?.autonomy } ?: AgentAutonomy.Standard,
                providerModels = providerModels,
                error = error,
                onDismiss = { creating = false; editor = null; error = null },
                onSave = { draft, arm ->
                    scope.launch {
                        runCatching {
                            if (editor == null) {
                                services.automations.create(draft, arm)
                            } else {
                                services.automations.update(editor!!.id, draft)
                            }
                        }.onSuccess {
                            creating = false
                            editor = null
                            error = null
                        }.onFailure { error = it.message }
                    }
                },
            )
        }
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    onOpen: () -> Unit,
    onOpenChat: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(automation.title, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (automation.paused) "Paused" else "Armed", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
        }
        Text(
            buildString {
                append(automation.cadenceLabel())
                append(" · ")
                append(automation.mode.label)
                automation.nextRunAtMillis?.takeIf { !automation.paused }?.let {
                    append(" · next ")
                    append(formatDisplayDateTime(it))
                }
            },
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        automation.pauseReason?.takeIf { automation.paused }?.let {
            Text(it, color = TextSecondary, fontFamily = DisplayFont, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
            if (automation.paused) {
                Text("Resume", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onResume))
            } else {
                Text("Pause", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onPause))
            }
            Text("Run now", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRun))
            Text("Open chat", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onOpenChat))
            Text("Delete", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDelete))
        }
    }
}

@Composable
private fun AutomationEditor(
    project: ActionProject,
    existing: Automation?,
    chats: List<app.andy.model.AgentTask>,
    defaultAgent: AgentKind,
    defaultModel: String?,
    defaultEffort: AgentReasoningEffort?,
    defaultAutonomy: AgentAutonomy,
    providerModels: Map<AgentKind, List<AgentModelOption>>,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (AutomationDraft, Boolean) -> Unit,
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var prompt by remember(existing?.id) { mutableStateOf(existing?.prompt.orEmpty()) }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: AutomationMode.Standalone) }
    var kind by remember(existing?.id) { mutableStateOf(existing?.schedule?.kind() ?: "daily") }
    var cron by remember(existing?.id) {
        mutableStateOf((existing?.schedule as? AutomationSchedule.Cron)?.expression ?: "0 9 * * *")
    }
    var intervalEvery by remember(existing?.id) {
        mutableStateOf((existing?.schedule as? AutomationSchedule.Interval)?.every?.toString() ?: "1")
    }
    var intervalUnit by remember(existing?.id) {
        mutableStateOf((existing?.schedule as? AutomationSchedule.Interval)?.unit ?: AutomationIntervalUnit.Hours)
    }
    var runHour by remember(existing?.id) { mutableStateOf(existing?.runHour ?: 9) }
    var runMinute by remember(existing?.id) { mutableStateOf(existing?.runMinute ?: 0) }
    var timeZone by remember(existing?.id) {
        mutableStateOf(
            resolveAutomationTimeZoneId(
                existing?.timeZone ?: hostTimeZoneId(),
                fallback = hostTimeZoneId(),
            ),
        )
    }
    val initialClock = hour24ToClock(existing?.runHour ?: 9)
    var hour12 by remember(existing?.id) { mutableStateOf(initialClock.first) }
    var isPm by remember(existing?.id) { mutableStateOf(initialClock.second) }
    var stopWhen by remember(existing?.id) { mutableStateOf(existing?.stopWhen.orEmpty()) }
    var failurePolicy by remember(existing?.id) { mutableStateOf(existing?.failurePolicy ?: AutomationFailurePolicy.StopAfter3) }
    var maxIterations by remember(existing?.id) { mutableStateOf(existing?.maxIterations ?: AutomationMaxIterations.Unlimited) }
    var notify by remember(existing?.id) { mutableStateOf(existing?.notify ?: AutomationNotify.AllRuns) }
    var useWorktree by remember(existing?.id) { mutableStateOf(existing?.useWorktree == true) }
    var cleanupWorktree by remember(existing?.id) { mutableStateOf(existing?.cleanupWorktree == true) }
    var heartbeatTaskId by remember(existing?.id) { mutableStateOf(existing?.heartbeatTaskId) }
    var agent by remember(existing?.id) {
        mutableStateOf(existing?.launch?.agentKind() ?: defaultAgent)
    }
    var model by remember(existing?.id) { mutableStateOf(existing?.launch?.model ?: defaultModel.orEmpty()) }
    var useCustomModel by remember(existing?.id) {
        val initial = existing?.launch?.model ?: defaultModel
        val selected = AgentModelCatalog.option(
            existing?.launch?.agentKind() ?: defaultAgent,
            initial,
            providerModels,
        )
        mutableStateOf(!initial.isNullOrBlank() && selected == null)
    }
    var effort by remember(existing?.id) { mutableStateOf(existing?.launch?.effort() ?: defaultEffort) }
    var autonomy by remember(existing?.id) { mutableStateOf(existing?.launch?.autonomyLevel() ?: defaultAutonomy) }
    var templateMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var scheduleMenu by remember { mutableStateOf(false) }
    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var autonomyMenu by remember { mutableStateOf(false) }
    var failureMenu by remember { mutableStateOf(false) }
    var iterationMenu by remember { mutableStateOf(false) }
    var notifyMenu by remember { mutableStateOf(false) }
    var heartbeatMenu by remember { mutableStateOf(false) }
    var hourMenu by remember { mutableStateOf(false) }
    var minuteMenu by remember { mutableStateOf(false) }
    var meridiemMenu by remember { mutableStateOf(false) }
    var zoneMenu by remember { mutableStateOf(false) }

    fun applyClock(nextHour12: Int, nextIsPm: Boolean) {
        hour12 = nextHour12
        isPm = nextIsPm
        runHour = clockToHour24(nextHour12, nextIsPm)
    }

    fun buildSchedule(): AutomationSchedule = when (kind) {
        "manual" -> AutomationSchedule.Manual
        "once" -> AutomationSchedule.Once(0L)
        "hourly" -> AutomationSchedule.Hourly
        "weekdays" -> AutomationSchedule.Weekdays
        "weekly" -> AutomationSchedule.Weekly(1)
        "custom" -> AutomationSchedule.Interval(
            every = intervalEvery.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            unit = intervalUnit,
            startAtMillis = app.andy.currentTimeMillis(),
        )
        "cron" -> AutomationSchedule.Cron(cron.trim())
        else -> AutomationSchedule.Daily
    }

    val modelOptions = AgentModelCatalog.options(agent, providerModels)
    val selectedModel = if (useCustomModel) {
        null
    } else {
        AgentModelCatalog.option(agent, model.takeIf { it.isNotBlank() }, providerModels)
    }
    LaunchedEffect(agent, model, providerModels) {
        val match = AgentModelCatalog.option(agent, model.takeIf { it.isNotBlank() }, providerModels)
        if (match != null) useCustomModel = false
    }

    Box(Modifier.fillMaxSize().background(AndyColors.ContentBg).padding(AndySpace.Space5)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .background(Panel, AndyShape.Sheet)
                .border(1.dp, Border, AndyShape.Sheet)
                .padding(AndySpace.Space6)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) "New automation" else "Edit automation", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { templateMenu = true }) { Text("Use template") }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        AutomationTemplates.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.title) },
                                onClick = {
                                    title = template.title
                                    prompt = template.prompt
                                    kind = template.schedule.kind()
                                    runHour = template.runHour
                                    runMinute = template.runMinute
                                    hour24ToClock(template.runHour).let { (nextHour12, nextIsPm) ->
                                        hour12 = nextHour12
                                        isPm = nextIsPm
                                    }
                                    templateMenu = false
                                },
                            )
                        }
                    }
                }
                Text("Close", color = TextSecondary, modifier = Modifier.padding(start = AndySpace.Space3).clickable(onClick = onDismiss), fontSize = 13.sp)
            }
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Automation title", color = TextSecondary) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Add prompt e.g. look for crashes in this project", color = TextSecondary) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                minLines = 6,
            )
            AutomationLaunchChips(
                agent = agent,
                model = model,
                selectedModel = selectedModel,
                modelOptions = modelOptions,
                customModel = useCustomModel,
                effort = effort,
                autonomy = autonomy,
                agentMenu = agentMenu,
                modelMenu = modelMenu,
                effortMenu = effortMenu,
                autonomyMenu = autonomyMenu,
                onAgentMenu = { agentMenu = it },
                onModelMenu = { modelMenu = it },
                onEffortMenu = { effortMenu = it },
                onAutonomyMenu = { autonomyMenu = it },
                onAgentChange = { agent = it },
                onModelChange = { next, custom ->
                    useCustomModel = custom
                    model = next
                },
                onEffortChange = { effort = it },
                onAutonomyChange = { autonomy = it },
            )
            if (useCustomModel) {
                TextField(
                    value = model,
                    onValueChange = { model = it },
                    placeholder = { Text("custom model or variant", color = TextSecondary) },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (mode != AutomationMode.Heartbeat) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                    AndyCheckbox(checked = useWorktree, onCheckedChange = { useWorktree = it })
                    Column {
                        Text("Use worktree", color = TextPrimary, fontSize = 13.sp)
                        Text(
                            if (mode == AutomationMode.Standalone) {
                                "Isolate each run. Cleanup deletes that worktree when the run finishes."
                            } else {
                                "Isolate the dedicated chat once. Cleanup runs when you delete the automation."
                            },
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                    AndyCheckbox(checked = cleanupWorktree, onCheckedChange = { cleanupWorktree = it }, enabled = useWorktree)
                    Text("Cleanup worktree after done", color = TextPrimary, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3), modifier = Modifier.fillMaxWidth()) {
                ChipMenu("Mode", mode.label, modeMenu, { modeMenu = it }) {
                    AutomationMode.entries.forEach {
                        DropdownMenuItem(text = { Text(it.label) }, onClick = { mode = it; modeMenu = false })
                    }
                }
                ChipMenu("Schedule", kind.replaceFirstChar { it.uppercase() }, scheduleMenu, { scheduleMenu = it }) {
                    listOf("manual", "once", "hourly", "daily", "weekdays", "weekly", "custom", "cron").forEach { option ->
                        DropdownMenuItem(text = { Text(option.replaceFirstChar { it.uppercase() }) }, onClick = { kind = option; scheduleMenu = false })
                    }
                }
            }
            if (kind == "cron") {
                TextField(value = cron, onValueChange = { cron = it }, label = { Text("Cron (5-field)") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (kind == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3), verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = intervalEvery, onValueChange = { intervalEvery = it }, label = { Text("Every") }, colors = fieldColors(), modifier = Modifier.weight(1f), singleLine = true)
                    ChipMenu("Unit", intervalUnit.label, false, {}) {}
                    AutomationIntervalUnit.entries.forEach { unit ->
                        Text(unit.label, color = if (unit == intervalUnit) TextPrimary else TextSecondary, modifier = Modifier.clickable { intervalUnit = unit }, fontSize = 12.sp)
                    }
                }
            }
            if (kind in setOf("hourly", "daily", "weekdays", "weekly", "once")) {
                val minuteChoices = ((0 until 60 step 5).toList() + runMinute).distinct().sorted()
                Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                    if (kind != "hourly") {
                        ChipMenu("Hour", hour12.toString(), hourMenu, { hourMenu = it }) {
                            (1..12).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.toString()) },
                                    onClick = { applyClock(option, isPm); hourMenu = false },
                                )
                            }
                        }
                        ChipMenu("Minute", runMinute.toString().padStart(2, '0'), minuteMenu, { minuteMenu = it }) {
                            minuteChoices.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.toString().padStart(2, '0')) },
                                    onClick = { runMinute = option; minuteMenu = false },
                                )
                            }
                        }
                        ChipMenu("AM/PM", if (isPm) "PM" else "AM", meridiemMenu, { meridiemMenu = it }) {
                            DropdownMenuItem(text = { Text("AM") }, onClick = { applyClock(hour12, false); meridiemMenu = false })
                            DropdownMenuItem(text = { Text("PM") }, onClick = { applyClock(hour12, true); meridiemMenu = false })
                        }
                    } else {
                        ChipMenu("Minute", runMinute.toString().padStart(2, '0'), minuteMenu, { minuteMenu = it }) {
                            minuteChoices.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.toString().padStart(2, '0')) },
                                    onClick = { runMinute = option; minuteMenu = false },
                                )
                            }
                        }
                    }
                    ChipMenu("Timezone", automationTimeZoneLabel(timeZone), zoneMenu, { zoneMenu = it }) {
                        automationTimeZonePickerOptions(timeZone).forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option.label}  ${option.id}") },
                                onClick = { timeZone = option.id; zoneMenu = false },
                            )
                        }
                    }
                }
                if (kind == "once") {
                    val meridiem = if (isPm) "PM" else "AM"
                    Text(
                        "Runs at the next $hour12:${runMinute.toString().padStart(2, '0')} $meridiem in ${automationTimeZoneLabel(timeZone)}. If that clock time has already passed today, it runs tomorrow.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            if (mode == AutomationMode.Heartbeat) {
                Box {
                    OutlinedButton(onClick = { heartbeatMenu = true }) {
                        Text(chats.firstOrNull { it.id == heartbeatTaskId }?.title ?: "Pick a chat")
                    }
                    DropdownMenu(expanded = heartbeatMenu, onDismissRequest = { heartbeatMenu = false }) {
                        chats.forEach { chat ->
                            DropdownMenuItem(text = { Text(chat.title) }, onClick = { heartbeatTaskId = chat.id; heartbeatMenu = false })
                        }
                    }
                }
            }
            TextField(value = stopWhen, onValueChange = { stopWhen = it }, label = { Text("Stop when") }, placeholder = { Text("PR is ready to merge") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                ChipMenu("On failure", failurePolicy.label, failureMenu, { failureMenu = it }) {
                    AutomationFailurePolicy.entries.forEach {
                        DropdownMenuItem(text = { Text(it.label) }, onClick = { failurePolicy = it; failureMenu = false })
                    }
                }
                ChipMenu("Max iterations", maxIterations.label, iterationMenu, { iterationMenu = it }) {
                    AutomationMaxIterations.entries.forEach {
                        DropdownMenuItem(text = { Text(it.label) }, onClick = { maxIterations = it; iterationMenu = false })
                    }
                }
                ChipMenu("Notify", notify.label, notifyMenu, { notifyMenu = it }) {
                    AutomationNotify.entries.forEach {
                        DropdownMenuItem(text = { Text(it.label) }, onClick = { notify = it; notifyMenu = false })
                    }
                }
            }
            error?.let { Text(it, color = AndyColors.Orange, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3), modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val draft = AutomationDraft(
                            projectId = project.id,
                            title = title,
                            prompt = prompt,
                            mode = mode,
                            schedule = buildSchedule(),
                            timeZone = timeZone,
                            runHour = runHour,
                            runMinute = runMinute,
                            stopWhen = stopWhen,
                            failurePolicy = failurePolicy,
                            maxIterations = maxIterations,
                            notify = notify,
                            useWorktree = useWorktree,
                            cleanupWorktree = cleanupWorktree,
                            heartbeatTaskId = heartbeatTaskId,
                            launch = AutomationLaunchSnapshot(
                                agent = agent.name,
                                model = model.takeIf { it.isNotBlank() },
                                reasoningEffort = effort?.name,
                                autonomy = autonomy.name,
                                directory = project.contextDir,
                            ),
                        )
                        onSave(draft, existing == null)
                    },
                    colors = primaryButtonColors(),
                    enabled = prompt.isNotBlank() && (mode != AutomationMode.Heartbeat || heartbeatTaskId != null),
                ) {
                    Text(if (existing == null) "Create" else "Save")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationLaunchChips(
    agent: AgentKind,
    model: String,
    selectedModel: AgentModelOption?,
    modelOptions: List<AgentModelOption>,
    customModel: Boolean,
    effort: AgentReasoningEffort?,
    autonomy: AgentAutonomy,
    agentMenu: Boolean,
    modelMenu: Boolean,
    effortMenu: Boolean,
    autonomyMenu: Boolean,
    onAgentMenu: (Boolean) -> Unit,
    onModelMenu: (Boolean) -> Unit,
    onEffortMenu: (Boolean) -> Unit,
    onAutonomyMenu: (Boolean) -> Unit,
    onAgentChange: (AgentKind) -> Unit,
    onModelChange: (String, Boolean) -> Unit,
    onEffortChange: (AgentReasoningEffort?) -> Unit,
    onAutonomyChange: (AgentAutonomy) -> Unit,
) {
    val effortChoices = selectedModel?.efforts?.takeIf { it.isNotEmpty() } ?: AgentReasoningEffort.entries
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Box {
            ComposerChip(
                text = agent.label,
                selected = true,
                onClick = { onAgentMenu(true) },
                leadingContent = { AgentPillIcon(agent) },
            )
            DropdownMenu(expanded = agentMenu, onDismissRequest = { onAgentMenu(false) }) {
                AgentKind.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AgentPillIcon(option)
                                Text(option.label, color = TextPrimary)
                            }
                        },
                        onClick = {
                            onAgentChange(option)
                            onAgentMenu(false)
                        },
                    )
                }
            }
        }
        Box {
            ComposerChip(
                text = when {
                    customModel -> "custom"
                    selectedModel != null -> selectedModel.label
                    else -> "Default model"
                },
                selected = model.isNotBlank() || customModel,
                onClick = { onModelMenu(true) },
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { onModelMenu(false) }) {
                DropdownMenuItem(
                    text = { Text("provider default", color = TextPrimary) },
                    onClick = {
                        onModelChange("", false)
                        onModelMenu(false)
                    },
                )
                if (agent == AgentKind.Cursor) {
                    modelOptions.groupedByModelFamily().forEach { (family, options) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    family.label.uppercase(),
                                    color = TextSecondary,
                                    fontFamily = MonoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label, color = TextPrimary) },
                                onClick = {
                                    onModelChange(option.id, false)
                                    onModelMenu(false)
                                },
                            )
                        }
                    }
                } else {
                    modelOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = TextPrimary) },
                            onClick = {
                                onModelChange(option.id, false)
                                onModelMenu(false)
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("custom", color = TextPrimary) },
                    onClick = {
                        onModelChange(if (customModel) model else "", true)
                        onModelMenu(false)
                    },
                )
            }
        }
        Box {
            ComposerChip(
                text = effort?.label ?: "Effort",
                selected = effort != null,
                onClick = { onEffortMenu(true) },
            )
            DropdownMenu(expanded = effortMenu, onDismissRequest = { onEffortMenu(false) }) {
                DropdownMenuItem(
                    text = { Text("provider default", color = TextPrimary) },
                    onClick = {
                        onEffortChange(null)
                        onEffortMenu(false)
                    },
                )
                effortChoices.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = TextPrimary) },
                        onClick = {
                            onEffortChange(option)
                            onEffortMenu(false)
                        },
                    )
                }
            }
        }
        Box {
            ComposerChip(
                text = autonomy.label,
                selected = true,
                onClick = { onAutonomyMenu(true) },
            )
            DropdownMenu(expanded = autonomyMenu, onDismissRequest = { onAutonomyMenu(false) }) {
                AgentAutonomy.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = TextPrimary) },
                        onClick = {
                            onAutonomyChange(option)
                            onAutonomyMenu(false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipMenu(
    label: String,
    value: String,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Box {
            OutlinedButton(onClick = { onExpanded(true) }) { Text(value) }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }, content = content)
        }
    }
}

private fun AutomationSchedule.kind(): String = when (this) {
    AutomationSchedule.Manual -> "manual"
    is AutomationSchedule.Once -> "once"
    AutomationSchedule.Hourly -> "hourly"
    AutomationSchedule.Daily -> "daily"
    AutomationSchedule.Weekdays -> "weekdays"
    is AutomationSchedule.Weekly -> "weekly"
    is AutomationSchedule.Interval -> "custom"
    is AutomationSchedule.Cron -> "cron"
}
