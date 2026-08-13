package app.andy.ui.actions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanCompletedLaneTest {
    @Test
    fun matchesDoneIdAndWholeWordLabels() {
        assertTrue(isCompletedKanbanLane("done", "Anything"))
        assertTrue(isCompletedKanbanLane("lane-1", "Done"))
        assertTrue(isCompletedKanbanLane("lane-1", "Completed work"))
        assertTrue(isCompletedKanbanLane("lane-1", "Finished"))
    }

    @Test
    fun rejectsUnfinishedAndSubstringTraps() {
        assertFalse(isCompletedKanbanLane("lane-1", "Incomplete"))
        assertFalse(isCompletedKanbanLane("lane-1", "Undone"))
        assertFalse(isCompletedKanbanLane("lane-1", "Not done"))
        assertFalse(isCompletedKanbanLane("lane-1", "Not completed"))
        assertFalse(isCompletedKanbanLane("lane-1", "Backlog"))
        assertFalse(isCompletedKanbanLane("lane-1", ""))
    }
}
