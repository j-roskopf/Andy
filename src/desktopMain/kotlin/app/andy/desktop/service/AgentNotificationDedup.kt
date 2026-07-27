package app.andy.desktop.service

/**
 * Previously suppressed duplicate OS notifications within a 20s window when Andy's
 * status coordinator and KetraTerm OSC 9/777 fired for the same session. That window
 * also blocked legitimate sequential Blocked/Done alerts (permission → work →
 * permission). Dedup is intentionally a no-op now — every attention event may notify.
 */
internal object AgentNotificationDedup {
    fun shouldSuppress(key: String): Boolean = false

    fun markNotified(key: String) = Unit

    /** Always allows the notification. */
    fun tryMarkNotified(taskId: String, kind: String? = null): Boolean = true

    internal fun clearForTests() = Unit
}
