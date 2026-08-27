package app.andy.ui.actions

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.theme.AndyTheme
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class ExternalLinkTabIconTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * A trailing icon taller than the tab label grows its tab, and the bottom-aligned tab
     * row then lifts that label off the baseline the other tabs share.
     */
    @Test
    fun externalLinkTabSitsOnTheSameLineAsPlainTabs() {
        composeRule.setContent {
            AndyTheme {
                TabBarRow {
                    TabBarItem(label = "Worktrees", selected = false, onClick = {})
                    TabBarItem(
                        label = "GitHub",
                        selected = false,
                        onClick = {},
                        trailing = { ExternalLinkTabIcon() },
                    )
                }
            }
        }

        val plain = composeRule.onNodeWithText("Worktrees").getUnclippedBoundsInRoot()
        val external = composeRule.onNodeWithText("GitHub").getUnclippedBoundsInRoot()

        assertTrue(
            abs(plain.top.value - external.top.value) < 0.5f,
            "expected GitHub tab top at ${plain.top}, was ${external.top}",
        )
        assertTrue(
            abs(plain.bottom.value - external.bottom.value) < 0.5f,
            "expected GitHub tab bottom at ${plain.bottom}, was ${external.bottom}",
        )
    }
}
