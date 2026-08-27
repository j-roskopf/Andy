package app.andy.domain

import app.andy.model.InvestigationEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationTimelineFilterGroupsTest {
    @Test
    fun emptyFiltersShowEveryGroup() {
        val filters = InvestigationTimelineFilters()
        InvestigationFilterGroup.entries.forEach { group ->
            assertTrue(filters.groupActive(group), "$group should be visible when no filter is set")
        }
    }

    @Test
    fun firstToggleNarrowsDownToJustThatGroup() {
        val narrowed = InvestigationTimelineFilters().withGroupToggled(InvestigationFilterGroup.Crashes)
        assertTrue(narrowed.groupActive(InvestigationFilterGroup.Crashes))
        assertTrue(!narrowed.groupActive(InvestigationFilterGroup.Network))
        assertEquals(setOf(InvestigationEventKind.Crash, InvestigationEventKind.Anr), narrowed.kinds)
    }

    @Test
    fun togglingEveryGroupCollapsesBackToShowAll() {
        var filters = InvestigationTimelineFilters()
        InvestigationFilterGroup.entries.forEach { group -> filters = filters.withGroupToggled(group) }
        assertEquals(InvestigationTimelineFilters(), filters)
    }

    @Test
    fun togglingAnActiveGroupOffRemovesOnlyItsKinds() {
        val onlyNetworkAndCrashes = InvestigationTimelineFilters(
            kinds = InvestigationFilterGroup.Network.kinds + InvestigationFilterGroup.Crashes.kinds,
        )
        val networkRemoved = onlyNetworkAndCrashes.withGroupToggled(InvestigationFilterGroup.Network)
        assertEquals(InvestigationFilterGroup.Crashes.kinds, networkRemoved.kinds)
    }
}
