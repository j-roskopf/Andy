package app.andy.desktop.service.webchat

import app.andy.desktop.service.DesktopMcpServerService
import app.andy.model.WorkspaceState
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Snapshot of workspace fields that require an HTTP MCP / Network Access rebind.
 * Token is included so regenerating the shared secret drops live WebSockets.
 */
internal data class NetworkAccessBindConfig(
    val enabled: Boolean,
    val tailscaleOnly: Boolean,
    val port: Int,
    val token: String,
)

internal fun WorkspaceState.toNetworkAccessBindConfig(): NetworkAccessBindConfig =
    NetworkAccessBindConfig(
        enabled = networkAccessEnabled,
        tailscaleOnly = networkAccessTailscaleOnly,
        port = mcpServerPort,
        token = networkAccessToken,
    )

/**
 * Applies [desired] to [mcp]: stop then start so host/port/token changes take effect
 * (including token regen, which would otherwise hit the same-host early-return in
 * [DesktopMcpServerService.startHttpBlocking]).
 */
internal fun applyNetworkAccessHttpBind(
    mcp: DesktopMcpServerService,
    desired: NetworkAccessBindConfig,
): app.andy.service.CommandResult {
    // Always stop first — same host+port with a rotated token must still drop WS sessions.
    runBlocking { mcp.stop() }
    return mcp.startHttpBlocking(desired.port)
}

/**
 * Standalone `andyd` watches [workspaceStore] (backed by `workspace.properties`) so
 * GUI Settings changes in daemon-client mode rebind HTTP without restarting the daemon.
 *
 * The Compose GUI only restarts its in-process MCP; this reconciler is what makes
 * Network Access settings control the separately running daemon.
 */
internal class NetworkAccessHttpReconciler(
    private val workspaceStore: WorkspaceStore,
    private val mcp: DesktopMcpServerService,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 750L,
    private val onApplied: (NetworkAccessBindConfig, app.andy.service.CommandResult) -> Unit = { _, _ -> },
) {
    private var job: Job? = null

    fun start(initial: NetworkAccessBindConfig) {
        job?.cancel()
        job = scope.launch {
            var applied = initial
            while (isActive) {
                delay(pollMillis)
                val next = runCatching { workspaceStore.load().toNetworkAccessBindConfig() }
                    .getOrNull() ?: continue
                if (next == applied) continue
                System.err.println(
                    "andyd: Network Access settings changed " +
                        "(enabled=${next.enabled}, tailscaleOnly=${next.tailscaleOnly}, " +
                        "port=${next.port}, tokenRotated=${next.token != applied.token}); " +
                        "rebinding HTTP",
                )
                val result = runCatching { applyNetworkAccessHttpBind(mcp, next) }
                    .getOrElse { error ->
                        error.printStackTrace()
                        app.andy.service.CommandResult.failure(error.message ?: "rebind failed")
                    }
                if (result.isSuccess) {
                    applied = next
                } else {
                    System.err.println(
                        "andyd: WARNING Network Access HTTP rebind failed " +
                            "(${result.stderr.ifBlank { result.stdout }})",
                    )
                }
                onApplied(next, result)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
