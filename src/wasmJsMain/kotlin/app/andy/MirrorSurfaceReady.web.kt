package app.andy

internal actual suspend fun awaitMirrorSurfaceReady(timeoutMs: Long): Boolean = true

internal actual suspend fun awaitMirrorSurfaceReadyInWindow(window: Any?, timeoutMs: Long): Boolean = true
