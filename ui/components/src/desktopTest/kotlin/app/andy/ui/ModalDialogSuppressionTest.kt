package app.andy.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.ui.components.ModalDialogRegistry
import app.andy.ui.theme.AndyTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose dialogs paint below desktop Swing/Metal interop hosts, so every dialog that can open
 * over a terminal or mirror has to drop those hosts out of composition while it is up.
 */
@OptIn(ExperimentalTestApi::class)
class ModalDialogSuppressionTest {
    @Test
    fun dialogSuppressesHeavyweightSurfacesWhileOpen() = runDesktopComposeUiTest(width = 900, height = 700) {
        var open by mutableStateOf(false)
        setContent {
            AndyTheme {
                if (open) {
                    AndyAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { Text("Save") },
                        title = { Text("New project") },
                    )
                }
            }
        }
        waitForIdle()
        assertFalse(ModalDialogRegistry.anyOpen, "no dialog is open yet")

        open = true
        waitForIdle()
        assertTrue(ModalDialogRegistry.anyOpen, "an open dialog must suppress heavyweight surfaces")

        open = false
        waitForIdle()
        assertFalse(ModalDialogRegistry.anyOpen, "closing the dialog must restore heavyweight surfaces")
    }

    @Test
    fun stackedDialogsSuppressUntilTheLastOneCloses() = runDesktopComposeUiTest(width = 900, height = 700) {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        setContent {
            AndyTheme {
                if (first) {
                    AndyAlertDialog(onDismissRequest = {}, confirmButton = { Text("Save") }, title = { Text("First") })
                }
                if (second) {
                    AndyAlertDialog(onDismissRequest = {}, confirmButton = { Text("Save") }, title = { Text("Second") })
                }
            }
        }
        waitForIdle()
        assertTrue(ModalDialogRegistry.anyOpen)

        first = false
        waitForIdle()
        assertTrue(ModalDialogRegistry.anyOpen, "the remaining dialog still needs the surfaces suppressed")

        second = false
        waitForIdle()
        assertFalse(ModalDialogRegistry.anyOpen)
    }
}
