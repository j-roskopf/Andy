package app.andy.domain

import app.andy.model.EvidenceArtifactManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceRedactorTest {
    @Test
    fun redactHeadersStripsKnownSensitiveNamesCaseInsensitively() {
        val result = redactHeaders(
            mapOf(
                "Authorization" to "Bearer abc123",
                "Cookie" to "session=xyz",
                "X-Api-Key" to "sk-live-1234",
                "Content-Type" to "application/json",
            ),
        )
        assertEquals("[redacted]", result.headers["Authorization"])
        assertEquals("[redacted]", result.headers["Cookie"])
        assertEquals("[redacted]", result.headers["X-Api-Key"])
        assertEquals("application/json", result.headers["Content-Type"])
        assertTrue("Authorization" in result.redactedNames)
        assertTrue("Cookie" in result.redactedNames)
        assertTrue("X-Api-Key" in result.redactedNames)
        assertFalse("Content-Type" in result.redactedNames)
    }

    @Test
    fun redactHeadersStripsBearerLikeValuesEvenWithUnknownHeaderName() {
        val result = redactHeaders(mapOf("X-Custom-Auth" to "Bearer sk-live-should-not-leak"))
        assertEquals("[redacted]", result.headers["X-Custom-Auth"])
        assertTrue("X-Custom-Auth" in result.redactedNames)
    }

    @Test
    fun capTextTruncatesLongTextAndNotesTruncation() {
        val long = "x".repeat(100)
        val capped = capText(long, 10)
        assertEquals(10 + "…[truncated 90 chars]".length, capped.length)
        assertTrue(capped.startsWith("x".repeat(10)))
    }

    @Test
    fun capTextLeavesShortTextUntouched() {
        assertEquals("short", capText("short", 100))
    }

    @Test
    fun redactHierarchyJsonStripsTextFromPasswordNodes() {
        val json = """
            {"className":"android.widget.EditText","resourceId":"pwd","text":"hunter2","contentDescription":"password field","password":true,"children":[]}
        """.trimIndent()
        val result = redactHierarchyJson(json)
        assertEquals(1, result.redactedNodeCount)
        assertFalse(result.json.contains("hunter2"))
        assertFalse(result.json.contains("password field"))
        assertTrue(result.json.contains("\"password\":true") || result.json.contains("\"password\": true"))
    }

    @Test
    fun redactHierarchyJsonLeavesNonPasswordNodesIntact() {
        val json = """{"className":"android.widget.TextView","text":"My garden","children":[]}"""
        val result = redactHierarchyJson(json)
        assertEquals(0, result.redactedNodeCount)
        assertTrue(result.json.contains("My garden"))
    }

    @Test
    fun redactHierarchyJsonRedactsNestedPasswordChildren() {
        val json = """
            {"className":"root","children":[
                {"className":"android.widget.EditText","text":"secret1","password":true,"children":[]},
                {"className":"android.widget.TextView","text":"visible","children":[]}
            ]}
        """.trimIndent()
        val result = redactHierarchyJson(json)
        assertEquals(1, result.redactedNodeCount)
        assertFalse(result.json.contains("secret1"))
        assertTrue(result.json.contains("visible"))
    }

    @Test
    fun applyBudgetExcludesArtifactsBeyondImageBudget() {
        val screenshots = (1..EvidenceBudgets.MaxImages + 2).map { index ->
            EvidenceArtifactManifestEntry("screenshots/$index.png", "screenshot", sizeBytes = 100L)
        }
        val result = applyBudget(screenshots)
        assertEquals(EvidenceBudgets.MaxImages, result.included.size)
        assertEquals(2, result.exclusions.size)
    }

    @Test
    fun applyBudgetExcludesArtifactsBeyondTotalByteBudget() {
        val artifacts = listOf(
            EvidenceArtifactManifestEntry("a.json", "network", sizeBytes = EvidenceBudgets.MaxTotalBundleBytes - 10),
            EvidenceArtifactManifestEntry("b.json", "network", sizeBytes = 100L),
        )
        val result = applyBudget(artifacts)
        assertEquals(1, result.included.size)
        assertEquals("a.json", result.included.single().relativePath)
        assertEquals(1, result.exclusions.size)
        assertTrue(result.exclusions.single().contains("b.json"))
    }

    @Test
    fun applyBudgetKeepsArtifactsWithinBudget() {
        val artifacts = listOf(
            EvidenceArtifactManifestEntry("a.json", "network", sizeBytes = 1_000L),
            EvidenceArtifactManifestEntry("b.json", "crash", sizeBytes = 2_000L),
        )
        val result = applyBudget(artifacts)
        assertEquals(artifacts, result.included)
        assertTrue(result.exclusions.isEmpty())
    }
}
