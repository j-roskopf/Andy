package app.andy.ui.components

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.andy.service.UnavailableVoiceDictationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class ActiveVoiceDictationShortcutTest {
    @Test
    fun handleRequiresBoundControllerAndMatchingShortcut() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = VoiceDictationController(
            voice = UnavailableVoiceDictationService,
            scope = scope,
            onText = {},
            onError = {},
        )
        val shortcut = KeyCombo(Key.E, meta = true)
        val event = KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isMetaPressed = true)

        assertFalse(ActiveVoiceDictationShortcut.handle(event, shortcut))

        ActiveVoiceDictationShortcut.bind(controller)
        try {
            assertTrue(ActiveVoiceDictationShortcut.handle(event, shortcut))
            assertFalse(
                ActiveVoiceDictationShortcut.handle(
                    KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isCtrlPressed = true),
                    shortcut,
                ),
            )
            assertFalse(ActiveVoiceDictationShortcut.handle(event, null))
        } finally {
            ActiveVoiceDictationShortcut.unbind(controller)
        }
    }
}
