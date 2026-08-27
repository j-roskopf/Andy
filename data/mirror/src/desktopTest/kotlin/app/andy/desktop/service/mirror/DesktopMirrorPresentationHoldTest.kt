package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.SdkLocator
import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Presentation holds decide whether the engine converts and paints frames, so the handoff window
 * between two Live surfaces has to survive a dispose that lands before the next compose.
 */
class DesktopMirrorPresentationHoldTest {
    @Test
    fun releasingTheLastHoldPausesPresentation() = runBlocking {
        val engine = newEngine()
        engine.acquirePresentation()
        assertTrue(engine.presenting.value)

        engine.releasePresentation()
        withTimeout(5_000) { engine.presenting.first { !it } }
        assertFalse(engine.presenting.value, "the last release must pause presentation")
    }

    @Test
    fun handingPresentationToAnotherSurfaceNeverPauses() = runBlocking {
        val engine = newEngine()
        engine.acquirePresentation()

        // Live leaves composition, then Design's surface composes a moment later.
        engine.releasePresentation()
        engine.acquirePresentation()
        delay(1_500)

        assertTrue(engine.presenting.value, "a surface handoff must not blank the mirror")
    }

    private fun newEngine(): DesktopMirrorEngine {
        val runner = CommandRunner { _, _ -> CommandResult.success("") }
        val store = object : WorkspaceStore {
            override suspend fun load() = WorkspaceState()
            override suspend fun save(state: WorkspaceState) = Unit
        }
        return DesktopMirrorEngine(runner, DesktopDeviceService(runner, SdkLocator(), store))
    }
}
