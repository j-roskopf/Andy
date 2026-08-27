package app.andy.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Android Auto Desktop Head Unit integration. Desktop-only; web uses [UnavailableDhuService].
 *
 * One Live-scoped session at a time. DHU runs as a separate `desktop-head-unit` window
 * (Andy owns process lifecycle + console; no embedded capture or pointer forwarding).
 * Never installs SDK extras or changes Android Auto settings — readiness only guides remediation.
 */
interface DhuService {
    val readiness: StateFlow<DhuReadiness>
    val session: StateFlow<DhuSession?>
    val console: StateFlow<DhuConsoleState>
    /** Always null — embedding is disabled; kept for API stability. */
    val captureFrame: StateFlow<DhuCaptureFrame?>

    /** Re-probe SDK extras, permissions, ADB, and selected device. */
    suspend fun refreshReadiness(serial: String?): DhuReadiness

    /**
     * Start (or replace) the single DHU session for [serial]. Caller starts after mirror
     * connect; DHU owns ADB forward cleanup on stop.
     */
    suspend fun start(serial: String): CommandResult

    /** Stop process and remove ADB forward. */
    suspend fun stop()

    /** Send a one-line DHU console command to the running process stdin. */
    suspend fun sendConsoleCommand(command: String): CommandResult

    /** Open the Android Developers DHU help page in the system browser. */
    fun openHelp()

    /** Focus the running DHU window, or launch a visible desktop-head-unit if needed. */
    fun openExternalTroubleshooting(): CommandResult

    fun copyDiagnostics(): String
}
