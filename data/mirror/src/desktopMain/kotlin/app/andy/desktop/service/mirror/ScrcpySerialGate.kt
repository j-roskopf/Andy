package app.andy.desktop.service.mirror

import app.andy.service.MirrorEngine
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Android allows only one healthy scrcpy MediaCodec capture per serial. Dock Live tabs, the main
 * Live destination, and pop-outs can briefly race during handoff; two `app_process` servers (or two
 * feeders into one VideoToolbox decoder) produce the classic macroblock / ghosted Live image.
 *
 * Hold [withExclusive] for the entire video-loop lifetime so a second engine waits instead of
 * interleaving H.264 access units. The dock-pause path still drops its pool hold; this gate is the
 * last line of defense when that race still overlaps.
 */
object ScrcpySerialGate {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val deviceMutexes = ConcurrentHashMap<String, Mutex>()
    private val capturingStreams = ConcurrentHashMap.newKeySet<String>()

    private fun mutexFor(streamKey: String): Mutex =
        mutexes.getOrPut(streamKey) { Mutex() }

    private fun deviceMutexFor(serial: String): Mutex =
        deviceMutexes.getOrPut(serial) { Mutex() }

    /**
     * Runs [block] while holding [serial]'s default display. Kept for callers that mirror display 0.
     */
    suspend fun <T> withExclusive(
        serial: String,
        onWaiting: (() -> Unit)? = null,
        block: suspend () -> T,
    ): T = withExclusive(serial, serial, onWaiting, block)

    /**
     * Runs [block] while holding one display stream ([streamKey] = serial + display id). If another
     * session owns the same stream, invokes [onWaiting] once then suspends until it unlocks.
     *
     * Different displays of the same device use different keys, so a dual-screen device can run
     * one scrcpy server per panel.
     */
    suspend fun <T> withExclusive(
        streamKey: String,
        serial: String,
        onWaiting: (() -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        val mutex = mutexFor(streamKey)
        if (!mutex.tryLock()) {
            onWaiting?.invoke()
            mutex.lock()
        }
        try {
            return block()
        } finally {
            capturingStreams -= streamKey
            mutex.unlock()
        }
    }

    /**
     * Serializes first-time device setup per serial and records that [streamKey] is capturing.
     *
     * [cleanup] (orphan-server pkill) runs only when no other display of the same device is
     * already capturing. The check-and-mark is guarded by a per-device lock, so two displays that
     * start at the same time clean up exactly once instead of both skipping or both killing the
     * other's server. Per-display capture exclusivity still comes from [withExclusive].
     */
    suspend fun withDeviceStartup(streamKey: String, serial: String, cleanup: suspend () -> Unit) {
        deviceMutexFor(serial).lock()
        try {
            val otherCapturing = capturingStreams.any {
                it != streamKey && app.andy.model.MirrorTarget.serial(it) == serial
            }
            if (!otherCapturing) cleanup()
            capturingStreams += streamKey
        } finally {
            deviceMutexFor(serial).unlock()
        }
    }

    /** Test helper: true when no coroutine currently holds [serial]'s default display. */
    fun isAvailable(serial: String): Boolean = !mutexFor(serial).isLocked
}

/**
 * adb argv that best-effort stops leftover on-device scrcpy servers for [serial].
 * Safe when none are running (pkill exits non-zero).
 */
fun killOrphanedScrcpyServerCommand(adb: String, serial: String): List<String> =
    listOf(adb, "-s", serial, "shell", "pkill", "-f", "com.genymobile.scrcpy.Server")

/**
 * Dock Live panes must not start a second scrcpy for a serial the primary Live engine still
 * owns (warm session after leaving the Live destination). Sharing [primary] keeps video *and*
 * the control socket; a pooled engine would attach to the GPU fan-out (picture works) while its
 * video loop blocked on [ScrcpySerialGate] — touches then no-op without scrcpy control.
 */
fun resolveDockLiveMirror(
    targetId: String,
    primarySerial: String?,
    primary: MirrorEngine,
    pooled: MirrorEngine?,
): MirrorEngine? =
    if (primarySerial == targetId) primary else pooled
