package app.andy.ui.live

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.andy.ui.theme.AndyTheme
import org.junit.Rule
import org.junit.Test

class DhuLiveUiFixtureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toggleFixtureShowsAndroidAutoLabel() {
        composeRule.setContent { AndyTheme { DhuToggleFixture(enabled = false) } }
        composeRule.onNodeWithText("Android Auto").assertIsDisplayed()
    }

    @Test
    fun statusFixtureShowsSeparateWindowCopy() {
        composeRule.setContent { AndyTheme { DhuStatusFixture() } }
        composeRule.onNodeWithText("Phone mirror").assertIsDisplayed()
        composeRule.onNodeWithText("separate desktop-head-unit window", substring = true).assertIsDisplayed()
    }

    @Test
    fun errorFixtureSurfacesRetryGuidance() {
        composeRule.setContent { AndyTheme { DhuErrorFixture() } }
        composeRule.onNodeWithText("Use Retry or toggle Android Auto again", substring = true).assertIsDisplayed()
    }

    @Test
    fun consoleFixtureShowsProcessOutputAndHelp() {
        composeRule.setContent { AndyTheme { DhuConsoleFixture() } }
        composeRule.onNodeWithText("DHU console").assertIsDisplayed()
        composeRule.onNodeWithText("Help").assertIsDisplayed()
        composeRule.onNodeWithText("Copy diagnostics").assertIsDisplayed()
        composeRule.onNodeWithText("Connecting to Android Auto…").assertIsDisplayed()
        composeRule.onNodeWithText("Focus DHU window", substring = true).assertIsDisplayed()
    }
}
