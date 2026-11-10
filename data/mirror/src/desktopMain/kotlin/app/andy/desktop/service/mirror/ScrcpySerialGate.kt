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
    private val activeStreams = ConcurrentHashMap.newKeySet<String>()

    private fun mutexFor(streamKey: String): Mutex =
        mutexes.getOrPut(streamKey) { Mutex() }

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
        activeStreams += streamKey
        try {
            return block()
        } finally {
            activeStreams -= streamKey
            mutex.unlock()
        }
    }

    /** True when another display stream for [serial] is already capturing. */
    fun hasOtherActiveStream(serial: String, streamKey: String): Boolean =
        activeStreams.any { it != streamKey && app.andy.model.MirrorTarget.serial(it) == serial }

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
