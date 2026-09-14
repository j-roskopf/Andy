package app.andy.service

import app.andy.model.ComputerAction
import app.andy.model.ComputerActionResult
import app.andy.model.ComputerUseActionLogEntry
import app.andy.model.ComputerUseArmRequest
import app.andy.model.ComputerUseCapabilities
import app.andy.model.ComputerUseGrantProfile
import app.andy.model.ComputerUseHudState
import app.andy.model.ComputerUseScope
import app.andy.model.HostAccessibilityElement
import app.andy.model.HostAccessibilityTree
import app.andy.model.HostScreenshotResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Host desktop computer-use: accessibility dump, element actuation, and synthetic input.
 * Executes in the Andy GUI process (TCC attribution + non-headless AWT).
 */
interface ComputerUseService {
    val hud: StateFlow<ComputerUseHudState?>
    val actionLog: StateFlow<List<ComputerUseActionLogEntry>>

    /** Attended arm requests awaiting an explicit user decision in the HUD. */
    val pendingArm: StateFlow<ComputerUseArmRequest?>

    suspend fun capabilities(): ComputerUseCapabilities

    /**
     * Request arming for a run. [attended] true blocks until the user approves (or
     * [armDecisionTimeoutMillis] elapses, which denies); unattended requires a matching
     * grant profile (Phase 4).
     */
    suspend fun requestControl(
        scope: ComputerUseScope,
        attended: Boolean = true,
        profileId: String? = null,
        wallClockCapSeconds: Int? = null,
        ownerTaskId: String? = null,
    ): ComputerUseArmResult

    /** Accept or decline the pending attended arm request. */
    fun decideArm(requestId: String, accepted: Boolean)

    suspend fun releaseControl(sessionId: String? = null): ComputerActionResult

    /** Instant revoke (panic hotkey / HUD stop). */
    fun panic(reason: String = "panic")

    suspend fun dump(
        appName: String? = null,
        menus: Boolean = false,
    ): HostAccessibilityTree

    suspend fun findElements(
        query: String,
        role: String? = null,
        limit: Int = 20,
    ): List<HostAccessibilityElement>

    suspend fun screenshot(appName: String? = null): HostScreenshotResult

    suspend fun act(action: ComputerAction, confirmHighConsequence: Boolean = false): ComputerActionResult

    /** Confirm a previously deferred high-consequence action (attended only). */
    suspend fun confirmPendingAction(): ComputerActionResult

    /** Dismiss a previously deferred high-consequence action without executing it. */
    suspend fun discardPendingAction(): ComputerActionResult
}

sealed interface ComputerUseArmResult {
    data class Armed(val sessionId: String) : ComputerUseArmResult
    data class Denied(val reason: String) : ComputerUseArmResult
    /** Attended arming waiting for the user to accept in the HUD. */
    data class PendingUser(val requestId: String, val scope: ComputerUseScope) : ComputerUseArmResult
}
