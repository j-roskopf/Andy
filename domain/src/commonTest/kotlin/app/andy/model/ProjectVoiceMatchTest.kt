package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectVoiceMatchTest {
    private val projects = listOf(
        "phoebe" to "Phoebe",
        "andy" to "Andy",
        "sync" to "Sync",
    )

    @Test
    fun exactMatch() {
        assertEquals("phoebe", matchProject("in the Phoebe project, fix the login", projects))
    }

    @Test
    fun caseInsensitive() {
        assertEquals("andy", matchProject("IN THE ANDY PROJECT ship the release", projects))
    }

    @Test
    fun whisperCorruption() {
        // ggml-base.en often mangles unusual names; one-edit "phebe" should still land.
        assertEquals("phoebe", matchProject("in the phebe project, do X", projects))
    }

    @Test
    fun ambiguousReturnsNull() {
        val twins = listOf(
            "app-ios" to "Mobile",
            "app-android" to "Mobile",
        )
        // Identical display names both clear the threshold → refuse to guess.
        assertNull(matchProject("in the mobile project please", twins))
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(matchProject("refactor the networking layer", projects))
    }

    @Test
    fun shortNameDoesNotFalsePositiveOnCommonVerb() {
        // Project named "sync" must not claim "sync the branches and rebase".
        assertNull(matchProject("sync the branches and rebase", projects))
    }

    @Test
    fun shortNameAcceptedWithExplicitProjectPhrase() {
        assertEquals("sync", matchProject("in the sync project, rebase main", projects))
    }

    @Test
    fun bareLongNameStillMatches() {
        assertEquals("phoebe", matchProject("open phoebe and fix the crash", projects))
    }
}
