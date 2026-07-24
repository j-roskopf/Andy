package app.andy.model

/**
 * Live session badge for an embedded agent terminal.
 *
 * Distinct from [AgentTaskStatus], which tracks Andy's task lifecycle
 * (queued/running/completed/…). These four states power per-tab badges and
 * OS notifications (herdr parity): fire on [Blocked] and [Done].
 */
enum class AgentSessionStatus {
    /** Hook-working or PTY output churn. */
    Working,

    /** Hook-idle or quiescent at a prompt. */
    Idle,

    /** Hook-blocked or scrape matched an approval/question UI. */
    Blocked,

    /**
     * Process exited or workflow phase finished while the tab is unseen.
     * Reverts to [Idle] once the user views the tab.
     */
    Done,
}
