package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectPlanSnapshot
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectReviewFinding
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectReviewVerdict
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskAttempt
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectVerificationVerdict
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class DesktopAgentTaskStoreTest {
    private fun withStore(block: suspend (DesktopAgentTaskStore) -> Unit) {
        val dir = File.createTempFile("andy-agents", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            runBlocking { block(DesktopAgentTaskStore(File(dir, "agents.toml"))) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsTasks() = withStore { store ->
        val task = AgentTask(
            id = "task-abc",
            title = "fix the bug",
            prompt = "fix it\nwith newlines",
            agent = AgentKind.Codex,
            projectId = "proj-1",
            cwd = "/tmp/wt",
            originDir = "/tmp/repo",
            useWorktree = true,
            worktreePath = "/tmp/wt",
            branchName = "andy/codex/fix-abc",
            attachAndyMcp = true,
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            planMode = true,
            completedPlanText = "1. Update the service\n2. Verify it",
            implementationPrompt = "Begin implementation using the completed plan.",
            continuationPrompt = "Continue after the user picks a platform.",
            model = "gpt-5.6-sol",
            reasoningEffort = AgentReasoningEffort.ExtraHigh,
            imagePaths = listOf("/tmp/reference.png"),
            goal = "Ship the regression fix with coverage",
            queuedFollowUps = listOf(
                AgentQueuedFollowUp(
                    text = "run the verification suite next",
                    imagePaths = listOf("/tmp/next.png"),
                    skills = listOf(AgentSkill("verify", "", "/tmp/verify/SKILL.md")),
                ),
                AgentQueuedFollowUp(text = "then summarize the results"),
            ),
            userInputRequest = AgentUserInputRequest(
                id = "request-1",
                questions = listOf(
                    AgentUserInputQuestion(
                        id = "platform",
                        header = "Target",
                        question = "Which platform should v1 support?",
                        options = listOf(
                            AgentUserInputOption("Desktop", "Mac desktop only."),
                            AgentUserInputOption("Desktop + web", "Ship both surfaces."),
                        ),
                    ),
                ),
            ),
            maxBudgetUsd = 2.5,
            completedChanges = AgentThreadChangeSnapshot(
                summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", additions = 2, deletions = 1))),
                diffs = mapOf(
                    "src/Main.kt" to AgentFileDiff(
                        path = "src/Main.kt",
                        lines = listOf(
                            DiffLine(DiffLineKind.Deletion, "old", oldLineNumber = 1),
                            DiffLine(DiffLineKind.Addition, "new", newLineNumber = 1),
                        ),
                    ),
                ),
            ),
            status = AgentTaskStatus.WaitingForInput,
            vendorSessionId = "t-99",
            createdAtMillis = 111,
            startedAtMillis = 222,
            finishedAtMillis = 333,
            exitCode = 0,
            totalCostUsd = 0.42,
            inputTokens = 100,
            outputTokens = 200,
            contextTokens = 120_000,
            contextWindowTokens = 272_000,
            ownsWorktree = true,
            workflowTaskId = "build-1",
            workflowStage = ProjectWorkflowStage.Build,
            workflowAttempt = 2,
            completedResultText = "implemented the frozen plan",
        )
        store.save(AgentStoreState(tasks = listOf(task), binaryOverrides = mapOf("codex" to "/bin/codex"), maxConcurrent = 4))
        val loaded = store.load()
        assertEquals(listOf(task), loaded.tasks)
        assertEquals(mapOf("codex" to "/bin/codex"), loaded.binaryOverrides)
        assertEquals(4, loaded.maxConcurrent)
    }

    @Test
    fun activeTasksBecomeUnknownOnLoad() = withStore { store ->
        val running = AgentTask(
            id = "task-run",
            title = "still going",
            prompt = "p",
            agent = AgentKind.ClaudeCode,
            cwd = "/tmp",
            originDir = "/tmp",
            status = AgentTaskStatus.Running,
            createdAtMillis = 1,
        )
        val queued = running.copy(id = "task-q", status = AgentTaskStatus.Queued)
        store.save(AgentStoreState(tasks = listOf(running, queued)))
        val loaded = store.load()
        assertEquals(setOf(AgentTaskStatus.Unknown), loaded.tasks.map { it.status }.toSet())
    }

    @Test
    fun missingFileYieldsDefaults() = withStore { store ->
        val loaded = store.load()
        assertEquals(AgentStoreState(), loaded)
    }

    @Test
    fun roundTripsProviderDefaults() = withStore { store ->
        val defaults = AgentProviderDefaults(
            model = "gpt-5.6-terra",
            reasoningEffort = AgentReasoningEffort.High,
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            planMode = true,
            useWorktree = true,
            attachAndyMcp = true,
            maxBudgetUsd = 4.0,
        )
        store.save(
            AgentStoreState(
                providerDefaults = mapOf(AgentKind.Codex to defaults),
                lastUsedAgent = AgentKind.Codex,
            ),
        )

        val loaded = store.load()
        assertEquals(mapOf(AgentKind.Codex to defaults), loaded.providerDefaults)
        assertEquals(AgentKind.Codex, loaded.lastUsedAgent)
    }

    @Test
    fun roundTripsTypedProjectWorkflows() = withStore { store ->
        val specProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-sol",
            reasoningEffort = AgentReasoningEffort.ExtraHigh,
            fastMode = true,
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = AgentSandboxMode.ReadOnly,
            maxBudgetUsd = 1.25,
        )
        val buildProfile = ProjectAgentProfile(
            agent = AgentKind.ClaudeCode,
            model = "sonnet",
            reasoningEffort = AgentReasoningEffort.Medium,
            useWorktree = true,
            attachAndyMcp = true,
        )
        val verifyProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-terra",
            reasoningEffort = AgentReasoningEffort.High,
            sandboxMode = AgentSandboxMode.ReadOnly,
        )
        val reviewProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-orbit",
            reasoningEffort = AgentReasoningEffort.High,
            autonomy = AgentAutonomy.Standard,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            attachAndyMcp = true,
        )
        val plan = ProjectPlanVersion(1, "1. Add workflow state\n2. Verify it", "run-spec-1", 100)
        val buildAttempt = ProjectTaskAttempt(
            runId = "run-build-1",
            stage = ProjectWorkflowStage.Build,
            attempt = 1,
            prompt = "frozen build prompt",
            profile = buildProfile,
            scratchpadSnapshot = "do not change the API",
            createdAtMillis = 200,
        )
        val verifyAttempt = ProjectTaskAttempt(
            runId = "run-verify-1",
            stage = ProjectWorkflowStage.Verification,
            attempt = 1,
            prompt = "verification prompt",
            profile = verifyProfile,
            createdAtMillis = 300,
            reviewedBuildRunId = "run-build-1",
            reviewGeneration = 3,
        )
        val reviewAttempt = ProjectTaskAttempt(
            runId = "run-review-1",
            stage = ProjectWorkflowStage.Review,
            attempt = 1,
            prompt = "review prompt",
            profile = reviewProfile,
            scratchpadSnapshot = "review context",
            createdAtMillis = 260,
            reviewedBuildRunId = "run-build-1",
            reviewGeneration = 3,
        )
        val spec = ProjectTask(
            id = "spec-1", projectId = "project-1", kind = ProjectTaskKind.Spec,
            title = "Plan workflows", instructions = "Design the workflow", profile = specProfile,
            includeScratchpad = true, imagePaths = listOf("/tmp/mockup.png", "/tmp/wireframe.jpg"),
            state = ProjectTaskState.Completed, planVersions = listOf(plan),
            grillMeEnabled = true, attempts = listOf(ProjectTaskAttempt("run-spec-1", ProjectWorkflowStage.Spec, 1, "spec prompt", specProfile, "scratch", 90)),
            createdAtMillis = 80, updatedAtMillis = 100,
        )
        val build = ProjectTask(
            id = "build-1", projectId = "project-1", kind = ProjectTaskKind.Build,
            title = "Build workflows", instructions = "Keep compatibility", profile = buildProfile,
            includeScratchpad = false, state = ProjectTaskState.Waiting, linkedSpecTaskId = spec.id,
            linkedReviewTaskId = "review-1",
            linkedVerificationTaskId = "verify-1",
            planSnapshot = ProjectPlanSnapshot(plan.text, spec.id, 1, "Plan workflows · v1"),
            buildNotes = "Keep compatibility", verificationInstructions = "Run desktop tests",
            reviewEnabled = true, reviewInstructions = "Focus on security", reviewGeneration = 3,
            maxReviewFailures = 5, reviewReopenedCompleted = true,
            maxVerificationAttempts = 4, maxBudgetUsd = 3.5, paused = true,
            workspacePath = "/tmp/worktree", worktreePath = "/tmp/worktree",
            branchName = "codex/workflow", worktreeOwnerRunId = "run-build-1", attempts = listOf(buildAttempt),
            lastError = "waiting for a retry",
            createdAtMillis = 150, updatedAtMillis = 250,
        )
        val review = ProjectTask(
            id = "review-1", projectId = "project-1", kind = ProjectTaskKind.Review,
            title = "Review workflows", instructions = "Focus on security", profile = reviewProfile,
            includeScratchpad = true, state = ProjectTaskState.Failed, linkedSpecTaskId = spec.id,
            linkedBuildTaskId = build.id, linkedVerificationTaskId = "verify-1", planSnapshot = build.planSnapshot,
            reviewEnabled = true, reviewInstructions = "Focus on security", reviewGeneration = 3,
            attempts = listOf(reviewAttempt),
            reviewVerdicts = listOf(
                ProjectReviewVerdict(
                    status = ProjectReviewStatus.ChangesRequested,
                    summary = "One blocker remains",
                    findings = listOf(
                        ProjectReviewFinding(
                            ProjectReviewFindingSeverity.Blocking,
                            "Unsafe fallback",
                            "Validation can be skipped",
                            "src/Main.kt",
                            42,
                        ),
                    ),
                    runId = "run-review-1",
                    reviewedBuildRunId = "run-build-1",
                    reviewGeneration = 3,
                    createdAtMillis = 280,
                ),
            ),
            createdAtMillis = 150, updatedAtMillis = 280,
        )
        val verification = ProjectTask(
            id = "verify-1", projectId = "project-1", kind = ProjectTaskKind.Verification,
            title = "Verify workflows", instructions = "Run desktop tests", profile = verifyProfile,
            includeScratchpad = false, state = ProjectTaskState.Failed, linkedSpecTaskId = spec.id, linkedBuildTaskId = build.id,
            planSnapshot = build.planSnapshot,
            verificationInstructions = "Run desktop tests", attempts = listOf(verifyAttempt),
            verdicts = listOf(ProjectVerificationVerdict(ProjectVerificationStatus.Failed, "One test failed", listOf("compile passed"), listOf("desktopTest failed"), "run-verify-1", 350, "run-build-1", 3)),
            createdAtMillis = 150, updatedAtMillis = 350,
        )
        val workflow = ProjectWorkflowState(
            projectId = "project-1",
            scratchpad = "persistent project context",
            profiles = mapOf(ProjectTaskKind.Spec to specProfile, ProjectTaskKind.Build to buildProfile, ProjectTaskKind.Review to reviewProfile, ProjectTaskKind.Verification to verifyProfile),
            tasks = listOf(spec, build, review, verification),
            legacyNotesMigrated = true,
        )

        store.save(AgentStoreState(projectWorkflows = mapOf(workflow.projectId to workflow)))

        assertEquals(mapOf(workflow.projectId to workflow), store.load().projectWorkflows)
    }

    @Test
    fun loadsVersionTwoWorkflowWithReviewDisabledAndNoReviewTask() = withStore { store ->
        val profile = ProjectAgentProfile()
        val build = ProjectTask(
            id = "build-v2", projectId = "project-v2", kind = ProjectTaskKind.Build,
            title = "Legacy typed build", instructions = "", profile = profile, includeScratchpad = false,
            linkedVerificationTaskId = "verify-v2", planSnapshot = ProjectPlanSnapshot("Frozen v2 plan"),
            verificationInstructions = "Run checks", createdAtMillis = 1, updatedAtMillis = 2,
        )
        val verify = ProjectTask(
            id = "verify-v2", projectId = "project-v2", kind = ProjectTaskKind.Verification,
            title = "Verify legacy build", instructions = "Run checks", profile = profile, includeScratchpad = false,
            linkedBuildTaskId = build.id, verificationInstructions = "Run checks", createdAtMillis = 1, updatedAtMillis = 2,
        )
        val workflow = ProjectWorkflowState("project-v2", tasks = listOf(build, verify))
        store.save(AgentStoreState(projectWorkflows = mapOf(workflow.projectId to workflow)))
        val file = store.javaClass.getDeclaredField("file").let { field ->
            field.isAccessible = true
            field.get(store) as File
        }
        file.writeText(file.readText().replaceFirst("version = 3", "version = 2"))

        val loaded = store.load().projectWorkflows.getValue("project-v2")
        assertEquals(false, loaded.tasks.first { it.kind == ProjectTaskKind.Build }.reviewEnabled)
        assertEquals(emptyList(), loaded.tasks.filter { it.kind == ProjectTaskKind.Review })
        assertEquals(listOf("build-v2", "verify-v2"), loaded.tasks.map { it.id })
    }

    @Test
    fun loadsVersionOneAgentDataWithWorkflowDefaults() {
        val dir = File.createTempFile("andy-agents-v1", null).also { it.delete(); it.mkdirs() }
        val file = File(dir, "agents.toml")
        try {
            file.writeText(
                """
                version = 1
                maxConcurrent = 3

                [[tasks]]
                id = "legacy-task"
                title = "Legacy"
                prompt = "keep working"
                agent = "Codex"
                status = "Completed"
                createdAtMillis = 42
                """.trimIndent(),
            )
            val loaded = runBlocking { DesktopAgentTaskStore(file).load() }
            assertEquals("legacy-task", loaded.tasks.single().id)
            assertEquals(3, loaded.maxConcurrent)
            assertEquals(emptyMap(), loaded.projectWorkflows)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsArchivedFlag() = withStore { store ->
        val task = AgentTask(
            id = "archived-task",
            title = "old chat",
            prompt = "done",
            agent = AgentKind.Codex,
            projectId = "proj-1",
            status = AgentTaskStatus.Completed,
            createdAtMillis = 42,
            archived = true,
        )
        store.save(AgentStoreState(tasks = listOf(task)))
        assertEquals(true, store.load().tasks.single().archived)
    }
}
