package app.andy.desktop.service.agents

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectBuildPairDraft
import app.andy.model.ProjectNote
import app.andy.model.ProjectPlanSnapshot
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectSpecDraft
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectWorkflowStage
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ProjectWorkflowServiceTest {
    @Test
    fun recoveryFollowUpsWaitForOneManualCumulativeReview() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("approved", "approved")))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }

            harness.service.startRecoveryFollowUp(buildId, "The confirmation toast never appears after rotation.")
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Paused
            }
            var workflow = harness.service.projects.value.getValue("project-1")
            var build = workflow.tasks.first { it.id == buildId }
            assertTrue(build.recoveryMode)
            assertTrue(build.reviewStale)
            assertEquals(2, build.attempts.size)
            assertTrue(build.attempts.last().isRecoveryFollowUp)
            assertTrue(build.attempts.last().prompt.contains("confirmation toast"))
            val originalWorkspace = harness.service.tasks.value.first { it.id == build.attempts.first().runId }.cwd
            val recoveryWorkspace = harness.service.tasks.value.first { it.id == build.attempts.last().runId }.cwd
            assertEquals(originalWorkspace, recoveryWorkspace, "recovery follow-ups must reuse the workflow workspace")
            assertEquals(1, workflow.tasks.first { it.id == build.linkedReviewTaskId }.attempts.size, "review must not start automatically")
            assertEquals(1, workflow.tasks.first { it.id == build.linkedVerificationTaskId }.attempts.size, "verification stays historical")

            harness.service.startRecoveryReview(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            assertFalse(build.recoveryMode)
            assertFalse(build.reviewStale)
            assertEquals(2, review.attempts.size)
            assertTrue(review.attempts.last().isRecoveryFollowUp)
            assertTrue(review.attempts.last().prompt.contains("cumulative re-review"))
            assertEquals(1, workflow.tasks.first { it.id == build.linkedVerificationTaskId }.attempts.size, "manual approval completes without verification")
        }
    }

    @Test
    fun recoveryReviewRejectionReturnsToManualFixModeWithoutAutoBuild() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("approved", "changes", "approved")))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }

            harness.service.startRecoveryFollowUp(buildId, "Fix the validation message.")
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.first { it.id == buildId }?.state == ProjectTaskState.Paused }
            harness.service.startRecoveryReview(buildId)
            await(timeoutMillis = 20_000) {
                val workflow = harness.service.projects.value["project-1"] ?: return@await false
                workflow.tasks.first { it.id == buildId }.state == ProjectTaskState.Paused &&
                    workflow.tasks.first { it.id == workflow.tasks.first { task -> task.id == buildId }.linkedReviewTaskId }.reviewVerdicts.size == 2
            }
            var build = harness.service.projects.value.getValue("project-1").tasks.first { it.id == buildId }
            assertTrue(build.recoveryMode)
            assertTrue(build.reviewStale)
            assertEquals(2, build.attempts.size, "rejected recovery review must not launch another build")

            harness.service.startRecoveryFollowUp(buildId, "Also cover the whitespace path.")
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.first { it.id == buildId }?.state == ProjectTaskState.Paused }
            harness.service.startRecoveryReview(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.first { it.id == buildId }?.state == ProjectTaskState.Completed }
            build = harness.service.projects.value.getValue("project-1").tasks.first { it.id == buildId }
            assertEquals(3, build.attempts.size)
            assertFalse(build.recoveryMode)
        }
    }

    @Test
    fun reviewApprovalBlocksVerificationAndIsStampedToTheExactBuild() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("approved-warnings")))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            val buildRunId = build.attempts.single().runId
            assertEquals(listOf(ProjectWorkflowStage.Build, ProjectWorkflowStage.Review, ProjectWorkflowStage.Verification), harness.service.tasks.value.mapNotNull { it.workflowStage })
            assertEquals(ProjectReviewStatus.Approved, review.reviewVerdicts.single().status)
            assertEquals(listOf(ProjectReviewFindingSeverity.Warning, ProjectReviewFindingSeverity.Nit), review.reviewVerdicts.single().findings.map { it.severity })
            assertEquals(buildRunId, review.attempts.single().reviewedBuildRunId)
            assertEquals(buildRunId, review.reviewVerdicts.single().reviewedBuildRunId)
            assertEquals(buildRunId, verification.attempts.single().reviewedBuildRunId)
            assertEquals(build.reviewGeneration, verification.verdicts.single().reviewGeneration)
            assertTrue(harness.service.tasks.value.first { it.workflowStage == ProjectWorkflowStage.Review }.prompt.contains("Standard review rubric"))
        }
    }

    @Test
    fun blockingReviewRebuildsAndFreshApprovalIsRequiredBeforeVerification() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("changes", "approved")))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(2, build.attempts.size)
            assertEquals(listOf(ProjectReviewStatus.ChangesRequested, ProjectReviewStatus.Approved), review.reviewVerdicts.map { it.status })
            assertEquals(1, verification.attempts.size)
            assertNotEquals(review.reviewVerdicts.first().reviewedBuildRunId, review.reviewVerdicts.last().reviewedBuildRunId)
            val retryRun = harness.service.tasks.value.first { it.id == build.attempts.last().runId }
            assertTrue(retryRun.prompt.contains("Unsafe fallback"))
        }
    }

    @Test
    fun verificationFailureInvalidatesReviewAndRunsItAgainAfterTheRebuild() = runBlocking {
        withHarness(
            WorkflowAdapter(
                reviewOutcomes = ArrayDeque(listOf("approved", "approved")),
                verificationOutcomes = ArrayDeque(listOf("failed", "passed")),
            ),
        ) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(2, build.attempts.size)
            assertEquals(2, review.attempts.size)
            assertEquals(2, verification.attempts.size)
            assertEquals(build.attempts.map { it.runId }, review.reviewVerdicts.map { it.reviewedBuildRunId })
        }
    }

    @Test
    fun malformedReviewNeedsAttentionWithoutConsumingTheFailureLimit() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("malformed", "approved")))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            var workflow = harness.service.projects.value.getValue("project-1")
            var build = workflow.tasks.first { it.id == buildId }
            var review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            assertTrue(review.reviewVerdicts.isEmpty())
            assertEquals(1, build.attempts.size)

            harness.service.resumeBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            assertEquals(1, build.attempts.size, "malformed Review output must not trigger another Build")
            assertEquals(2, review.attempts.size)
            assertEquals(1, review.reviewVerdicts.size)
        }
    }

    @Test
    fun invalidReviewContractsNeverAdvanceOrConsumeAReviewFailure() = runBlocking {
        listOf("malformed", "duplicate", "contradictory", "trailing").forEach { outcome ->
            withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf(outcome)))) { harness ->
                val buildId = saveExternalPair(harness.service, reviewEnabled = true)
                harness.service.startBuildPair(buildId)
                await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
                val workflow = harness.service.projects.value.getValue("project-1")
                val build = workflow.tasks.first { it.id == buildId }
                val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
                val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
                assertEquals(1, review.attempts.size, outcome)
                assertTrue(review.reviewVerdicts.isEmpty(), outcome)
                assertTrue(verification.attempts.isEmpty(), outcome)
            }
        }
    }

    @Test
    fun reviewCostAndScratchpadJoinThePairGuardrail() = runBlocking {
        withHarness(WorkflowAdapter(reportedCostUsd = 0.3)) { harness ->
            harness.service.updateScratchpad("project-1", "Audit the trust boundary")
            val buildId = harness.service.saveBuildPair(
                ProjectBuildPairDraft(
                    projectId = "project-1",
                    title = "Guarded review",
                    plan = ProjectPlanSnapshot("Implement guarded review"),
                    buildNotes = "",
                    verificationInstructions = "Run focused checks",
                    buildProfile = buildProfile(false),
                    verificationProfile = verifyProfile(),
                    maxBudgetUsd = 0.5,
                    reviewEnabled = true,
                    reviewProfile = reviewProfile(),
                    includeScratchpadInReview = true,
                ),
            )
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals("Audit the trust boundary", review.attempts.single().scratchpadSnapshot)
            assertTrue(review.attempts.single().prompt.contains("Audit the trust boundary"))
            assertEquals(ProjectReviewStatus.Approved, review.reviewVerdicts.single().status)
            assertTrue(verification.attempts.isEmpty())
            assertTrue(build.lastError?.contains("cost") == true)
        }
    }

    @Test
    fun fifthBlockingReviewStopsWithoutLaunchingVerification() = runBlocking {
        withHarness(WorkflowAdapter(reviewOutcomes = ArrayDeque(List(5) { "changes" }))) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(5, build.attempts.size)
            assertEquals(5, review.reviewVerdicts.count { it.status == ProjectReviewStatus.ChangesRequested })
            assertTrue(verification.attempts.isEmpty())
        }
    }

    @Test
    fun enablingCompletedWorkflowWaitsForResumeAndRedisablingRestoresCompletion() = runBlocking {
        withHarness(WorkflowAdapter()) { harness ->
            val buildId = saveExternalPair(harness.service)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            val completedRunCount = harness.service.tasks.value.size

            editReviewSetting(harness.service, buildId, enabled = true)
            var workflow = harness.service.projects.value.getValue("project-1")
            var build = workflow.tasks.first { it.id == buildId }
            assertEquals(ProjectTaskState.Paused, build.state)
            assertEquals(1, build.reviewGeneration)
            assertEquals(completedRunCount, harness.service.tasks.value.size, "saving an enabled Review must not spend automatically")

            editReviewSetting(harness.service, buildId, enabled = false)
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            assertEquals(ProjectTaskState.Completed, build.state)
            assertTrue(workflow.tasks.none { it.kind == ProjectTaskKind.Review }, "a never-run Review does not need a disabled audit row")
            assertEquals(completedRunCount, harness.service.tasks.value.size)

            editReviewSetting(harness.service, buildId, enabled = true)
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            assertEquals(2, build.reviewGeneration, "re-enabling must always create a fresh generation")
            harness.service.resumeBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verify = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, build.attempts.size)
            assertEquals(1, review.attempts.size)
            assertEquals(2, verify.attempts.size, "reopened completion requires a fresh Verification")
        }
    }

    @Test
    fun disablingReviewWithHistoryKeepsADisabledAuditRow() = runBlocking {
        withHarness(WorkflowAdapter()) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            editReviewSetting(harness.service, buildId, enabled = false)

            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            assertFalse(build.reviewEnabled)
            assertEquals(ProjectTaskState.Disabled, review.state)
            assertEquals(1, review.reviewVerdicts.size)
        }
    }

    @Test
    fun writeEnabledReviewChangesAreVisibleToVerificationInTheSharedWorkspace() = runBlocking {
        withHarness(WorkflowAdapter(reviewWritesFile = true), gitRepo = true) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            val runs = harness.service.tasks.value.filter { it.workflowStage in setOf(ProjectWorkflowStage.Build, ProjectWorkflowStage.Review, ProjectWorkflowStage.Verification) }
            val reviewRun = runs.first { it.workflowStage == ProjectWorkflowStage.Review }
            val verifyRun = runs.first { it.workflowStage == ProjectWorkflowStage.Verification }
            assertEquals(reviewRun.cwd, verifyRun.cwd)
            assertTrue(reviewRun.completedChanges?.summary?.files?.any { it.path == "review-edit.txt" } == true)
            assertTrue(verifyRun.prompt.contains("review-edit.txt"))
        }
    }

    @Test
    fun disablingAfterBlockingReviewBypassesItOnlyAfterExplicitResume() = runBlocking {
        withHarness(
            WorkflowAdapter(reviewOutcomes = ArrayDeque(listOf("changes")), stageDelayMillis = 250),
        ) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await {
                harness.service.tasks.value.any { it.workflowStage == ProjectWorkflowStage.Review && it.status == AgentTaskStatus.Running }
            }
            harness.service.pauseBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                val workflow = harness.service.projects.value["project-1"] ?: return@await false
                val build = workflow.tasks.firstOrNull { it.id == buildId } ?: return@await false
                val review = workflow.tasks.firstOrNull { it.id == build.linkedReviewTaskId }
                build.state == ProjectTaskState.Paused && review?.reviewVerdicts?.lastOrNull()?.status == ProjectReviewStatus.ChangesRequested
            }
            editReviewSetting(harness.service, buildId, enabled = false)
            val runCount = harness.service.tasks.value.size
            delay(100)
            assertEquals(runCount, harness.service.tasks.value.size, "disabling Review must not launch Verification")

            harness.service.resumeBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            assertEquals(1, build.attempts.size)
            assertEquals(ProjectTaskState.Disabled, review.state)
            assertEquals(1, workflow.tasks.first { it.id == build.linkedVerificationTaskId }.attempts.size)
        }
    }

    @Test
    fun reviewProcessFailurePausesWithoutLaunchingVerification() = runBlocking {
        withHarness(WorkflowAdapter(failStage = ProjectWorkflowStage.Review)) { harness ->
            val buildId = saveExternalPair(harness.service, reviewEnabled = true)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val review = workflow.tasks.first { it.id == build.linkedReviewTaskId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, review.attempts.size)
            assertTrue(review.reviewVerdicts.isEmpty())
            assertTrue(verification.attempts.isEmpty())
            assertTrue(build.paused)
        }
    }

    @Test
    fun specFeedsFreshBuildVerificationRunsUntilPassingVerdictAndReusesOneWorktree() = runBlocking {
        withHarness(WorkflowAdapter(verificationOutcomes = ArrayDeque(listOf("failed", "passed"))), gitRepo = true) { harness ->
            val service = harness.service
            service.updateScratchpad("project-1", "Keep the public API stable")
            val specId = service.saveSpec(
                ProjectSpecDraft(
                    projectId = "project-1",
                    title = "Plan typed workflows",
                    brief = "Design typed project tasks",
                    profile = specProfile(),
                    includeScratchpad = true,
                ),
            )
            service.runSpec(specId)
            await { service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }?.planVersions?.size == 1 }
            val firstVersion = service.projects.value.getValue("project-1").tasks.first { it.id == specId }.planVersions.single()
            service.runSpec(specId, "Add explicit recovery behavior")
            await { service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }?.planVersions?.size == 2 }
            val spec = service.projects.value.getValue("project-1").tasks.first { it.id == specId }
            assertEquals(AgentSandboxMode.ReadOnly, spec.profile.sandboxMode)
            assertTrue(spec.attempts.all { it.scratchpadSnapshot == "Keep the public API stable" })
            assertEquals(listOf(1, 2), spec.planVersions.map { it.version })
            assertEquals(firstVersion, spec.planVersions.first(), "revisions must never mutate older plan artifacts")
            val frozenPlan = spec.planVersions.last()

            val buildId = service.saveBuildPair(
                ProjectBuildPairDraft(
                    projectId = "project-1",
                    title = "Build typed workflows",
                    plan = ProjectPlanSnapshot(frozenPlan.text, specId, frozenPlan.version, "Plan typed workflows · v1"),
                    buildNotes = "Preserve old agent chats",
                    verificationInstructions = "Compile and run desktop tests",
                    buildProfile = buildProfile(useWorktree = true),
                    verificationProfile = verifyProfile(),
                    includeScratchpadInBuild = true,
                    includeScratchpadInVerification = false,
                ),
            )
            val draft = service.projects.value.getValue("project-1").tasks.first { it.id == buildId }
            assertEquals(ProjectTaskState.Draft, draft.state)
            val verificationId = requireNotNull(draft.linkedVerificationTaskId)
            assertTrue(service.projects.value.getValue("project-1").tasks.any { it.id == verificationId })

            service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed
            }

            val workflow = service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val verification = workflow.tasks.first { it.id == verificationId }
            assertEquals(2, build.attempts.size)
            assertEquals(2, verification.attempts.size)
            assertEquals(listOf(ProjectVerificationStatus.Failed, ProjectVerificationStatus.Passed), verification.verdicts.map { it.status })
            assertEquals(ProjectTaskState.Completed, verification.state)
            assertEquals("Keep the public API stable", build.attempts.first().scratchpadSnapshot)
            assertTrue(verification.attempts.all { it.scratchpadSnapshot == null })
            assertTrue(build.planSnapshot?.text == frozenPlan.text)

            val workflowRuns = service.tasks.value.filter { it.workflowTaskId in setOf(buildId, verificationId) }
            assertEquals(4, workflowRuns.size)
            assertEquals(4, workflowRuns.map { it.id }.distinct().size, "every stage must be a fresh agent run")
            assertEquals(1, workflowRuns.count { it.ownsWorktree })
            assertTrue(workflowRuns.map { it.worktreePath }.distinct().size == 1)
            assertTrue(workflowRuns.all { it.worktreePath == build.worktreePath })
            assertTrue(workflowRuns.filter { it.workflowStage == ProjectWorkflowStage.Build }.all { it.model == "gpt-5.6-terra" })
            assertTrue(workflowRuns.filter { it.workflowStage == ProjectWorkflowStage.Verification }.all { it.model == "gpt-5.6-luna" })
            assertNotEquals(harness.projectDir.absolutePath, build.worktreePath)
            assertTrue(workflowRuns.filter { it.workflowStage == ProjectWorkflowStage.Verification }.all { it.prompt.contains("<andy_verification>") })
        }
    }

    @Test
    fun buildWithoutVerificationCompletesAfterTheBuildRun() = runBlocking {
        withHarness(WorkflowAdapter()) { harness ->
            val buildId = harness.service.saveBuildPair(
                ProjectBuildPairDraft(
                    projectId = "project-1",
                    title = "Build without verification",
                    plan = ProjectPlanSnapshot("Implement the focused change"),
                    buildNotes = "",
                    verificationInstructions = "",
                    buildProfile = buildProfile(useWorktree = false),
                    verificationProfile = verifyProfile(),
                ),
            )
            val draft = harness.service.projects.value.getValue("project-1").tasks.first { it.id == buildId }
            assertEquals(null, draft.linkedVerificationTaskId)

            harness.service.startBuildPair(buildId)
            await {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state ==
                    ProjectTaskState.Completed
            }

            val workflowRuns = harness.service.tasks.value.filter { it.workflowTaskId == buildId }
            assertEquals(1, workflowRuns.size)
            assertEquals(ProjectWorkflowStage.Build, workflowRuns.single().workflowStage)
        }
    }

    @Test
    fun specForcesPlanModeAndAttachesAnExactInstalledGrillMeSkill() = runBlocking {
        val adapter = WorkflowAdapter(kind = AgentKind.ClaudeCode)
        withHarness(
            adapter = adapter,
            projectSetup = { projectDir ->
                File(projectDir, ".claude/skills/grill-me/SKILL.md").apply {
                    parentFile.mkdirs()
                    writeText("---\nname: grill-me\ndescription: sharpen the plan\n---\n")
                }
            },
        ) { harness ->
            val specId = harness.service.saveSpec(
                ProjectSpecDraft(
                    projectId = "project-1",
                    title = "Plan with questions",
                    brief = "Produce a decision-complete implementation plan",
                    profile = specProfile().copy(agent = AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full, sandboxMode = AgentSandboxMode.None),
                    grillMeEnabled = true,
                ),
            )
            harness.service.runSpec(specId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }?.state == ProjectTaskState.Completed }

            val run = adapter.launched.single()
            assertTrue(run.planMode)
            assertEquals(AgentAutonomy.ReadOnly, run.autonomy)
            assertEquals(AgentSandboxMode.ReadOnly, run.sandboxMode)
            assertEquals(listOf("grill-me"), run.skills.map { it.name })
        }
    }

    @Test
    fun fifthFailedVerificationStopsInNeedsAttention() = runBlocking {
        withHarness(WorkflowAdapter(verificationOutcomes = ArrayDeque(List(5) { "failed" }))) { harness ->
            val buildId = saveExternalPair(harness.service)
            harness.service.startBuildPair(buildId)
            await(timeoutMillis = 20_000) {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention
            }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(5, build.attempts.size)
            assertEquals(5, verification.attempts.size)
            assertEquals(5, verification.verdicts.size)
            assertTrue(build.paused)
            assertTrue(build.lastError?.contains("5 times") == true)

            val runCount = harness.service.tasks.value.size
            harness.service.resumeBuildPair(buildId)
            delay(100)
            assertEquals(runCount, harness.service.tasks.value.size, "the hard verification limit must not spend on another Build")
        }
    }

    @Test
    fun invalidVerifierOutputNeverCompletesBuildOrSchedulesAnotherStage() = runBlocking {
        listOf("malformed", "duplicate", "contradictory", "trailing").forEach { outcome ->
            withHarness(WorkflowAdapter(verificationOutcomes = ArrayDeque(listOf(outcome)))) { harness ->
                val buildId = saveExternalPair(harness.service)
                harness.service.startBuildPair(buildId)
                await {
                    harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention
                }
                val workflow = harness.service.projects.value.getValue("project-1")
                val build = workflow.tasks.first { it.id == buildId }
                val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
                assertEquals(1, build.attempts.size, outcome)
                assertEquals(1, verification.attempts.size, outcome)
                assertTrue(verification.verdicts.isEmpty(), outcome)
                assertFalse(build.state == ProjectTaskState.Completed, outcome)
            }
        }
    }

    @Test
    fun reportedCostGuardrailStopsBeforeSchedulingTheNextStage() = runBlocking {
        withHarness(WorkflowAdapter(reportedCostUsd = 1.0)) { harness ->
            val buildId = harness.service.saveBuildPair(
                ProjectBuildPairDraft(
                    projectId = "project-1",
                    title = "Guarded implementation",
                    plan = ProjectPlanSnapshot("Implement the guarded change"),
                    buildNotes = "",
                    verificationInstructions = "Run the focused checks",
                    buildProfile = buildProfile(useWorktree = false),
                    verificationProfile = verifyProfile(),
                    maxBudgetUsd = 0.5,
                ),
            )
            harness.service.startBuildPair(buildId)
            await {
                harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention
            }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, build.attempts.size)
            assertTrue(verification.attempts.isEmpty())
            assertTrue(build.lastError?.contains("cost") == true)
        }
    }

    @Test
    fun failedBuildProcessPausesThePairWithoutLaunchingVerification() = runBlocking {
        withHarness(WorkflowAdapter(failStage = ProjectWorkflowStage.Build)) { harness ->
            val buildId = saveExternalPair(harness.service)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, build.attempts.size)
            assertTrue(verification.attempts.isEmpty())
            assertTrue(build.paused)
        }
    }

    @Test
    fun pauseLetsTheCurrentBuildFinishAndResumeContinuesWithVerification() = runBlocking {
        withHarness(WorkflowAdapter(stageDelayMillis = 250)) { harness ->
            val buildId = saveExternalPair(harness.service)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Running }
            harness.service.pauseBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Paused }
            var workflow = harness.service.projects.value.getValue("project-1")
            var build = workflow.tasks.first { it.id == buildId }
            var verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, build.attempts.size)
            assertTrue(verification.attempts.isEmpty())

            harness.service.resumeBuildPair(buildId)
            await(timeoutMillis = 20_000) { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Completed }
            workflow = harness.service.projects.value.getValue("project-1")
            build = workflow.tasks.first { it.id == buildId }
            verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertEquals(1, build.attempts.size)
            assertEquals(1, verification.attempts.size)
        }
    }

    @Test
    fun stopCurrentTerminatesTheRunAndRequiresAttention() = runBlocking {
        withHarness(WorkflowAdapter(stageDelayMillis = 5_000)) { harness ->
            val buildId = saveExternalPair(harness.service)
            harness.service.startBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.Running }
            harness.service.stopBuildPair(buildId)
            await { harness.service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == buildId }?.state == ProjectTaskState.NeedsAttention }
            val run = harness.service.tasks.value.single { it.workflowTaskId == buildId }
            await { harness.service.tasks.value.first { it.id == run.id }.status == AgentTaskStatus.Stopped }
            val workflow = harness.service.projects.value.getValue("project-1")
            val build = workflow.tasks.first { it.id == buildId }
            val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
            assertTrue(build.paused)
            assertTrue(verification.attempts.isEmpty())
        }
    }

    @Test
    fun stoppingAndDeletingASpecRefineRestoresCompletedAndDropsTheAttempt() = runBlocking {
        withHarness(WorkflowAdapter(stageDelayMillis = 5_000)) { harness ->
            val service = harness.service
            val specId = service.saveSpec(
                ProjectSpecDraft(
                    projectId = "project-1",
                    title = "Plan typed workflows",
                    brief = "Design typed project tasks",
                    profile = specProfile(),
                ),
            )
            service.runSpec(specId)
            await { service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }?.planVersions?.size == 1 }
            assertEquals(ProjectTaskState.Completed, service.projects.value.getValue("project-1").tasks.first { it.id == specId }.state)

            service.runSpec(specId, "Tighten the recovery section")
            await { service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }?.state == ProjectTaskState.Running }
            val refineRun = service.tasks.value.single { it.workflowTaskId == specId && it.status == AgentTaskStatus.Running }
            service.stop(refineRun.id)
            await {
                val spec = service.projects.value["project-1"]?.tasks?.firstOrNull { it.id == specId }
                spec?.state == ProjectTaskState.Completed &&
                    service.tasks.value.firstOrNull { it.id == refineRun.id }?.status == AgentTaskStatus.Stopped
            }
            var spec = service.projects.value.getValue("project-1").tasks.first { it.id == specId }
            assertEquals(1, spec.planVersions.size)
            assertEquals(2, spec.attempts.size)
            assertEquals(null, spec.lastError)

            service.delete(refineRun.id, removeWorktree = false)
            spec = service.projects.value.getValue("project-1").tasks.first { it.id == specId }
            assertEquals(ProjectTaskState.Completed, spec.state)
            assertEquals(1, spec.attempts.size)
            assertEquals(spec.planVersions.single().runId, spec.attempts.single().runId)
            assertEquals(null, spec.lastError)
            assertTrue(service.tasks.value.none { it.id == refineRun.id })
        }
    }

    @Test
    fun migratesLegacyNotesIntoScratchpadOnceBeforeClearingThem() = runBlocking {
        val config = MutableActionConfig(
            ActionsConfig(
                projects = listOf(
                    ActionProject(
                        id = "project-1",
                        name = "Andy",
                        contextDir = File(".").absolutePath,
                        notes = listOf(
                            ProjectNote("note-1", "Open item", "First line\n\nSecond line", completed = false),
                            ProjectNote("note-2", "Finished item", "Already shipped", completed = true),
                        ),
                    ),
                ),
            ),
        )
        withHarness(WorkflowAdapter(), actionConfig = config) { harness ->
            await { harness.service.projects.value["project-1"]?.legacyNotesMigrated == true }
            val scratchpad = harness.service.projects.value.getValue("project-1").scratchpad
            assertEquals(
                "## Migrated todos\n- [ ] Open item\n  First line\n  \n  Second line\n- [x] Finished item\n  Already shipped",
                scratchpad,
            )
            assertTrue(config.value.projects.single().notes.isEmpty())
            val persisted = harness.store.load().projectWorkflows.getValue("project-1")
            assertTrue(persisted.legacyNotesMigrated)
            assertEquals(scratchpad, persisted.scratchpad)

            harness.service.ensureProject("project-1")
            delay(100)
            assertEquals(1, harness.service.projects.value.getValue("project-1").scratchpad.lines().count { it == "## Migrated todos" })
        }
    }

    @Test
    fun migrationRestartClearsLegacyNotesAfterTheMarkerWasAlreadyPersisted() = runBlocking {
        val root = File.createTempFile("andy-workflow-migration-restart", null).also { it.delete(); it.mkdirs() }
        val projectDir = File(root, "project").apply { mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.toml"))
        store.save(
            AgentStoreState(
                binaryOverrides = mapOf(AgentKind.Codex.cliName to workflowShellBinary()),
                projectWorkflows = mapOf(
                    "project-1" to app.andy.model.ProjectWorkflowState(
                        projectId = "project-1",
                        scratchpad = "Existing text\n\n## Migrated todos\n- [ ] Preserved item",
                        legacyNotesMigrated = true,
                    ),
                ),
            ),
        )
        val config = MutableActionConfig(
            ActionsConfig(
                projects = listOf(
                    ActionProject(
                        "project-1",
                        "Project",
                        projectDir.absolutePath,
                        notes = listOf(ProjectNote("note-1", "Preserved item", "", completed = false)),
                    ),
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val service = DesktopAgentRunService(
                scope, store, AgentCliLocator(), mapOf(AgentKind.Codex to WorkflowAdapter()), WorktreeManager(File(root, "worktrees")),
                WorkflowFakeMcp, WorkflowWorkspaceStore, config,
            )
            service.ensureProject("project-1")
            await { config.value.projects.single().notes.isEmpty() }
            val scratchpad = service.projects.value.getValue("project-1").scratchpad
            assertEquals(1, scratchpad.lines().count { it == "## Migrated todos" })
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun workflowDeletionPreservesRequiredPairingAndRequiresSpecCascade() = runBlocking {
        withHarness(WorkflowAdapter()) { harness ->
            val specId = harness.service.saveSpec(
                ProjectSpecDraft("project-1", "Plan deletion", "Plan the linked workflow", specProfile()),
            )
            val plan = ProjectPlanSnapshot("Implement the linked workflow", specId, 1, "Plan deletion · v1")
            val buildId = harness.service.saveBuildPair(
                ProjectBuildPairDraft(
                    "project-1", "Linked build", plan, "", "Run the checks",
                    buildProfile(false), verifyProfile(),
                ),
            )
            harness.service.deleteTask(specId, cascade = false)
            assertEquals(3, harness.service.projects.value.getValue("project-1").tasks.size)

            harness.service.deleteTask(specId, cascade = true)
            assertTrue(harness.service.projects.value.getValue("project-1").tasks.isEmpty())

            val externalBuildId = saveExternalPair(harness.service)
            val verificationId = harness.service.projects.value.getValue("project-1").tasks
                .first { it.id == externalBuildId }.linkedVerificationTaskId!!
            harness.service.deleteTask(verificationId)
            assertTrue(harness.service.projects.value.getValue("project-1").tasks.isEmpty())
            assertFalse(harness.service.tasks.value.any { it.workflowTaskId in setOf(buildId, externalBuildId, verificationId) })
        }
    }

    @Test
    fun restartTurnsAnInterruptedVerifierAndItsBuildIntoAttentionWithoutResuming() = runBlocking {
        val root = File.createTempFile("andy-workflow-recovery", null).also { it.delete(); it.mkdirs() }
        val projectDir = File(root, "project").apply { mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.toml"))
        val profile = verifyProfile()
        val run = AgentTask(
            id = "run-interrupted-verify",
            title = "Verify interrupted build",
            prompt = "verify it",
            agent = AgentKind.Codex,
            projectId = "project-1",
            cwd = projectDir.absolutePath,
            originDir = projectDir.absolutePath,
            status = AgentTaskStatus.Running,
            createdAtMillis = 20,
            workflowTaskId = "verify-1",
            workflowStage = ProjectWorkflowStage.Verification,
            workflowAttempt = 1,
        )
        val build = app.andy.model.ProjectTask(
            id = "build-1", projectId = "project-1", kind = ProjectTaskKind.Build,
            title = "Interrupted build", instructions = "", profile = buildProfile(false), includeScratchpad = false,
            state = ProjectTaskState.Waiting, linkedVerificationTaskId = "verify-1",
            planSnapshot = ProjectPlanSnapshot("Do the work"), verificationInstructions = "Check it",
            createdAtMillis = 1, updatedAtMillis = 10,
        )
        val verification = app.andy.model.ProjectTask(
            id = "verify-1", projectId = "project-1", kind = ProjectTaskKind.Verification,
            title = "Verify interrupted build", instructions = "Check it", profile = profile, includeScratchpad = false,
            state = ProjectTaskState.Running, linkedBuildTaskId = build.id, verificationInstructions = "Check it",
            attempts = listOf(app.andy.model.ProjectTaskAttempt(run.id, ProjectWorkflowStage.Verification, 1, run.prompt, profile, null, 20)),
            createdAtMillis = 1, updatedAtMillis = 20,
        )
        store.save(
            AgentStoreState(
                tasks = listOf(run),
                binaryOverrides = mapOf(AgentKind.Codex.cliName to workflowShellBinary()),
                projectWorkflows = mapOf("project-1" to app.andy.model.ProjectWorkflowState("project-1", tasks = listOf(build, verification))),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val service = DesktopAgentRunService(
                scope, store, AgentCliLocator(), mapOf(AgentKind.Codex to WorkflowAdapter()), WorktreeManager(File(root, "worktrees")),
                WorkflowFakeMcp, WorkflowWorkspaceStore,
                MutableActionConfig(ActionsConfig(projects = listOf(ActionProject("project-1", "Project", projectDir.absolutePath)))),
            )
            service.ensureProject("project-1")
            val recovered = service.projects.value.getValue("project-1").tasks
            assertTrue(recovered.filter { it.id in setOf(build.id, verification.id) }.all { it.state == ProjectTaskState.NeedsAttention })
            assertTrue(recovered.first { it.id == build.id }.paused)
            delay(150)
            assertEquals(1, service.tasks.value.size, "restart recovery must not launch another paid run")
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private suspend fun saveExternalPair(service: DesktopAgentRunService, reviewEnabled: Boolean = false): String = service.saveBuildPair(
        ProjectBuildPairDraft(
            projectId = "project-1",
            title = "Implement external plan",
            plan = ProjectPlanSnapshot("1. Add the feature\n2. Test it"),
            buildNotes = "",
            verificationInstructions = "Run deterministic checks",
            buildProfile = buildProfile(useWorktree = false),
            verificationProfile = verifyProfile(),
            reviewEnabled = reviewEnabled,
            reviewInstructions = "Flag correctness, security, and scope regressions",
            reviewProfile = reviewProfile(),
        ),
    )

    private suspend fun editReviewSetting(service: DesktopAgentRunService, buildId: String, enabled: Boolean) {
        val workflow = service.projects.value.getValue("project-1")
        val build = workflow.tasks.first { it.id == buildId }
        val review = build.linkedReviewTaskId?.let { id -> workflow.tasks.firstOrNull { it.id == id } }
        val verification = workflow.tasks.first { it.id == build.linkedVerificationTaskId }
        service.saveBuildPair(
            ProjectBuildPairDraft(
                projectId = build.projectId,
                title = build.title,
                plan = requireNotNull(build.planSnapshot),
                buildNotes = build.buildNotes,
                verificationInstructions = build.verificationInstructions,
                buildProfile = build.profile,
                verificationProfile = verification.profile,
                includeScratchpadInBuild = build.includeScratchpad,
                includeScratchpadInVerification = verification.includeScratchpad,
                maxBudgetUsd = build.maxBudgetUsd,
                buildTaskId = build.id,
                reviewEnabled = enabled,
                reviewInstructions = build.reviewInstructions.ifBlank { "Review the risky paths" },
                reviewProfile = review?.profile ?: reviewProfile(),
                includeScratchpadInReview = review?.includeScratchpad ?: false,
            ),
        )
    }

    private fun specProfile() = ProjectAgentProfile(
        agent = AgentKind.Codex,
        model = "gpt-5.6-sol",
        autonomy = AgentAutonomy.ReadOnly,
        sandboxMode = AgentSandboxMode.ReadOnly,
    )

    private fun buildProfile(useWorktree: Boolean) = ProjectAgentProfile(
        agent = AgentKind.Codex,
        model = "gpt-5.6-terra",
        autonomy = AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.WorkspaceWrite,
        useWorktree = useWorktree,
    )

    private fun verifyProfile() = ProjectAgentProfile(
        agent = AgentKind.Codex,
        model = "gpt-5.6-luna",
        autonomy = AgentAutonomy.ReadOnly,
        sandboxMode = AgentSandboxMode.ReadOnly,
    )

    private fun reviewProfile() = ProjectAgentProfile(
        agent = AgentKind.Codex,
        model = "gpt-5.6-orbit",
        autonomy = AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.WorkspaceWrite,
    )
}

private data class WorkflowHarness(
    val service: DesktopAgentRunService,
    val store: DesktopAgentTaskStore,
    val projectDir: File,
)

private suspend fun withHarness(
    adapter: WorkflowAdapter,
    gitRepo: Boolean = false,
    actionConfig: MutableActionConfig? = null,
    projectSetup: (File) -> Unit = {},
    block: suspend (WorkflowHarness) -> Unit,
) {
    val root = File.createTempFile("andy-workflow", null).also { it.delete(); it.mkdirs() }
    val projectDir = File(root, "project").apply { mkdirs() }
    if (gitRepo) initializeGitRepository(projectDir)
    projectSetup(projectDir)
    val config = actionConfig ?: MutableActionConfig(
        ActionsConfig(projects = listOf(ActionProject("project-1", "Test project", projectDir.absolutePath))),
    )
    val store = DesktopAgentTaskStore(File(root, "agents.toml"))
    store.save(AgentStoreState(binaryOverrides = mapOf(adapter.kind.cliName to workflowShellBinary())))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        val service = DesktopAgentRunService(
            scope = scope,
            store = store,
            locator = AgentCliLocator(),
            adapters = mapOf(adapter.kind to adapter),
            worktrees = WorktreeManager(File(root, "worktrees")),
            mcp = WorkflowFakeMcp,
            workspaceStore = WorkflowWorkspaceStore,
            actionConfig = config,
        )
        service.ensureProject("project-1")
        block(WorkflowHarness(service, store, projectDir))
    } finally {
        scope.cancel()
        root.deleteRecursively()
    }
}

private class WorkflowAdapter(
    private val verificationOutcomes: ArrayDeque<String> = ArrayDeque(),
    private val reviewOutcomes: ArrayDeque<String> = ArrayDeque(),
    private val reportedCostUsd: Double? = null,
    override val kind: AgentKind = AgentKind.Codex,
    private val failStage: ProjectWorkflowStage? = null,
    private val stageDelayMillis: Long = 0,
    private val reviewWritesFile: Boolean = false,
) : AgentCliAdapter {
    override val supportsHeadlessResume = false
    override val supportsStreamJson = false
    val launched = Collections.synchronizedList(mutableListOf<AgentTask>())

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> {
        launched += task
        val marker = when (task.workflowStage) {
            ProjectWorkflowStage.Spec -> "spec"
            ProjectWorkflowStage.Build -> "build"
            ProjectWorkflowStage.Review -> "review-${if (reviewOutcomes.isEmpty()) "approved" else reviewOutcomes.removeFirst()}"
            ProjectWorkflowStage.Verification -> "verify-${if (verificationOutcomes.isEmpty()) "passed" else verificationOutcomes.removeFirst()}"
            null -> "generic"
        }
        if (isWindows()) {
            val command = if (task.workflowStage == failStage) {
                "exit /b 7"
            } else {
                buildString {
                    if (stageDelayMillis > 0) append("ping 127.0.0.1 -n 2 >NUL & ")
                    if (reviewWritesFile && task.workflowStage == ProjectWorkflowStage.Review) append("echo reviewed change>review-edit.txt & ")
                    append("echo ").append(marker)
                }
            }
            return listOf(binary, "/d", "/c", command)
        }
        val command = if (task.workflowStage == failStage) {
            "exit 7"
        } else {
            buildString {
                if (stageDelayMillis > 0) append("sleep ").append(stageDelayMillis / 1_000.0).append("; ")
                if (reviewWritesFile && task.workflowStage == ProjectWorkflowStage.Review) append("printf 'reviewed change\\n' > review-edit.txt; ")
                append("printf '").append(marker).append("\\n'")
            }
        }
        return listOf(binary, "-c", command)
    }

    override fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>? = null
    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> {
        val final = when (line.trim()) {
            "spec" -> "1. Add typed workflow models\n2. Implement the guarded loop\n3. Verify it"
            "build" -> "Implementation complete; checks are ready for the verifier."
            "review-approved" -> "<andy_review>{\"status\":\"approved\",\"summary\":\"Code matches the plan\",\"findings\":[]}</andy_review>"
            "review-approved-warnings" -> "<andy_review>{\"status\":\"approved\",\"summary\":\"No blocking issues\",\"findings\":[{\"severity\":\"warning\",\"title\":\"Broader test coverage\",\"details\":\"Add a stress case later\",\"file\":\"src/Test.kt\",\"line\":12},{\"severity\":\"nit\",\"title\":\"Naming\",\"details\":\"A local could be clearer\"}]}</andy_review>"
            "review-changes" -> "<andy_review>{\"status\":\"changes_requested\",\"summary\":\"A blocking issue remains\",\"findings\":[{\"severity\":\"blocking\",\"title\":\"Unsafe fallback\",\"details\":\"The failure path bypasses validation\",\"file\":\"src/Main.kt\",\"line\":42}]}</andy_review>"
            "review-malformed" -> "Review found no issue, but intentionally omitted the verdict block."
            "review-duplicate" -> "<andy_review>{\"status\":\"approved\",\"summary\":\"First\",\"findings\":[]}</andy_review>\n<andy_review>{\"status\":\"approved\",\"summary\":\"Second\",\"findings\":[]}</andy_review>"
            "review-contradictory" -> "<andy_review>{\"status\":\"approved\",\"summary\":\"Contradictory\",\"findings\":[{\"severity\":\"blocking\",\"title\":\"Blocker\",\"details\":\"This must block\"}]}</andy_review>"
            "review-trailing" -> "<andy_review>{\"status\":\"approved\",\"summary\":\"Looks good\",\"findings\":[]}</andy_review>\nThis makes the block non-terminal."
            "verify-failed" -> "<andy_verification>{\"status\":\"failed\",\"summary\":\"A check failed\",\"evidence\":[\"Sources compiled\"],\"failures\":[\"desktop test failed\"]}</andy_verification>"
            "verify-passed" -> "<andy_verification>{\"status\":\"passed\",\"summary\":\"All checks passed\",\"evidence\":[\"compile passed\",\"desktop tests passed\"],\"failures\":[]}</andy_verification>"
            "verify-malformed" -> "Verification looked fine, but this is intentionally not a verdict block."
            "verify-duplicate" -> "<andy_verification>{\"status\":\"failed\",\"summary\":\"First\",\"evidence\":[],\"failures\":[\"one\"]}</andy_verification>\n<andy_verification>{\"status\":\"failed\",\"summary\":\"Second\",\"evidence\":[],\"failures\":[\"two\"]}</andy_verification>"
            "verify-contradictory" -> "<andy_verification>{\"status\":\"passed\",\"summary\":\"Contradictory\",\"evidence\":[\"a check ran\"],\"failures\":[\"it failed\"]}</andy_verification>"
            "verify-trailing" -> "<andy_verification>{\"status\":\"passed\",\"summary\":\"Passed\",\"evidence\":[\"tests passed\"],\"failures\":[]}</andy_verification>\nThis text makes the block non-terminal."
            else -> line
        }
        return listOf(AgentEvent.AssistantText(nowMillis, final), AgentEvent.TaskResult(nowMillis, success = true, finalText = final, costUsd = reportedCostUsd))
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

private fun workflowShellBinary(): String = if (isWindows()) {
    checkNotNull(System.getenv("ComSpec")) { "ComSpec is required to run workflow tests on Windows" }
} else {
    "/bin/sh"
}

private class MutableActionConfig(var value: ActionsConfig) : ActionConfigStore {
    override suspend fun load(): ActionsConfig = value
    override suspend fun save(config: ActionsConfig) { value = config }
}

private object WorkflowFakeMcp : McpServerService {
    override val status = MutableStateFlow("stopped")
    override val running = MutableStateFlow(false)
    override suspend fun start(port: Int): CommandResult = CommandResult.success()
    override suspend fun stop(): CommandResult = CommandResult.success()
    override fun getSnippet(clientName: String, port: Int): String = ""
    override fun getClients(): List<String> = emptyList()
    override fun isAutoWriteSupported(clientName: String): Boolean = false
    override fun writeConfig(clientName: String, port: Int): Boolean = false
    override fun getToolNames(): List<String> = emptyList()
}

private object WorkflowWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState()
    override suspend fun save(state: WorkspaceState) = Unit
}

private suspend fun await(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    withTimeout(timeoutMillis) {
        while (!condition()) delay(20)
    }
}

private fun initializeGitRepository(directory: File) {
    fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
    git("init")
    git("config", "user.email", "andy-tests@example.test")
    git("config", "user.name", "Andy Tests")
    File(directory, "README.md").writeText("test repository\n")
    git("add", "README.md")
    git("commit", "-m", "initial")
}
