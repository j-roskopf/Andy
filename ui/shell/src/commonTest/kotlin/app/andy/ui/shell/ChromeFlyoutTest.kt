package app.andy.ui.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromeFlyoutTest {
    @Test
    fun `aligned content puts a label directly beneath its trigger`() {
        assertEquals(
            192.dp,
            chromeFlyoutContentStart(
                anchorX = 200.dp,
                contentWidth = 260.dp,
                hostWidth = 800.dp,
                contentAnchorInset = 8.dp,
            ),
        )
    }

    @Test
    fun `right-edge tab trigger keeps its full menu inside the panel`() {
        assertEquals(
            300.dp,
            chromeFlyoutContentStart(
                anchorX = 584.dp,
                contentWidth = 280.dp,
                hostWidth = 600.dp,
                contentAnchorInset = 8.dp,
            ),
        )
    }
}
