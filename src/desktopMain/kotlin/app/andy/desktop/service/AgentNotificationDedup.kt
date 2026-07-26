package app.andy.desktop.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents duplicate OS notifications when Andy's status coordinator and a
 * KetraTerm OSC 9/777 terminal notification fire for the same agent session.
 */
internal object AgentNotificationDedup {
    private val recentByKey = ConcurrentHashMap<String, Long>()
    private const val WINDOW_MS = 20_000L

    fun shouldSuppress(key: String): Boolean {
        val last = recentByKey[key] ?: return false
        return System.currentTimeMillis() - last < WINDOW_MS
    }

    fun markNotified(key: String) {
        recentByKey[key] = System.currentTimeMillis()
    }

    /** Returns false when this notification was already sent recently. */
    fun tryMarkNotified(taskId: String, kind: String? = null): Boolean {
        if (kind != null) {
            val kindKey = "$taskId:$kind"
            if (shouldSuppress(kindKey)) return false
            markNotified(kindKey)
            markNotified(taskId)
            return true
        }
        if (shouldSuppress(taskId)) return false
        markNotified(taskId)
        return true
    }

    internal fun clearForTests() {
        recentByKey.clear()
    }
}
