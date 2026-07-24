package app.andy

/** Waits until a desktop mirror host surface is realized, or [timeoutMs] elapses. */
internal expect suspend fun awaitMirrorSurfaceReady(timeoutMs: Long = 2_000): Boolean

/** Waits until a mirror host exists inside [window] (pop-out), or [timeoutMs] elapses. */
internal expect suspend fun awaitMirrorSurfaceReadyInWindow(window: Any?, timeoutMs: Long = 2_000): Boolean
