package app.andy

internal actual suspend fun awaitMirrorSurfaceReady(timeoutMs: Long): Boolean = true

actual suspend fun awaitMirrorSurfaceReadyInWindow(window: Any?, timeoutMs: Long): Boolean = true
