package app.andy.domain

import app.andy.model.CatalogPinRecord
import app.andy.model.CatalogUploadRecord
import app.andy.model.ProjectCatalogSourceKind
import app.andy.model.ProjectCatalogStore
import app.andy.model.ProjectCatalogTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectArtifactCatalogTest {
    @Test
    fun classifiesMediaByExtension() {
        assertEquals(ProjectCatalogTab.Media, projectCatalogTabForFileName("shot.png"))
        assertEquals(ProjectCatalogTab.Media, projectCatalogTabForFileName("clip.MP4"))
        assertEquals(ProjectCatalogTab.Documents, projectCatalogTabForFileName("plan.md"))
        assertEquals(ProjectCatalogTab.Documents, projectCatalogTabForFileName("review.json"))
    }

    @Test
    fun excludesAndyStatusJson() {
        assertTrue(isCatalogExcludedWorkflowArtifact("status.json"))
        assertFalse(isCatalogExcludedWorkflowArtifact("review.json"))
        assertFalse(isCatalogExcludedWorkflowArtifact("plan.md"))
    }

    @Test
    fun mergeDropsUnlinkedAndMissingIsAbsent() {
        val hits = listOf(
            hit("chat:1", projectId = "p1", title = "a.png"),
            hit("chat:2", projectId = "p1", title = "b.png"),
        )
        val store = ProjectCatalogStore(unlinkedIds = setOf("chat:1"))
        val merged = mergeProjectCatalog(hits, store, "p1") { null }
        assertEquals(listOf("chat:2"), merged.map { it.id })
    }

    @Test
    fun unscopedExcludesAssigned() {
        val hits = listOf(
            hit("rec:1", projectId = null, title = "capture.mp4"),
            hit("rec:2", projectId = null, title = "notes.txt"),
        )
        val store = ProjectCatalogStore(assignments = mapOf("rec:1" to "p1"))
        val unscoped = mergeProjectCatalogForUnscoped(hits, store) { null }
        assertEquals(listOf("rec:2"), unscoped.map { it.id })
        val project = mergeProjectCatalog(
            hits = hits,
            store = ProjectCatalogStore(),
            projectFilter = "p1",
            assignmentLookup = store.assignments,
            durableAbsolutePath = { null },
        )
        assertEquals(listOf("rec:1"), project.map { it.id })
    }

    @Test
    fun pinReplacesLiveRow() {
        val hits = listOf(hit("chat:1", projectId = "p1", title = "a.png", path = "/tmp/a.png"))
        val store = ProjectCatalogStore(
            pins = listOf(
                CatalogPinRecord(
                    sourceId = "chat:1",
                    durableRelativePath = "pin-a.png",
                    pinnedAtMillis = 99L,
                    title = "a.png",
                    tab = ProjectCatalogTab.Media,
                    originalSourceKind = ProjectCatalogSourceKind.ChatAttachment,
                ),
            ),
        )
        val merged = mergeProjectCatalog(hits, store, "p1") { rel ->
            if (rel == "pin-a.png") "/durable/pin-a.png" else null
        }
        assertEquals(1, merged.size)
        assertTrue(merged.single().pinned)
        assertEquals(ProjectCatalogSourceKind.PinnedCopy, merged.single().sourceKind)
        assertEquals("/durable/pin-a.png", merged.single().absolutePath)
    }

    @Test
    fun uploadsAppearAndSurviveWithoutHits() {
        val store = ProjectCatalogStore(
            uploads = listOf(
                CatalogUploadRecord(
                    id = "u1",
                    projectId = "p1",
                    durableRelativePath = "u1-doc.md",
                    title = "doc.md",
                    tab = ProjectCatalogTab.Documents,
                    createdAtMillis = 5L,
                ),
            ),
        )
        val merged = mergeProjectCatalog(emptyList(), store, "p1") { rel ->
            if (rel == "u1-doc.md") "/durable/u1-doc.md" else null
        }
        assertEquals(listOf(uploadCatalogId("u1")), merged.map { it.id })
        assertEquals(ProjectCatalogSourceKind.DirectUpload, merged.single().sourceKind)
    }

    @Test
    fun orphanPinSurvivesMissingSource() {
        val store = ProjectCatalogStore(
            pins = listOf(
                CatalogPinRecord(
                    sourceId = "gone",
                    durableRelativePath = "keep.png",
                    pinnedAtMillis = 1L,
                    title = "keep.png",
                    tab = ProjectCatalogTab.Media,
                    originalSourceKind = ProjectCatalogSourceKind.Recording,
                ),
            ),
        )
        val merged = mergeProjectCatalog(emptyList(), store, "p1") { "/durable/$it" }
        assertEquals(listOf("gone"), merged.map { it.id })
        assertTrue(merged.single().pinned)
    }

    @Test
    fun temporaryHitsAreCallerFiltered() {
        // Merge itself does not know temporary; callers must exclude. Sanity: empty hits → empty.
        assertTrue(mergeProjectCatalog(emptyList(), ProjectCatalogStore(), "p1") { null }.isEmpty())
    }

    @Test
    fun storeHelpers() {
        var store = ProjectCatalogStore()
        store = store.withUnlinked("a")
        assertTrue("a" in store.unlinkedIds)
        store = store.withoutUnlinked("a")
        assertFalse("a" in store.unlinkedIds)
        store = store.withAssignment("x", "p1")
        assertEquals("p1", store.assignments["x"])
    }

    private fun hit(
        id: String,
        projectId: String?,
        title: String,
        path: String = "/tmp/$title",
    ) = ProjectCatalogSourceHit(
        id = id,
        projectId = projectId,
        title = title,
        createdAtMillis = 1L,
        sourceKind = ProjectCatalogSourceKind.ChatAttachment,
        absolutePath = path,
    )
}
