package app.andy.ui.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewVerdictHeadlineTest {
    @Test
    fun includesAttemptAndOmitsSingleGenerationOne() {
        assertEquals(
            "Latest · changes requested · attempt 2\nPostal code still slips through.",
            reviewVerdictHeadline(
                statusLabel = "changes requested",
                summary = "Postal code still slips through.",
                attempt = 2,
                reviewGeneration = 1,
                latest = true,
                showGeneration = false,
            ),
        )
    }

    @Test
    fun includesGenerationWhenShown() {
        assertEquals(
            "approved · attempt 1 · generation 2\nLooks good after recovery.",
            reviewVerdictHeadline(
                statusLabel = "approved",
                summary = "Looks good after recovery.",
                attempt = 1,
                reviewGeneration = 2,
                latest = false,
                showGeneration = true,
            ),
        )
    }
}
