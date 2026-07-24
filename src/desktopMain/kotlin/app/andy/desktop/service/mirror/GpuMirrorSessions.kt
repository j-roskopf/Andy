package app.andy.desktop.service.mirror

/**
 * Binds one [GpuMirrorPipeline] per device serial (or other stable stream key).
 *
 * Pipelines are **reference counted**: Live and a same-device pop-out both hold the same decode
 * session (fan-out), so the pipeline must survive until the *last* holder releases it. A single
 * holder tearing it down — e.g. Live switching devices — used to blank every other presenter that
 * shared the serial.
 */
internal object GpuMirrorSessions {
    private class Entry(val pipeline: GpuMirrorPipeline, var refCount: Int)

    private val lock = Any()
    private val pipelines = HashMap<Any, Entry>()

    fun get(key: Any): GpuMirrorPipeline? = synchronized(lock) { pipelines[key]?.pipeline }

    /**
     * Returns the existing pipeline (incrementing its holder count) or creates a fresh one with a
     * single holder. Every successful [acquire] must be balanced by exactly one [release].
     */
    fun acquire(key: Any): GpuMirrorPipeline? = synchronized(lock) {
        pipelines[key]?.let { entry ->
            entry.refCount++
            return entry.pipeline
        }
        val pipeline = GpuMirrorPipeline.create() ?: return null
        pipelines[key] = Entry(pipeline, 1)
        pipeline
    }

    /** Drops one holder; the pipeline is closed only when the final holder releases it. */
    fun release(key: Any) {
        val closing = synchronized(lock) {
            val entry = pipelines[key] ?: return
            entry.refCount--
            if (entry.refCount > 0) return
            pipelines.remove(key)
            entry.pipeline
        }
        closing.close()
    }

    /**
     * Test/helper: force-replaces any existing pipeline with a fresh single-holder one. Production
     * code must use [acquire] so it participates in reference counting.
     */
    fun createAndBind(key: Any): GpuMirrorPipeline? {
        val previous = synchronized(lock) { pipelines.remove(key)?.pipeline }
        previous?.close()
        val pipeline = GpuMirrorPipeline.create() ?: return null
        synchronized(lock) { pipelines[key] = Entry(pipeline, 1) }
        return pipeline
    }

    fun clear() {
        val closing = synchronized(lock) {
            val snapshot = pipelines.values.map { it.pipeline }
            pipelines.clear()
            snapshot
        }
        closing.forEach { it.close() }
    }
}
