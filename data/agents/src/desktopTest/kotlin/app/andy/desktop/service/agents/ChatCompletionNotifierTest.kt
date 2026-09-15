package app.andy.desktop.service.agents

import app.andy.model.AgentAttachment
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentKind
import app.andy.model.AgentSkill
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ChatCompletionNotifierTest {
    @Test
    fun skipsParentWhenLeadStoppedByUser() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val lead = AgentTask(
            id = "lead",
            title = "Swarm lead",
            prompt = "",
            agent = AgentKind.Cursor,
            status = AgentStatus.Done,
            stoppedByUser = true,
            createdAtMillis = 1L,
        )
        val working = task("worker", AgentStatus.Working, parent = "lead", notify = true)
        fake.setTasks(listOf(lead, working))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })

        fake.setTasks(listOf(lead, working.copy(status = AgentStatus.Done)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun notifiesParentOnTerminalEdge() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val working = task("worker", AgentStatus.Working, parent = "lead", notify = true)
        fake.setTasks(listOf(working))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())

        fake.setTasks(listOf(working.copy(status = AgentStatus.Done)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertEquals(1, fake.followUps.size)
        assertEquals("lead", fake.followUps.single().first)
        assertTrue(fake.followUps.single().second.contains("worker"))
        assertTrue(fake.followUps.single().second.contains("Done"))
    }

    @Test
    fun firstSightOfAlreadyTerminalDoesNotNotify() {
        // App restart / store hydration: Done workers appear with no prior lastStatus entry.
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val done = task("worker", AgentStatus.Done, parent = "lead", notify = true)
        fake.setTasks(listOf(done))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun startAfterHydrationDoesNotReNotifyDoneWorkers() = runBlocking {
        val fake = FakeNotifyAgents()
        val workers = listOf(
            task("w1", AgentStatus.Done, parent = "lead", notify = true),
            task("w2", AgentStatus.Done, parent = "lead", notify = true),
        )
        fake.setTasks(workers)
        fake.tasksLoaded.complete(Unit)
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        notifier.start()
        delay(50)
        // Emit the same hydrated snapshot again (collector's first distinct value).
        fake.setTasks(workers.toList())
        delay(50)
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun dedupesFlappingTerminalEdge() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        var worker = task("worker", AgentStatus.Working, "lead", notify = true)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })

        worker = worker.copy(status = AgentStatus.Done)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        worker = worker.copy(status = AgentStatus.Working)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        worker = worker.copy(status = AgentStatus.Done)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertEquals(1, fake.followUps.size)
    }

    @Test
    fun noNotificationWithoutParent() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val worker = task("worker", AgentStatus.Working, parent = null, notify = true)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        fake.setTasks(listOf(worker.copy(status = AgentStatus.Done)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun noNotificationWhenFlagOff() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val worker = task("worker", AgentStatus.Working, parent = "lead", notify = false)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        fake.setTasks(listOf(worker.copy(status = AgentStatus.Error)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun blockedEdgeDoesNotNotifyParent() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val worker = task("worker", AgentStatus.Working, parent = "lead", notify = true)
        fake.setTasks(listOf(worker))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        fake.setTasks(listOf(worker.copy(status = AgentStatus.Blocked)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertTrue(fake.followUps.isEmpty())
    }

    @Test
    fun nullStatusDoesNotThrowAndStillNotifiesOnLaterEdge() {
        val fake = FakeNotifyAgents()
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val queued = task("worker", status = null, parent = "lead", notify = true)
        fake.setTasks(listOf(queued))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })

        val working = queued.copy(status = AgentStatus.Working)
        fake.setTasks(listOf(working))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })

        fake.setTasks(listOf(working.copy(status = AgentStatus.Done)))
        notifier.processSnapshot(fake.tasks.value.associateBy { it.id })
        assertEquals(1, fake.followUps.size)
    }

    @Test
    fun startSeedsNullStatusWithoutThrowing() = runBlocking {
        val fake = FakeNotifyAgents()
        fake.setTasks(listOf(task("worker", status = null, parent = "lead", notify = true)))
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        notifier.start()
        delay(50)
        assertTrue(fake.followUps.isEmpty())
    }

    private fun task(
        id: String,
        status: AgentStatus?,
        parent: String?,
        notify: Boolean,
    ) = AgentTask(
        id = id,
        title = "Subtask $id",
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        parentChatTaskId = parent,
        notifyParentOnCompletion = notify,
        worktreePath = "/tmp/wt",
        branchName = "swarm/$id",
        createdAtMillis = 1L,
    )
}

class ChatAwaitTest {
    @Test
    fun returnsImmediatelyWhenAlreadyTerminal() = runBlocking {
        val fake = FakeNotifyAgents(listOf(awaitTask("w1", AgentStatus.Done)))
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val result = notifier.awaitAny(listOf("w1"), ChatAwaitUntil.Terminal, timeout = 500.milliseconds)
        assertFalse(result.timedOut)
        assertEquals("w1", result.taskId)
        assertEquals(AgentStatus.Done, result.status)
    }

    @Test
    fun returnsOnBlockedUnderAnyButNotTerminal() = runBlocking {
        val fake = FakeNotifyAgents(listOf(awaitTask("w1", AgentStatus.Blocked)))
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val any = notifier.awaitAny(listOf("w1"), ChatAwaitUntil.Any, timeout = 200.milliseconds)
        assertEquals(AgentStatus.Blocked, any.status)
        assertFalse(any.timedOut)

        val terminal = notifier.awaitAny(listOf("w1"), ChatAwaitUntil.Terminal, timeout = 150.milliseconds)
        assertTrue(terminal.timedOut)
        assertNull(terminal.taskId)
    }

    @Test
    fun honoursTimeout() = runBlocking {
        val fake = FakeNotifyAgents(listOf(awaitTask("w1", AgentStatus.Working)))
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val result = notifier.awaitAny(listOf("w1"), ChatAwaitUntil.Any, timeout = 80.milliseconds)
        assertTrue(result.timedOut)
    }

    @Test
    fun returnsOnFirstTerminalWorker() = runBlocking {
        val fake = FakeNotifyAgents(
            listOf(
                awaitTask("w1", AgentStatus.Working),
                awaitTask("w2", AgentStatus.Working),
            ),
        )
        val notifier = ChatCompletionNotifier(fake, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val job = async {
            notifier.awaitAny(listOf("w1", "w2"), ChatAwaitUntil.Terminal, timeout = 2_000.milliseconds)
        }
        delay(50)
        fake.setTasks(
            listOf(
                awaitTask("w1", AgentStatus.Working),
                awaitTask("w2", AgentStatus.Done),
            ),
        )
        val result = job.await()
        assertEquals("w2", result.taskId)
        assertEquals(AgentStatus.Done, result.status)
        assertFalse(result.timedOut)
    }

    private fun awaitTask(id: String, status: AgentStatus) = AgentTask(
        id = id,
        title = id,
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        createdAtMillis = 1L,
    )
}

private class FakeNotifyAgents(
    initial: List<AgentTask> = emptyList(),
) : AgentRunService by UnavailableAgentRunService {
    private val _tasks = MutableStateFlow(initial)
    override val tasks: StateFlow<List<AgentTask>> = _tasks
    val tasksLoaded = CompletableDeferred<Unit>().also { it.complete(Unit) }
    fun setTasks(value: List<AgentTask>) {
        _tasks.value = value
    }

    override suspend fun awaitTasksLoaded() {
        tasksLoaded.await()
    }

    val followUps = mutableListOf<Pair<String, String>>()

    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
        attachments: List<AgentAttachment>,
    ) {
        followUps += taskId to followUp
    }
}
