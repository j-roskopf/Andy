package app.andy.model

import kotlin.test.Test
import kotlin.test.assertTrue

class ContextualPromptBuilderTest {
    private val provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.ExplainCrash,
        investigationId = "bug-1",
        eventId = "crash-1",
        playbackMillis = 4200L,
        crashId = "c1",
        packageName = "com.example.garden",
    )

    @Test
    fun promptIncludesQuestionAndProvenance() {
        val prompt = buildContextualPrompt(
            question = "Why did this crash happen?",
            provenance = provenance,
        )
        assertTrue(prompt.contains("Why did this crash happen?"))
        assertTrue(prompt.contains("investigation=bug-1"))
        assertTrue(prompt.contains("event=crash-1"))
        assertTrue(prompt.contains("crash=c1"))
        assertTrue(prompt.contains("package=com.example.garden"))
        assertTrue(prompt.contains("kind=${ContextualActionKind.ExplainCrash.name}"))
    }

    @Test
    fun promptIncludesTimelineSummaryWhenPresent() {
        val prompt = buildContextualPrompt(
            question = "What happened here?",
            provenance = provenance,
            timelineSummary = "6 event(s): Crash×1, NetworkExchange×2",
        )
        assertTrue(prompt.contains("6 event(s): Crash×1, NetworkExchange×2"))
    }

    @Test
    fun promptIncludesManagedEvidenceBundlePaths() {
        val bundle = ManagedEvidenceBundle(
            id = "evidence-1",
            rootRelativePath = ".andy/evidence/evidence-1",
            manifest = listOf(
                EvidenceArtifactManifestEntry("events/crashes/crash-1.json", "crash", 512L, redacted = false),
                EvidenceArtifactManifestEntry("events/network/network-1.json", "network", 256L, redacted = true),
            ),
            redactionReport = RedactionReport(redactedHeaderNames = listOf("Authorization")),
            investigationId = "bug-1",
            eventId = "crash-1",
        )
        val prompt = buildContextualPrompt(
            question = "Explain this crash.",
            provenance = provenance,
            bundle = bundle,
        )
        assertTrue(prompt.contains("evidence-1"))
        assertTrue(prompt.contains(".andy/evidence/evidence-1"))
        assertTrue(prompt.contains("events/crashes/crash-1.json"))
        assertTrue(prompt.contains("events/network/network-1.json"))
        assertTrue(prompt.contains("redacted"))
    }

    @Test
    fun promptStaysCompactEvenWithLargeInputs() {
        val bigManifest = (1..500).map { index ->
            EvidenceArtifactManifestEntry("events/network/network-$index.json", "network", 128L)
        }
        val bundle = ManagedEvidenceBundle(
            id = "evidence-big",
            rootRelativePath = ".andy/evidence/evidence-big",
            manifest = bigManifest,
            investigationId = "bug-1",
        )
        val prompt = buildContextualPrompt(
            question = "x".repeat(5_000),
            provenance = provenance,
            timelineSummary = "y".repeat(5_000),
            bundle = bundle,
        )
        assertTrue(prompt.length <= 2_100, "prompt should stay compact, was ${prompt.length} chars")
    }

    @Test
    fun promptOmitsBlankSectionsGracefully() {
        val prompt = buildContextualPrompt(
            question = "  ",
            provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.ExplainNode),
        )
        assertTrue(prompt.contains("kind=${ContextualActionKind.ExplainNode.name}"))
    }
}
