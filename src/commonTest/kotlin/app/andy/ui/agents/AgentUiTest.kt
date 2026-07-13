package app.andy.ui.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentUiTest {
    @Test
    fun formatsCostsWithoutFloatingPointTruncation() {
        assertEquals("$0.5700", formatCost(0.57))
        assertEquals("~$0.0421", formatCost(0.0421, estimated = true))
        assertNull(formatCost(Double.NaN))
    }
}
