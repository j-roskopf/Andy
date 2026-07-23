package app.andy

import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import kotlinx.coroutines.delay

internal actual suspend fun awaitMirrorSurfaceReady(timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (NativeMirrorHostRegistry.current() != null) return true
        delay(16)
    }
    return NativeMirrorHostRegistry.current() != null
}
