package app.andy.ui.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTaskComposerTest {
    @Test
    fun parsesOnlyFiniteNonNegativeBudgets() {
        assertEquals(2.5, " 2.50 ".toMaxBudgetUsd())
        assertEquals(0.0, "0".toMaxBudgetUsd())
        assertNull("-1".toMaxBudgetUsd())
        assertNull("NaN".toMaxBudgetUsd())
        assertNull("Infinity".toMaxBudgetUsd())
        assertNull("not a number".toMaxBudgetUsd())
    }
}
