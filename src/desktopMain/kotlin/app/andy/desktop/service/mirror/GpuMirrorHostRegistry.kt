package app.andy.desktop.service.mirror

import java.awt.Canvas
import java.awt.Window
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** Maps each realized Canvas to its dedicated [GpuMirrorPresenter]. */
internal object GpuMirrorHostRegistry {
    private val presentersByHost = ConcurrentHashMap<Canvas, GpuMirrorPresenter>()
    private val presentersByDecoder = Collections.newSetFromMap(ConcurrentHashMap<GpuMirrorPresenter, Boolean>())

    fun registerPresenter(host: Canvas, presenter: GpuMirrorPresenter) {
        val previous = presentersByHost.put(host, presenter)
        if (previous != null && previous !== presenter) {
            previous.close()
        }
        presentersByDecoder.add(presenter)
    }

    fun unregisterPresenter(host: Canvas) {
        val removed = presentersByHost.remove(host)
        if (removed != null) {
            presentersByDecoder.remove(removed)
        }
    }

    /** Drops a presenter from the decoder index without touching another host's registration. */
    fun forgetPresenter(presenter: GpuMirrorPresenter) {
        presentersByDecoder.remove(presenter)
        presentersByHost.entries.removeIf { it.value === presenter }
    }

    fun presenterFor(host: Canvas): GpuMirrorPresenter? = presentersByHost[host]

    fun current(): Canvas? = presentersByHost.keys.lastOrNull { it.isDisplayable }

    fun hostInWindow(window: Window): Canvas? =
        presentersByHost.keys.lastOrNull { canvas ->
            canvas.isDisplayable && SwingUtilities.getWindowAncestor(canvas) == window
        }

    fun presentersForDecoder(decoderId: Long): List<GpuMirrorPresenter> =
        presentersByDecoder.filter { it.decoderId == decoderId }

    fun pruneOrphanedPresenters(decoderId: Long) {
        val registered = presentersByHost.values.toSet()
        presentersForDecoder(decoderId)
            .filter { it !in registered }
            .toList()
            .forEach { it.close() }
    }

    fun detachDecoder(decoderId: Long) {
        presentersByDecoder.removeIf { presenter ->
            if (presenter.decoderId == decoderId) {
                presentersByHost.entries.removeIf { it.value === presenter }
                true
            } else {
                false
            }
        }
    }

    /** Test-only snapshot of registered hosts. */
    internal fun registeredHostsForTests(): List<Canvas> = presentersByHost.keys.toList()
}
