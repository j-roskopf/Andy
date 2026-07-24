package app.andy.desktop.service.agents

import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectVerificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File

class AgentWorkflowArtifactsTest {
    @Test
    fun reviewJsonEmitsReviewReady() = runTest {
        val root = File.createTempFile("andy-artifacts-review", null).also { it.delete(); it.mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val artifacts = AgentWorkflowArtifacts(scope, "task-review", root)
            artifacts.start()
            root.resolve("review.json").writeText(
                """
                {
                  "status": "changes_requested",
                  "summary": "Blocking issue found",
                  "findings": [{
                    "severity": "blocking",
                    "title": "Unsafe fallback",
                    "details": "Validation is bypassed on failure",
                    "file": "src/Main.kt",
                    "line": 42
                  }]
                }
                """.trimIndent(),
            )

            val event = artifacts.events.first()
            assertTrue(event is AgentWorkflowArtifacts.Event.ReviewReady)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun parseReviewJsonMapsChangesRequestedWithBlockingFinding() {
        val verdict = AgentWorkflowArtifacts.parseReviewJson(
            raw = """
            {
              "status": "changes_requested",
              "summary": "Blocking issue found",
              "findings": [{
                "severity": "blocking",
                "title": "Unsafe fallback",
                "details": "Validation is bypassed on failure",
                "file": "src/Main.kt",
                "line": 42
              }]
            }
            """.trimIndent(),
            runId = "run-1",
            reviewedBuildRunId = "build-1",
            reviewGeneration = 1,
            atMillis = 100,
        )
        assertNotNull(verdict)
        assertEquals(ProjectReviewStatus.ChangesRequested, verdict.status)
        assertEquals(ProjectReviewFindingSeverity.Blocking, verdict.findings.single().severity)
    }

    @Test
    fun parseReviewJsonAcceptsApprovedWithEmptyFindings() {
        val verdict = AgentWorkflowArtifacts.parseReviewJson(
            raw = """{"status":"approved","summary":"Looks good","findings":[]}""",
            runId = "run-1",
            reviewedBuildRunId = "build-1",
            reviewGeneration = 1,
            atMillis = 100,
        )
        assertNotNull(verdict)
        assertEquals(ProjectReviewStatus.Approved, verdict.status)
        assertTrue(verdict.findings.isEmpty())
    }

    @Test
    fun parseVerificationJsonHandlesPassedAndFailed() {
        val passed = AgentWorkflowArtifacts.parseVerificationJson(
            raw = """{"status":"passed","summary":"All checks passed","evidence":["tests passed"],"failures":[]}""",
            runId = "run-1",
            atMillis = 100,
            reviewedBuildRunId = "build-1",
            reviewGeneration = 1,
        )
        assertNotNull(passed)
        assertEquals(ProjectVerificationStatus.Passed, passed.status)

        val failed = AgentWorkflowArtifacts.parseVerificationJson(
            raw = """{"status":"failed","summary":"A check failed","evidence":["compiled"],"failures":["desktop test failed"]}""",
            runId = "run-2",
            atMillis = 200,
            reviewedBuildRunId = "build-1",
            reviewGeneration = 1,
        )
        assertNotNull(failed)
        assertEquals(ProjectVerificationStatus.Failed, failed.status)
    }

    @Test
    fun parseQuestionJsonProducesAgentUserInputRequest() {
        val request = AgentWorkflowArtifacts.parseQuestionJson(
            """
            {
              "id": "checkpoint-1",
              "questions": [{
                "id": "platform",
                "question": "Which platform?",
                "options": [{"label": "Desktop"}, {"label": "Web"}]
              }]
            }
            """.trimIndent(),
        )
        assertNotNull(request)
        assertEquals("checkpoint-1", request.id)
        assertEquals("platform", request.questions.single().id)
        assertEquals(2, request.questions.single().options.size)
    }

    @Test
    fun parseQuestionJsonRejectsInvalidPayload() {
        assertNull(
            AgentWorkflowArtifacts.parseQuestionJson(
                """{"questions":[{"id":"bad id","question":"?","options":[{"label":"A"},{"label":"B"}]}]}""",
            ),
        )
    }

    @Test
    fun dirForUsesAndySubdirectoryUnderCwd() {
        val cwd = File.createTempFile("andy-artifact-cwd", null).apply { delete(); mkdirs(); deleteOnExit() }
        val dir = AgentWorkflowArtifacts.dirFor(cwd, "task-xyz")
        assertEquals(File(cwd, ".andy/task-xyz").absolutePath, dir.absolutePath)
    }
}
