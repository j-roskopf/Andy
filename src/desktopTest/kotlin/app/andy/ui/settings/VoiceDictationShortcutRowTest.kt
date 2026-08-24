package app.andy.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import app.andy.ui.components.KeyCombo
import app.andy.ui.theme.AndyTheme
import org.junit.Rule
import org.junit.Test

@OptIn(InternalComposeUiApi::class)
class VoiceDictationShortcutRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun pill() = composeRule.onNodeWithContentDescription("Voice dictation shortcut")

    @Test
    fun bareModifierKeepsCapturingUntilARealKeyArrives() {
        var captured: KeyCombo? = null
        composeRule.setContent {
            AndyTheme {
                var shortcut by remember { mutableStateOf<KeyCombo?>(null) }
                VoiceDictationShortcutRow(
                    shortcut = shortcut,
                    onChange = {
                        captured = it
                        shortcut = it
                    },
                )
            }
        }

        pill().performClick()
        composeRule.onNodeWithText("press keys…").assertExists()

        pill().performKeyPress(
            KeyEvent(key = Key.CtrlLeft, type = KeyEventType.KeyDown, isCtrlPressed = true),
        )
        composeRule.waitForIdle()

        // A bare modifier must not cancel capture or commit a combo.
        composeRule.onNodeWithText("Ctrl+…").assertExists()
        assert(captured == null) { "modifier-only keydown should not commit a shortcut" }

        pill().performKeyPress(
            KeyEvent(key = Key.M, type = KeyEventType.KeyDown, isCtrlPressed = true),
        )
        composeRule.waitForIdle()

        assert(captured == KeyCombo(Key.M, ctrl = true)) { "expected Ctrl+M, got $captured" }
        composeRule.onNodeWithText("Ctrl+M").assertExists()
    }

    @Test
    fun escapeCancelsCapture() {
        composeRule.setContent {
            AndyTheme {
                VoiceDictationShortcutRow(shortcut = null, onChange = {})
            }
        }

        pill().performClick()
        composeRule.onNodeWithText("press keys…").assertExists()

        pill().performKeyPress(KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("not set").assertExists()
    }
}
