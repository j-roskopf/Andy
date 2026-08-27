package app.andy.desktop.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Suppresses duplicate attention alerts for the same task + kind within a short window.
 * Scrape flicker (Done → brief Working → Done) used to double-ding when this was a no-op.
 *
 * A short window still allows legitimate sequences (Blocked → Done → Blocked) because
 * each uses a different kind key. Same-kind repeats after the window (e.g. two quick
 * turns) are rare enough to notify twice.
 */
object AgentNotificationDedup {
    private val recentByKey = ConcurrentHashMap<String, Long>()
    private const val SAME_KIND_WINDOW_MS = 5_000L

    fun shouldSuppress(key: String): Boolean {
        val last = recentByKey[key] ?: return false
        return System.currentTimeMillis() - last < SAME_KIND_WINDOW_MS
    }

    fun markNotified(key: String) {
        recentByKey[key] = System.currentTimeMillis()
    }

    /** Returns false when this task+kind was already notified within [SAME_KIND_WINDOW_MS]. */
    fun tryMarkNotified(taskId: String, kind: String? = null): Boolean {
        val key = if (kind != null) "$taskId:$kind" else taskId
        if (shouldSuppress(key)) return false
        markNotified(key)
        return true
    }

    fun clearForTests() {
        recentByKey.clear()
    }
}
