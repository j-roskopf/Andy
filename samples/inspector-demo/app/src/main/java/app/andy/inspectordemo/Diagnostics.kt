package app.andy.inspectordemo

import android.util.Log

/**
 * Deterministic crash and log helpers for Andy's Logcat → Crashes panel and retrace pipeline.
 * Stack frames intentionally reference this package so dropbox entries are easy to spot.
 */
object Diagnostics {
    private const val LogTag = "InspectorDemo"

    fun logSampleError() {
        val nested = IllegalStateException("Nested cause for logcat stack trace")
        Log.e(LogTag, "Sample ERROR log — open Logcat in Andy and filter tag:InspectorDemo", nested)
    }

    fun triggerJavaCrash() {
        crashFromNestedCaller("Java crash from nested caller")
    }

    fun triggerKotlinCrash() {
        val label: String? = null
        // Deliberate NPE with a Kotlin-specific frame in the stack trace.
        checkNotNull(label) { "Inspector demo Kotlin null crash (check Logcat → Crashes)" }
    }

    /** Blocks the UI thread long enough to trigger the system ANR dialog on most devices. */
    fun triggerAnr() {
        Thread.sleep(20_000)
    }

    private fun crashFromNestedCaller(message: String) {
        throw IllegalStateException(message)
    }
}
