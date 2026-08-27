package app.andy.ui.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.ContextualActionKind
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationTimeline
import app.andy.model.explainCrashRequest
import app.andy.model.explainMomentRequest
import app.andy.service.UnavailableInvestigationEvidenceService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextualAiActionsTest {

    private val crashRequest = explainCrashRequest(
        crashId = "crash-9",
        packageName = "com.example.garden",
        summary = "FATAL",
        crashText = "boom",
    )

    @Test
    fun evidenceIsUnavailableOnPlatformsWithoutAManagedRoot() {
        assertFalse(contextualEvidenceAvailable(UnavailableInvestigationEvidenceService))
    }

    @Test
    fun contextualTasksDefaultToReadOnlyAndNoWorktree() {
        val draft = contextualTaskDraft(
            request = crashRequest,
            prompt = "Explain this crash.",
            agent = AgentKind.Codex,
            defaults = AgentProviderDefaults(
                model = "gpt-5.6-sol",
                reasoningEffort = AgentReasoningEffort.High,
                // Last-used write settings must not leak into a contextual read-only chat.
                autonomy = AgentAutonomy.Full,
                useWorktree = true,
                planMode = true,
            ),
            contextBundleIds = listOf("evidence-1"),
        )
        assertEquals(AgentAutonomy.ReadOnly, draft.autonomy)
        assertEquals(AgentSandboxMode.ReadOnly, draft.sandboxMode)
        assertFalse(draft.useWorktree)
        assertFalse(draft.planMode)
        assertNull(draft.projectId)
        assertEquals("gpt-5.6-sol", draft.model)
        assertEquals(AgentReasoningEffort.High, draft.reasoningEffort)
        assertEquals(listOf("evidence-1"), draft.contextBundleIds)
        assertEquals(crashRequest.provenance, draft.provenance)
    }

    @Test
    fun liveDeviceActionsAttachMcpButInvestigationActionsDoNot() {
        val fromDevice = contextualTaskDraft(crashRequest, "p", AgentKind.Codex, null, emptyList())
        val fromInvestigation = contextualTaskDraft(
            request = explainMomentRequest("bug-1", "event-1", 1_000L),
            prompt = "p",
            agent = AgentKind.Codex,
            defaults = null,
            contextBundleIds = emptyList(),
        )
        assertTrue(fromDevice.attachAndyMcp)
        assertFalse(fromInvestigation.attachAndyMcp)
    }

    @Test
    fun providerPrefersLastUsedWhenItsCliIsReady() {
        val statuses = listOf(
            AgentCliStatus(AgentKind.Codex, binaryPath = "/usr/bin/codex"),
            AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/usr/bin/claude"),
        )
        assertEquals(AgentKind.ClaudeCode, contextualAgentKind(AgentKind.ClaudeCode, statuses))
    }

    @Test
    fun providerFallsBackToAnyReadyCliAndThenToNone() {
        val statuses = listOf(
            AgentCliStatus(AgentKind.Cursor, binaryPath = null),
            AgentCliStatus(AgentKind.Codex, binaryPath = "/usr/bin/codex"),
        )
        assertEquals(AgentKind.Codex, contextualAgentKind(AgentKind.Cursor, statuses))
        assertNull(contextualAgentKind(AgentKind.Cursor, listOf(AgentCliStatus(AgentKind.Cursor))))
    }

    @Test
    fun correlationLookupFindsTheRecordedEvent() {
        val timeline = InvestigationTimeline(
            originMillis = 0L,
            endedAtMillis = 5_000L,
            events = listOf(
                event("net-1", 1_000L, InvestigationEventKind.NetworkExchange, mapOf("flowId" to "flow-a")),
                event("crash-1", 2_000L, InvestigationEventKind.Crash, mapOf("crashId" to "crash-9")),
            ),
        )
        assertEquals("crash-1", timeline.eventByCorrelation("crashId", "crash-9")?.id)
        assertEquals("net-1", timeline.eventByCorrelation("flowId", "flow-a")?.id)
        assertNull(timeline.eventByCorrelation("crashId", "crash-missing"))
    }

    @Test
    fun headerSummaryRedactsCredentialsAndCapsLength() {
        val summary = redactedHeaderSummary(
            requestHeaders = mapOf(
                "Authorization" to "Bearer secret-token",
                "Content-Type" to "application/json",
            ),
            responseHeaders = mapOf("Set-Cookie" to "session=abc"),
        )
        assertTrue(summary!!.contains("Authorization=[redacted]"))
        assertTrue(summary.contains("Set-Cookie=[redacted]"))
        assertTrue(summary.contains("Content-Type=application/json"))
    }

    @Test
    fun headerSummaryIsAbsentWhenThereAreNoHeaders() {
        assertNull(redactedHeaderSummary(emptyMap(), emptyMap()))
    }

    @Test
    fun headerSummaryNotesHowManyHeadersWereOmitted() {
        val headers = (1..12).associate { "X-Header-$it" to "$it" }
        val summary = redactedHeaderSummary(headers, emptyMap(), maxHeaders = 4)
        assertTrue(summary!!.contains("(+8 more)"), "summary was: $summary")
    }

    @Test
    fun draftTitleNamesTheAction() {
        assertEquals("Explain crash", contextualTaskDraft(crashRequest, "p", AgentKind.Codex, null, emptyList()).title)
        assertEquals(ContextualActionKind.ExplainCrash, crashRequest.kind)
    }

    private fun event(
        id: String,
        atMillis: Long,
        kind: InvestigationEventKind,
        correlationIds: Map<String, String>,
    ) = InvestigationEvent(
        id = id,
        atMillis = atMillis,
        kind = kind,
        summary = id,
        correlationIds = correlationIds,
    )
}
