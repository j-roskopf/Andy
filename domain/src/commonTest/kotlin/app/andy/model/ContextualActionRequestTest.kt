package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextualActionRequestTest {

    @Test
    fun crashRequestCarriesCrashProvenanceAndExcerpt() {
        val request = explainCrashRequest(
            crashId = "crash-9",
            packageName = "com.example.garden",
            summary = "FATAL EXCEPTION: main",
            crashText = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:12)",
        )
        assertEquals(ContextualActionKind.ExplainCrash, request.provenance.sourceKind)
        assertEquals("crash-9", request.provenance.crashId)
        assertEquals("com.example.garden", request.provenance.packageName)
        assertFalse(request.hasEvidence, "no investigation was supplied, so the action is prompt-only")

        val prompt = request.promptDraft()
        assertTrue(prompt.contains("FATAL EXCEPTION: main"))
        assertTrue(prompt.contains("IllegalStateException"))
        assertTrue(prompt.contains("crash=crash-9"))
    }

    @Test
    fun crashRequestAttachesEvidenceWhenAnInvestigationHoldsIt() {
        val request = explainCrashRequest(
            crashId = "crash-9",
            packageName = "com.example.garden",
            summary = "FATAL EXCEPTION: main",
            crashText = "boom",
            investigationId = "bug-3",
            eventId = "crash-crash-9",
            atMillis = 4_200L,
        )
        assertTrue(request.hasEvidence)
        assertEquals("bug-3", request.evidence?.investigationId)
        assertEquals("crash-crash-9", request.evidence?.focusedEventId)
        assertEquals(4_200L, request.evidence?.centerMillis)
        assertEquals("bug-3", request.provenance.investigationId)
        assertEquals(4_200L, request.provenance.playbackMillis)
    }

    @Test
    fun networkRequestDescribesTheExchangeInThePrompt() {
        val request = explainNetworkRequest(
            exchangeId = "flow-7",
            method = "POST",
            url = "https://api.example.com/v1/checkout",
            statusCode = 500,
            durationMillis = 812L,
            error = "upstream timeout",
            headerSummary = "request: Authorization=[redacted]",
        )
        assertEquals(ContextualActionKind.ExplainRequest, request.provenance.sourceKind)
        assertEquals("flow-7", request.provenance.networkExchangeId)

        val prompt = request.promptDraft()
        assertTrue(prompt.contains("POST"))
        assertTrue(prompt.contains("https://api.example.com/v1/checkout"))
        assertTrue(prompt.contains("500"))
        assertTrue(prompt.contains("812ms"))
        assertTrue(prompt.contains("upstream timeout"))
        assertTrue(prompt.contains("Authorization=[redacted]"))
        assertTrue(prompt.contains("exchange=flow-7"))
    }

    @Test
    fun nodeRequestCarriesTheNodesIdentifyingProperties() {
        val request = explainNodeRequest(
            nodeId = "node-42",
            className = "androidx.compose.ui.platform.ComposeView",
            resourceId = "com.example:id/checkout_button",
            text = "Place order",
            contentDescription = null,
            bounds = "[0,120][1080,300]",
            packageName = "com.example.garden",
        )
        assertEquals(ContextualActionKind.ExplainNode, request.provenance.sourceKind)
        assertEquals("node-42", request.provenance.hierarchyNodeId)

        val prompt = request.promptDraft()
        assertTrue(prompt.contains("ComposeView"))
        assertTrue(prompt.contains("com.example:id/checkout_button"))
        assertTrue(prompt.contains("Place order"))
        assertTrue(prompt.contains("[0,120][1080,300]"))
        assertFalse(prompt.contains("Content description:"), "absent fields are omitted, not left blank")
    }

    @Test
    fun momentRequestAlwaysCentersOnTheScrubPosition() {
        val request = explainMomentRequest(
            investigationId = "bug-3",
            eventId = "action-11",
            playbackMillis = 9_000L,
            momentSummary = "Tapped Checkout",
        )
        assertEquals(ContextualActionKind.ExplainMoment, request.provenance.sourceKind)
        assertTrue(request.hasEvidence)
        assertEquals("action-11", request.evidence?.focusedEventId)
        assertEquals(9_000L, request.evidence?.centerMillis)
        assertEquals(DefaultMomentRadiusMillis, request.evidence?.windowRadiusMillis)
        assertTrue(request.promptDraft().contains("Tapped Checkout"))
    }

    @Test
    fun investigateSelectionCarriesEveryChosenEventId() {
        val request = investigateSelectionRequest(
            investigationId = "bug-3",
            eventIds = listOf("action-11", "net-2", "crash-1"),
            playbackMillis = 9_000L,
            selectionSummary = "3 event(s): Action×1, NetworkExchange×1, Crash×1",
        )
        assertEquals(ContextualActionKind.InvestigateSelection, request.provenance.sourceKind)
        assertEquals(listOf("action-11", "net-2", "crash-1"), request.evidence?.eventIds)
        assertEquals("action-11", request.provenance.eventId)
        assertEquals(DefaultSelectionRadiusMillis, request.evidence?.windowRadiusMillis)
    }

    @Test
    fun liveDeviceActionsAttachAndyMcpAndSavedInvestigationsDoNot() {
        assertTrue(ContextualActionKind.ExplainCrash.attachesAndyMcpByDefault())
        assertTrue(ContextualActionKind.ExplainRequest.attachesAndyMcpByDefault())
        assertTrue(ContextualActionKind.ExplainNode.attachesAndyMcpByDefault())
        assertFalse(ContextualActionKind.ExplainMoment.attachesAndyMcpByDefault())
        assertFalse(ContextualActionKind.InvestigateSelection.attachesAndyMcpByDefault())
    }

    @Test
    fun oversizedExcerptsStayWithinThePromptBudget() {
        val request = explainCrashRequest(
            crashId = "crash-9",
            packageName = "com.example.garden",
            summary = "FATAL",
            crashText = "x".repeat(50_000),
        )
        val prompt = request.promptDraft()
        assertTrue(prompt.length <= 2_100, "prompt should stay compact, was ${prompt.length} chars")
        assertTrue(prompt.contains("crash=crash-9"), "provenance must survive the excerpt cap")
    }

    @Test
    fun requestsWithNoInlineDetailHaveNoExcerpt() {
        val request = explainNodeRequest(
            nodeId = "node-1",
            className = null,
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = null,
        )
        assertNull(request.contextExcerpt)
        assertEquals(request.question, request.questionWithExcerpt())
    }
}
