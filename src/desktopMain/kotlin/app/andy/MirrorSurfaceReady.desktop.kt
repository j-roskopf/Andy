package app.andy

import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import java.awt.Window
import kotlinx.coroutines.delay

internal actual suspend fun awaitMirrorSurfaceReady(timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (hasMirrorHost()) return true
        delay(16)
    }
    return hasMirrorHost()
}

internal actual suspend fun awaitMirrorSurfaceReadyInWindow(window: Any?, timeoutMs: Long): Boolean {
    val awtWindow = window as? Window ?: return awaitMirrorSurfaceReady(timeoutMs)
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (hasMirrorHostInWindow(awtWindow)) return true
        delay(16)
    }
    return hasMirrorHostInWindow(awtWindow)
}

private fun hasMirrorHost(): Boolean =
    GpuMirrorHostRegistry.current() != null || NativeMirrorHostRegistry.current() != null

private fun hasMirrorHostInWindow(window: Window): Boolean =
    GpuMirrorHostRegistry.hostInWindow(window) != null ||
        NativeMirrorHostRegistry.hostInWindow(window) != null
