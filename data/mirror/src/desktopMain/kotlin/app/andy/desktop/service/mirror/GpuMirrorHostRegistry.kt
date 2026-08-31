package app.andy.desktop.service.mirror

import java.awt.Canvas
import java.awt.Window
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** Maps each realized Canvas to its dedicated [GpuMirrorPresenter]. */
object GpuMirrorHostRegistry {
    private val presentersByHost = ConcurrentHashMap<Canvas, GpuMirrorPresenter>()
    private val presentersByDecoder = Collections.newSetFromMap(ConcurrentHashMap<GpuMirrorPresenter, Boolean>())

    fun registerPresenter(host: Canvas, presenter: GpuMirrorPresenter) {
        val previous = presentersByHost.put(host, presenter)
        if (previous != null && previous !== presenter) {
            previous.close()
        }
        presentersByDecoder.add(presenter)
    }

    /** Drops the host mapping only; keeps the presenter warm for tab-switch reattach. */
    fun unregisterPresenter(host: Canvas) {
        presentersByHost.remove(host)
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

    /** Presenter detached from its canvas but still decoding; safe to reattach after tab switches. */
    fun unattachedPresenterForDecoder(decoderId: Long): GpuMirrorPresenter? =
        presentersForDecoder(decoderId).firstOrNull { it !in presentersByHost.values }

    fun anyHostShowing(): Boolean = presentersByHost.keys.any { it.isShowing }

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

    fun attachedPresenters(): List<GpuMirrorPresenter> = presentersByHost.values.distinct()

    fun allPresenters(): List<GpuMirrorPresenter> = presentersByDecoder.toList()

    /** Test-only snapshot of registered hosts. */
    fun registeredHostsForTests(): List<Canvas> = presentersByHost.keys.toList()
}
