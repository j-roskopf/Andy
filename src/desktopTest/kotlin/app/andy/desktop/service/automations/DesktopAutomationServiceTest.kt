package app.andy.desktop.service.automations

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.AgentKind
import app.andy.model.AgentSkill
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AutomationDraft
import app.andy.model.AutomationLaunchSnapshot
import app.andy.model.AutomationMode
import app.andy.model.AutomationNotify
import app.andy.model.AutomationSchedule
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAutomationServiceTest {
    @Test
    fun createStaysPausedUntilResume() = runBlocking {
        withService { service, _, _ ->
            val created = service.create(sampleDraft(), arm = false)
            assertTrue(created.paused)
            assertNull(created.nextRunAtMillis)
            val armed = service.resume(created.id).let { service.automations.value.single() }
            assertEquals(false, armed.paused)
            assertNotNull(armed.nextRunAtMillis)
        }
    }

    @Test
    fun runNowStandaloneStartsChatWithAutomationMetadata() = runBlocking {
        withService { service, fake, _ ->
            val created = service.create(sampleDraft(title = "Nightly", prompt = "triage crashes"), arm = false)
            val ran = service.runNow(created.id)
            val start = fake.startCalls.single()
            assertEquals("triage crashes", start.prompt)
            assertEquals(true, start.attachAndyMcp)
            assertEquals(created.id, start.automationId)
            assertEquals(false, start.automationNotifyFailedOnly)
            assertEquals(1, ran.fireCount)
            assertEquals("done", ran.runs.single().outcome)
            assertEquals(fake.tasks.value.single().id, ran.lastTaskId)
        }
    }

    @Test
    fun failedOnlyNotifyFlagIsPassedToLaunch() = runBlocking {
        withService { service, fake, _ ->
            val created = service.create(
                sampleDraft(notify = AutomationNotify.FailedOnly),
                arm = false,
            )
            service.runNow(created.id)
            assertEquals(true, fake.startCalls.single().automationNotifyFailedOnly)
        }
    }

    @Test
    fun skipsWhenTargetChatIsStillWorking() = runBlocking {
        withService { service, fake, _ ->
            val created = service.create(sampleDraft(), arm = false)
            val first = service.runNow(created.id)
            fake.markWorking(first.lastTaskId!!)
            val skipped = service.runNow(created.id)
            assertEquals("skipped", skipped.runs.last().outcome)
            assertTrue(skipped.runs.last().detail.orEmpty().contains("still working"))
            assertEquals(1, fake.startCalls.size)
        }
    }

    @Test
    fun dedicatedReusesBoundChatOnLaterRuns() = runBlocking {
        withService { service, fake, _ ->
            val created = service.create(sampleDraft(mode = AutomationMode.Dedicated), arm = false)
            service.runNow(created.id)
            val boundId = service.automations.value.single().boundTaskId
            assertEquals(fake.tasks.value.single().id, boundId)
            service.runNow(created.id)
            assertEquals(1, fake.startCalls.size)
            assertEquals(listOf(boundId to "look"), fake.resumeCalls)
        }
    }

    @Test
    fun heartbeatMissingTargetPauses() = runBlocking {
        withService { service, _, _ ->
            val created = service.create(
                sampleDraft(mode = AutomationMode.Heartbeat, heartbeatTaskId = "missing"),
                arm = false,
            )
            service.runNow(created.id)
            val latest = service.automations.value.single()
            assertTrue(latest.paused)
            assertEquals("Heartbeat target chat is gone", latest.pauseReason)
        }
    }

    @Test
    fun heartbeatResumesExistingChat() = runBlocking {
        withService { service, fake, _ ->
            fake.seed(
                AgentTask(
                    id = "beat",
                    title = "host",
                    prompt = "hi",
                    agent = AgentKind.Codex,
                    status = AgentStatus.Done,
                    statusConfident = true,
                    finishedAtMillis = 5,
                    createdAtMillis = 1,
                ),
            )
            val created = service.create(
                sampleDraft(mode = AutomationMode.Heartbeat, heartbeatTaskId = "beat"),
                arm = false,
            )
            service.runNow(created.id)
            assertEquals(emptyList(), fake.startCalls)
            assertEquals(listOf("beat" to "look"), fake.resumeCalls)
        }
    }

    @Test
    fun stopWhenYesPausesAfterEvaluatorTurn() = runBlocking {
        withService { service, fake, _ ->
            fake.evaluatorReply = "Looks merged\nANDY_STOP=YES"
            val created = service.create(sampleDraft(stopWhen = "the PR is merged"), arm = false)
            val ran = service.runNow(created.id)
            assertTrue(ran.paused)
            assertEquals("Stop condition met", ran.pauseReason)
            assertEquals("stop_yes", ran.runs.single().outcome)
            assertTrue(fake.resumeCalls.single().second.contains("the PR is merged"))
            assertEquals(true, fake.notifySuppress.last())
        }
    }

    @Test
    fun onceCompletesAndDoesNotReschedule() = runBlocking {
        withService { service, _, now ->
            val created = service.create(
                sampleDraft(schedule = AutomationSchedule.Once(now + 1_000)),
                arm = false,
            )
            val ran = service.runNow(created.id)
            assertTrue(ran.paused)
            assertEquals("Completed one-time run", ran.pauseReason)
            assertNull(ran.nextRunAtMillis)
        }
    }

    @Test
    fun standaloneCleansOwnedWorktreeWhenAsked() = runBlocking {
        withService { service, fake, _ ->
            val created = service.create(
                sampleDraft(useWorktree = true, cleanupWorktree = true),
                arm = false,
            )
            service.runNow(created.id)
            assertEquals(listOf(fake.tasks.value.single().id), fake.cleanedWorktrees)
        }
    }

    @Test
    fun schedulerFiresDueAutomationAndIgnoresIdlePausedOnes() = runBlocking {
        val dir = File.createTempFile("andy-auto-sched", null).also {
            it.delete()
            it.mkdirs()
        }
        val store = DesktopAgentTaskStore(File(dir, "agents.db"))
        val now = 50_000_000L
        store.saveAutomation(
            app.andy.model.Automation(
                id = "due-1",
                projectId = "garden",
                title = "Due",
                prompt = "look",
                schedule = AutomationSchedule.Daily,
                timeZone = "UTC",
                launch = AutomationLaunchSnapshot(agent = AgentKind.Codex.name),
                paused = false,
                createdAtMillis = 1,
                updatedAtMillis = 1,
                nextRunAtMillis = now,
            ),
        )
        val fake = FakeAutomationAgentRuns()
        val service = DesktopAutomationService(
            store = store,
            agentRuns = fake,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            startScheduler = true,
            nowMillis = { now },
        )
        try {
            withTimeout(2_000) {
                while (fake.startCalls.isEmpty()) delay(10)
            }
            assertEquals("look", fake.startCalls.single().prompt)
            delay(120)
            assertEquals(1, fake.startCalls.size)
        } finally {
            service.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun idleSchedulerDoesNotStartWorkWhenNothingIsArmed() = runBlocking {
        withService(startScheduler = true) { _, fake, _ ->
            delay(150)
            assertEquals(emptyList(), fake.startCalls)
        }
    }

    private suspend fun withService(
        startScheduler: Boolean = false,
        block: suspend (DesktopAutomationService, FakeAutomationAgentRuns, Long) -> Unit,
    ) {
        val dir = File.createTempFile("andy-auto-svc", null).also {
            it.delete()
            it.mkdirs()
        }
        val now = 1_775_000_000_000L
        val fake = FakeAutomationAgentRuns()
        val service = DesktopAutomationService(
            store = DesktopAgentTaskStore(File(dir, "agents.db")),
            agentRuns = fake,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            startScheduler = startScheduler,
            nowMillis = { now },
        )
        try {
            block(service, fake, now)
        } finally {
            service.stop()
            dir.deleteRecursively()
        }
    }
}

private fun sampleDraft(
    title: String = "Triage",
    prompt: String = "look",
    mode: AutomationMode = AutomationMode.Standalone,
    schedule: AutomationSchedule = AutomationSchedule.Daily,
    stopWhen: String = "",
    notify: AutomationNotify = AutomationNotify.AllRuns,
    useWorktree: Boolean = false,
    cleanupWorktree: Boolean = false,
    heartbeatTaskId: String? = null,
) = AutomationDraft(
    projectId = "garden",
    title = title,
    prompt = prompt,
    mode = mode,
    schedule = schedule,
    timeZone = "UTC",
    stopWhen = stopWhen,
    notify = notify,
    useWorktree = useWorktree,
    cleanupWorktree = cleanupWorktree,
    heartbeatTaskId = heartbeatTaskId,
    launch = AutomationLaunchSnapshot(agent = AgentKind.Codex.name),
)

private class FakeAutomationAgentRuns : AgentRunService by UnavailableAgentRunService {
    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks
    val startCalls = mutableListOf<AgentTaskDraft>()
    val resumeCalls = mutableListOf<Pair<String, String>>()
    val cleanedWorktrees = mutableListOf<String>()
    val notifySuppress = mutableListOf<Boolean>()
    var evaluatorReply: String? = null
    private var seq = 0

    fun seed(task: AgentTask) {
        _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
    }

    fun markWorking(taskId: String) {
        _tasks.value = _tasks.value.map {
            if (it.id != taskId) it
            else it.copy(status = AgentStatus.Working, statusConfident = true, finishedAtMillis = null)
        }
    }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        startCalls += draft
        seq += 1
        val task = AgentTask(
            id = "task-$seq",
            title = draft.title,
            prompt = draft.prompt,
            agent = draft.agent,
            projectId = draft.projectId,
            status = AgentStatus.Done,
            statusConfident = true,
            finishedAtMillis = 10L + seq,
            createdAtMillis = 1,
            attachAndyMcp = draft.attachAndyMcp,
            automationId = draft.automationId,
            completedResultText = "work done",
        )
        _tasks.value = _tasks.value + task
        return task
    }

    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) {
        resumeCalls += taskId to followUp
        _tasks.value = _tasks.value.map { task ->
            if (task.id != taskId) task
            else task.copy(
                status = AgentStatus.Done,
                statusConfident = true,
                finishedAtMillis = (task.finishedAtMillis ?: 0L) + 1L,
                completedResultText = evaluatorReply ?: followUp,
            )
        }
    }

    override fun updateAutomationNotifySuppress(taskId: String, suppress: Boolean) {
        notifySuppress += suppress
    }

    override suspend fun cleanupOwnedWorktree(taskId: String) {
        cleanedWorktrees += taskId
    }
}
