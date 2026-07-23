package app.andy

/** Waits until a desktop mirror host surface is realized, or [timeoutMs] elapses. */
internal expect suspend fun awaitMirrorSurfaceReady(timeoutMs: Long = 2_000): Boolean
