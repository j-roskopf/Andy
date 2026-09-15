package app.andy.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanModelsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun defaultLanesHaveRoles() {
        val lanes = defaultKanbanLanes()
        assertEquals(KanbanLaneRole.Todo, lanes[0].role)
        assertEquals(KanbanLaneRole.Doing, lanes[1].role)
        assertEquals(KanbanLaneRole.Done, lanes[2].role)
        assertTrue(lanes.none { it.role == KanbanLaneRole.Blocked })
    }

    @Test
    fun deserializesPreMigrationBoardJsonWithoutRoles() {
        val legacy = """
            {
              "lanes": [
                {"id":"todo","name":"To-Do","cards":[]},
                {"id":"doing","name":"Doing","cards":[]},
                {"id":"done","name":"Done","cards":[]}
              ]
            }
        """.trimIndent()
        val board = json.decodeFromString(KanbanBoard.serializer(), legacy)
        assertNull(board.lanes[0].role)
        val backfilled = board.withBackfilledLaneRoles()
        assertEquals(KanbanLaneRole.Todo, backfilled.lanes[0].role)
        assertEquals(KanbanLaneRole.Doing, backfilled.lanes[1].role)
        assertEquals(KanbanLaneRole.Done, backfilled.lanes[2].role)
    }

    @Test
    fun deserializesNewCardFieldsWithDefaults() {
        val legacyCard = """
            {
              "lanes": [{
                "id":"todo","name":"To-Do","cards":[{
                  "id":"c1","title":"Work","createdAtMillis":1,"updatedAtMillis":1
                }]
              }]
            }
        """.trimIndent()
        val board = json.decodeFromString(KanbanBoard.serializer(), legacyCard)
        val card = board.lanes.single().cards.single()
        assertNull(card.parentCardId)
        assertEquals(false, card.lanePinned)
        assertNull(card.swarmRunId)
    }

    @Test
    fun mirrorLaneForBlockedFallsBackToDoing() {
        val board = KanbanBoard()
        assertEquals("doing", board.mirrorLaneFor(KanbanLaneRole.Blocked)?.id)
        val withBlocked = board.copy(
            lanes = board.lanes + KanbanLane(id = "blocked", name = "Blocked", role = KanbanLaneRole.Blocked),
        )
        assertEquals("blocked", withBlocked.mirrorLaneFor(KanbanLaneRole.Blocked)?.id)
    }
}
