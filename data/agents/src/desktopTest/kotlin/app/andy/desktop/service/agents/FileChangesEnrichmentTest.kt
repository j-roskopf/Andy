package app.andy.desktop.service.agents

import app.andy.desktop.service.agents.acp.AcpTranscriptStore
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileChange
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.ActionsConfig
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import app.andy.model.WorkspaceState

class FileChangesEnrichmentTest {
    @Test
    fun synthesizeTurnSkipsWhenTurnHadNoMutatingToolCalls() = runBlocking {
        withGitService(status = AgentStatus.Done) { service, repo, taskId, baseline ->
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(taskId, AgentEvent.UserMessage(1, "what is the weather"))
            store.append(taskId, AgentEvent.AssistantText(2, "Sunny and warm."))
            File(repo, "src/Main.kt").writeText("one\ntwo\nuser edit\n")
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.testRunFileChangesEnrichmentNow(taskId, synthesizeTurn = true)

            assertEquals(0, WorktreeManager.changeSnapshotInvocations.get())
            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun synthesizeTurnDoesNotReplayPathsFromEarlierTurns() = runBlocking {
        withGitService(status = AgentStatus.Done) { service, repo, taskId, baseline ->
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(taskId, AgentEvent.UserMessage(1, "edit something"))
            store.append(
                taskId,
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "edit",
                    summary = "src/Other.kt",
                    detail = "src/Other.kt",
                    toolCallId = "call-old",
                    kind = AgentToolKind.Edit,
                    state = AgentToolState.Completed,
                    locations = listOf("src/Other.kt"),
                ),
            )
            store.append(taskId, AgentEvent.UserMessage(3, "what is the weather"))
            store.append(taskId, AgentEvent.AssistantText(4, "Sunny and warm."))
            File(repo, "src/Other.kt").writeText("one\ntwo\n")
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.testRunFileChangesEnrichmentNow(taskId, synthesizeTurn = true)
            service.testAwaitFileChangesEnrichmentJobs()

            val fileChanges = service.events(taskId).value.filterIsInstance<AgentEvent.FileChanges>()
            assertEquals(1, fileChanges.size)
            assertEquals(listOf("src/Other.kt"), fileChanges.single().snapshot.summary.files.map { it.path })
        }
    }

    @Test
    fun blockedTaskSkipsLiveEnrichment() = runBlocking {
        withGitService(status = AgentStatus.Blocked) { service, repo, taskId, baseline ->
            seedLegacyEditSegment(service, taskId, repoFile = "src/Main.kt")
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.testRunFileChangesEnrichmentNow(taskId)

            assertEquals(0, WorktreeManager.changeSnapshotInvocations.get())
            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun enrichmentPersistsSynthesizedFileChanges() = runBlocking {
        withGitService(status = AgentStatus.Working) { service, repo, taskId, baseline ->
            // withGitService already leaves src/Main.kt dirty ("one\ntwo\n" vs committed "one\n").
            seedLegacyEditSegment(service, taskId, repoFile = "src/Main.kt")
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.testRunFileChangesEnrichmentNow(taskId, synthesizeTurn = true)

            assertTrue(WorktreeManager.changeSnapshotInvocations.get() >= 1)
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            assertTrue(store.load(taskId).any { it is AgentEvent.FileChanges })
            service.events(taskId)
            service.testAwaitFileChangesEnrichmentJobs()
            assertTrue(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun midTurnEnrichmentDoesNotEmitFileChanges() = runBlocking {
        withGitService(status = AgentStatus.Working) { service, _, taskId, _ ->
            // Store load recovers ACP Working → Error; restore a live in-progress turn.
            service.testSetTaskStatus(taskId, AgentStatus.Working)
            assertTrue(service.tasks.value.single { it.id == taskId }.isActive)

            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(taskId, AgentEvent.UserMessage(1, "edit something"))
            store.append(
                taskId,
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "edit",
                    summary = "src/Main.kt",
                    detail = "src/Main.kt",
                    toolCallId = "call-edit-1",
                    kind = AgentToolKind.Edit,
                    state = AgentToolState.Completed,
                    locations = listOf("src/Main.kt"),
                ),
            )
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.testRunFileChangesEnrichmentNow(taskId)
            assertEquals(0, WorktreeManager.changeSnapshotInvocations.get())
            assertFalse(store.load(taskId).any { it is AgentEvent.FileChanges })

            service.events(taskId)
            service.testAwaitFileChangesEnrichmentJobs()
            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })
            assertFalse(store.load(taskId).any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun refreshFromDiskDoesNotReRunGitWhenFileChangesPersisted() = runBlocking {
        withGitService(status = AgentStatus.Working) { service, repo, taskId, baseline ->
            val snapshot = AgentThreadChangeSnapshot(
                summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", 1, 0))),
                diffs = emptyMap(),
            )
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(
                taskId,
                AgentEvent.FileChanges(
                    atMillis = 1,
                    batchId = "batch-persisted",
                    baselineTree = baseline,
                    snapshot = snapshot,
                ),
            )
            service.events(taskId)
            WorktreeManager.resetChangeSnapshotInvocationCount()

            service.setChatViewing(taskId, viewing = true)
            service.testAwaitFileChangesEnrichmentJobs()
            delay(500)

            assertEquals(0, WorktreeManager.changeSnapshotInvocations.get())
            assertTrue(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun incrementalEnrichmentSkipsSegmentsThatAlreadyHaveFileChanges() = runBlocking {
        val manager = WorktreeManager()
        val cwd = "/tmp/unused"
        val baseline = "abc"
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("a.kt", 1, 0))),
            diffs = emptyMap(),
        )
        val segment = listOf(
            AgentEvent.ToolCall(1, "edit", "a.kt", kind = AgentToolKind.Edit, state = AgentToolState.Completed),
            AgentEvent.FileChanges(2, "batch-1", baseline, snapshot),
        )
        WorktreeManager.resetChangeSnapshotInvocationCount()

        val (_, synthesized) = AgentFileChangesEnrichment.enrichTurnSegment(
            worktrees = manager,
            cwd = cwd,
            baseline = baseline,
            segment = segment,
            segmentPaths = { setOf("a.kt") },
        )

        assertEquals(null, synthesized)
        assertEquals(0, WorktreeManager.changeSnapshotInvocations.get())
    }

    @Test
    fun debouncedEnrichmentCoalescesRapidToolUpserts() = runBlocking {
        withGitService(status = AgentStatus.Working) { service, repo, taskId, baseline ->
            File(repo, "src/Main.kt").writeText("one\n")
            service.testAppendAcpEvents(taskId, listOf(AgentEvent.UserMessage(1, "edit something")))
            WorktreeManager.resetChangeSnapshotInvocationCount()

            repeat(20) { index ->
                service.testAppendAcpEvents(
                    taskId,
                    listOf(
                        AgentEvent.ToolCall(
                            atMillis = index.toLong() + 2,
                            toolName = "edit",
                            summary = "src/Main.kt",
                            detail = "src/Main.kt",
                            toolCallId = "call-1",
                            kind = AgentToolKind.Edit,
                            state = if (index == 19) AgentToolState.Completed else AgentToolState.InProgress,
                            locations = listOf("src/Main.kt"),
                        ),
                    ),
                )
            }

            service.testAwaitFileChangesEnrichmentJobs()
            delay(500)
            service.testAwaitFileChangesEnrichmentJobs()

            assertTrue(
                WorktreeManager.changeSnapshotInvocations.get() <= 2,
                "expected debounced enrichment, got ${WorktreeManager.changeSnapshotInvocations.get()} git snapshots",
            )
        }
    }

    @Test
    fun eventsInitialDisplayOmitsFileChangesUntilEnrichment() = runBlocking {
        withGitService(status = AgentStatus.Done) { service, repo, taskId, baseline ->
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(
                taskId,
                AgentEvent.FileChanges(
                    atMillis = 1,
                    batchId = "batch-stale",
                    baselineTree = baseline,
                    snapshot = AgentThreadChangeSnapshot(
                        summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", 1, 0))),
                        diffs = emptyMap(),
                    ),
                ),
            )
            git(repo, "add", "src/Main.kt")
            git(repo, "commit", "-m", "committed edits")

            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })

            service.testAwaitFileChangesEnrichmentJobs()

            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun eventsAddsValidFileChangesAfterImmediateEnrichment() = runBlocking {
        withGitService(status = AgentStatus.Working) { service, _, taskId, _ ->
            seedLegacyEditSegment(service, taskId, repoFile = "src/Main.kt")

            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })

            service.testRunFileChangesEnrichmentNow(taskId, synthesizeTurn = true)
            service.testAwaitFileChangesEnrichmentJobs()

            assertTrue(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun reclickingSameChatDoesNotResurrectStaleFileChangesCard() = runBlocking {
        withGitService(status = AgentStatus.Done) { service, repo, taskId, baseline ->
            val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
            store.append(
                taskId,
                AgentEvent.FileChanges(
                    atMillis = 1,
                    batchId = "batch-stale",
                    baselineTree = baseline,
                    snapshot = AgentThreadChangeSnapshot(
                        summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", 1, 0))),
                        diffs = emptyMap(),
                    ),
                ),
            )
            // withGitService leaves src/Main.kt dirty ("one\ntwo\n"); commit so the card is stale.
            git(repo, "add", "src/Main.kt")
            git(repo, "commit", "-m", "committed edits")

            service.events(taskId)
            service.setChatViewing(taskId, viewing = true)
            service.testAwaitFileChangesEnrichmentJobs()

            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })

            service.setChatViewing(taskId, viewing = true)
            service.testAwaitFileChangesEnrichmentJobs()

            assertFalse(service.events(taskId).value.any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun displayEventsEqualIgnoresEquivalentCoalescedAssistantDeltas() {
        val base = listOf(
            AgentEvent.AssistantText(1, "Hello", isStreamDelta = true),
            AgentEvent.AssistantText(2, " world", isStreamDelta = true),
        )
        val coalesced = listOf(AgentEvent.AssistantText(2, "Hello world", isStreamDelta = false))
        assertFalse(AgentFileChangesEnrichment.displayEventsEqual(base, coalesced))

        val withFileChanges = base + AgentEvent.FileChanges(
            atMillis = 3,
            batchId = "batch-1",
            baselineTree = "abc",
            snapshot = AgentThreadChangeSnapshot(
                summary = AgentChangeSummary(listOf(AgentFileChange("a.kt", 1, 0))),
                diffs = emptyMap(),
            ),
        )
        val reloaded = withFileChanges
        assertTrue(AgentFileChangesEnrichment.displayEventsEqual(withFileChanges, reloaded))
    }

    private fun seedLegacyEditSegment(service: DesktopAgentRunService, taskId: String, repoFile: String) {
        val store = AcpTranscriptStore(fileFor = { service.testTranscriptFile(it) })
        store.append(taskId, AgentEvent.UserMessage(1, "edit something"))
        store.append(
            taskId,
            AgentEvent.ToolCall(
                atMillis = 2,
                toolName = "edit",
                summary = repoFile,
                detail = repoFile,
                toolCallId = "call-edit-1",
                kind = AgentToolKind.Edit,
                state = AgentToolState.Completed,
                locations = listOf(repoFile),
            ),
        )
        service.events(taskId)
    }

    private fun withGitService(
        status: AgentStatus,
        block: suspend (DesktopAgentRunService, File, String, String) -> Unit,
    ) {
        val root = File.createTempFile("andy-file-changes", null).also { it.delete(); it.mkdirs() }
        val repo = File(root, "repo").apply { mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.db"))
        val taskId = "task-file-changes"
        git(repo, "init")
        git(repo, "config", "user.email", "test@example.test")
        git(repo, "config", "user.name", "Test")
        File(repo, "src").mkdirs()
        File(repo, "src/Main.kt").writeText("one\n")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "initial")
        val worktrees = WorktreeManager(File(root, "worktrees"))
        val baseline = checkNotNull(worktrees.captureChangeBaseline(repo.absolutePath))
        File(repo, "src/Main.kt").writeText("one\ntwo\n")

        val blockedRequest = AgentUserInputRequest(
            id = "req-1",
            questions = listOf(
                AgentUserInputQuestion(
                    id = "q1",
                    question = "Pick one",
                    options = listOf(
                        app.andy.model.AgentUserInputOption("a"),
                        app.andy.model.AgentUserInputOption("b"),
                    ),
                ),
            ),
            origin = AgentUserInputOrigin.Artifact,
        )

        runBlocking {
            store.save(
                AgentStoreState(
                    tasks = listOf(
                        AgentTask(
                            id = taskId,
                            title = "file changes",
                            prompt = "edit",
                            agent = AgentKind.Cursor,
                            cwd = repo.absolutePath,
                            originDir = repo.absolutePath,
                            lane = AgentLaneKind.Acp,
                            status = status,
                            changeBaselineTree = baseline,
                            userInputRequest = if (status == AgentStatus.Blocked) blockedRequest else null,
                            createdAtMillis = 1,
                            startedAtMillis = 2,
                        ),
                    ),
                ),
            )
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = DesktopAgentRunService(
            scope = scope,
            store = store,
            locator = AgentCliLocator(),
            adapters = mapOf(AgentKind.Cursor to CursorAdapter()),
            worktrees = worktrees,
            mcp = FileChangesFakeMcp,
            workspaceStore = FileChangesWorkspaceStore,
            actionConfig = FileChangesActionConfig,
            enableProbes = false,
            terminalMode = AgentTerminalMode.DirectPty,
        )
        try {
            runBlocking {
                service.tasks.first { it.any { it.id == taskId } }
                block(service, repo, taskId, baseline)
            }
        } finally {
            service.close()
            scope.cancel()
            root.deleteRecursively()
            WorktreeManager.resetChangeSnapshotInvocationCount()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}

private object FileChangesFakeMcp : McpServerService {
    override val status = MutableStateFlow("stopped")
    override val running = MutableStateFlow(false)
    override suspend fun start(port: Int): CommandResult = CommandResult.success("ok")
    override suspend fun stop(): CommandResult = CommandResult.success("ok")
    override fun getSnippet(clientName: String, port: Int): String = ""
    override fun getClients(): List<String> = emptyList()
    override fun isAutoWriteSupported(clientName: String): Boolean = false
    override fun writeConfig(clientName: String, port: Int): Boolean = false
    override fun getToolNames(): List<String> = emptyList()
}

private object FileChangesWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState()
    override suspend fun save(state: WorkspaceState) = Unit
}

private object FileChangesActionConfig : ActionConfigStore {
    override suspend fun load(): ActionsConfig = ActionsConfig()
    override suspend fun save(config: ActionsConfig) = Unit
}
