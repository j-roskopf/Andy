package app.andy.desktop.service.webchat

import app.andy.desktop.service.DesktopMcpServerService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.WorkspaceState
import app.andy.service.UnavailableAccessibilityService
import app.andy.service.UnavailableAppService
import app.andy.service.UnavailableAvdService
import app.andy.service.UnavailableBugService
import app.andy.service.UnavailableCrashInspectorService
import app.andy.service.UnavailableEmulatorControls
import app.andy.service.UnavailableFileService
import app.andy.service.UnavailableHeapDumpService
import app.andy.service.UnavailableIntentService
import app.andy.service.UnavailableLogcatService
import app.andy.service.UnavailableMetricsService
import app.andy.service.UnavailableProjectWorkflowService
import app.andy.service.UnavailableProxyService
import app.andy.service.UnavailableRecordingExportService
import app.andy.service.UnavailableViewHierarchyService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.service.UnavailableAgentRunService
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.AgentRunService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Standalone-andyd lifecycle: workspace Network Access changes must rebind HTTP
 * and token regeneration must drop live WebSockets (GUI daemon-client mode only
 * restarts in-process MCP; andyd relies on [NetworkAccessHttpReconciler]).
 */
class NetworkAccessHttpReconcilerTest {
    private lateinit var workspaceFile: File
    private lateinit var workspaceStore: DesktopWorkspaceStore
    private lateinit var mcp: DesktopMcpServerService
    private lateinit var scope: CoroutineScope
    private var port: Int = 0
    private val token = "reconciler-token-abcdefghijklmnopqrstuvwxyz"
    private val agents = FakeAgentRuns()

    @BeforeTest
    fun setUp() {
        runBlocking {
            workspaceFile = File.createTempFile("andy-na-reconciler", ".properties")
            workspaceStore = DesktopWorkspaceStore(workspaceFile)
            port = ephemeralPort()
            workspaceStore.save(
                WorkspaceState(
                    mcpServerEnabled = true,
                    mcpServerPort = port,
                    networkAccessEnabled = false,
                    networkAccessToken = token,
                ),
            )
            mcp = DesktopMcpServerService(
                devices = StubDevices,
                emulatorControls = UnavailableEmulatorControls,
                avd = UnavailableAvdService,
                mirror = StubMirror,
                logcat = UnavailableLogcatService,
                intents = UnavailableIntentService,
                apps = UnavailableAppService,
                files = UnavailableFileService,
                proxy = UnavailableProxyService,
                accessibility = UnavailableAccessibilityService,
                viewHierarchy = UnavailableViewHierarchyService,
                workspaceStore = workspaceStore,
                metrics = UnavailableMetricsService,
                crashInspector = UnavailableCrashInspectorService,
                heapDump = UnavailableHeapDumpService,
                bugs = UnavailableBugService,
                recordingExport = UnavailableRecordingExportService,
                webPush = WebPushService(workspaceStore, PushSubscriptionStore(File.createTempFile("push", ".json"))),
            )
            mcp.bindAgentServices(agents, UnavailableProjectWorkflowService)
            assertTrue(mcp.startHttpBlocking(port).isSuccess)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            scope.cancel()
            mcp.stop()
            workspaceFile.delete()
        }
    }

    @Test
    fun applyBindStaysLoopbackForDefaultTailscaleOnlyMode() = runBlocking {
        assertTrue(mcp.status.value.contains("127.0.0.1"), mcp.status.value)
        // startHttpBlocking reads host from workspace (same as standalone andyd).
        // Default networkAccessTailscaleOnly = true — enabling Network Access alone
        // must NOT open a LAN-facing listener; reach is only via `tailscale serve`.
        workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = true))
        val enabled = workspaceStore.load().toNetworkAccessBindConfig()
        assertTrue(enabled.tailscaleOnly, "test assumes default tailscaleOnly = true")
        val result = applyNetworkAccessHttpBind(mcp, enabled)
        assertTrue(result.isSuccess, result.stderr)
        assertTrue(mcp.status.value.contains("127.0.0.1"), mcp.status.value)
        assertTrue(mcp.running.value)
    }

    @Test
    fun applyBindRebindsFromLoopbackToAllInterfacesWhenTailscaleOnlyOff() = runBlocking {
        assertTrue(mcp.status.value.contains("127.0.0.1"), mcp.status.value)
        workspaceStore.save(
            workspaceStore.load().copy(networkAccessEnabled = true, networkAccessTailscaleOnly = false),
        )
        val enabled = workspaceStore.load().toNetworkAccessBindConfig()
        val result = applyNetworkAccessHttpBind(mcp, enabled)
        assertTrue(result.isSuccess, result.stderr)
        assertTrue(mcp.status.value.contains("0.0.0.0"), mcp.status.value)
        assertTrue(mcp.running.value)
    }

    @Test
    fun applyBindTokenRotationDropsWebsocket() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            val session = client.webSocketSession("ws://127.0.0.1:$port/ws/chats/acp-1?token=$token")
            val frame = withTimeout(5_000) { session.incoming.receive() as Frame.Text }
            assertTrue(frame.readText().contains("acp-1"))

            val newToken = "rotated-token-abcdefghijklmnopqrstuvwxyz"
            workspaceStore.save(workspaceStore.load().copy(networkAccessToken = newToken))
            val rotated = workspaceStore.load().toNetworkAccessBindConfig()
            assertTrue(applyNetworkAccessHttpBind(mcp, rotated).isSuccess)

            val reason = withTimeout(5_000) { session.closeReason.await() }
            assertTrue(reason != null, "websocket should close after HTTP rebind")

            // Old socket is dead; server accepts a fresh connection with the new token.
            val fresh = client.webSocketSession("ws://127.0.0.1:$port/ws/chats/acp-1?token=${rotated.token}")
            try {
                val next = withTimeout(5_000) { fresh.incoming.receive() as Frame.Text }
                assertTrue(next.readText().contains("acp-1"))
            } finally {
                fresh.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun reconcilerAppliesWorkspaceFileChanges() = runBlocking {
        val applied = MutableStateFlow<NetworkAccessBindConfig?>(null)
        val reconciler = NetworkAccessHttpReconciler(
            workspaceStore = workspaceStore,
            mcp = mcp,
            scope = scope,
            pollMillis = 100L,
            onApplied = { next, result ->
                if (result.isSuccess) applied.value = next
            },
        )
        val initial = workspaceStore.load().toNetworkAccessBindConfig()
        reconciler.start(initial)
        try {
            workspaceStore.save(
                workspaceStore.load().copy(
                    networkAccessEnabled = true,
                    networkAccessToken = "file-rotated-token-abcdefghijklmnopqrstu",
                ),
            )
            withTimeout(5_000) {
                while (applied.value?.enabled != true ||
                    applied.value?.token != "file-rotated-token-abcdefghijklmnopqrstu"
                ) {
                    delay(50)
                }
            }
            // Default networkAccessTailscaleOnly = true — stays on loopback; reach is
            // only via `tailscale serve`.
            assertTrue(mcp.status.value.contains("127.0.0.1"), mcp.status.value)
            val client = HttpClient(CIO)
            try {
                // Network Access on → token required even on loopback (Serve-safe).
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("http://127.0.0.1:$port/api/chats").status,
                )
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("http://127.0.0.1:$port/api/chats") {
                        header(HttpHeaders.Authorization, "Bearer file-rotated-token-abcdefghijklmnopqrstu")
                    }.status,
                )
            } finally {
                client.close()
            }
        } finally {
            reconciler.stop()
        }
    }

    @Test
    fun bindConfigTracksEnabledPortAndToken() {
        val a = NetworkAccessBindConfig(enabled = false, tailscaleOnly = true, port = 8565, token = "a")
        val b = a.copy(enabled = true)
        val c = a.copy(port = 9000)
        val d = a.copy(token = "b")
        val e = a.copy(tailscaleOnly = false)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertNotEquals(a, e)
        assertEquals(a, a.copy())
    }

    @Test
    fun suggestNetworkAccessHostsReturnsLoopbackForDefaultTailscaleOnlyMode() = runBlocking {
        workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = true))
        assertTrue(workspaceStore.load().networkAccessTailscaleOnly, "test assumes default tailscaleOnly = true")
        // Nothing listens on the Tailscale/LAN interface in this mode — 127.0.0.1 is
        // the only host actually reachable from this Mac (remote reach is via `tailscale serve`).
        assertEquals(listOf("127.0.0.1"), mcp.suggestNetworkAccessHosts())
    }

    private fun ephemeralPort(): Int = ServerSocket(0).use { it.localPort }

    private object StubDevices : DeviceService {
        override suspend fun discoverSdk() = SdkDiscovery(null, null, null, null, null)
        override suspend fun listDevices() = emptyList<app.andy.model.AndroidDevice>()
        override suspend fun shell(serial: String, command: List<String>) = CommandResult.success()
        override suspend fun pair(host: String, port: Int, code: String) = CommandResult.failure("unused")
        override suspend fun connect(host: String, port: Int) = CommandResult.failure("unused")
        override suspend fun disconnect(serial: String) = CommandResult.failure("unused")
        override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
        override suspend fun mdnsAvailable() = false
        override suspend fun generatePairingQr(content: String): ByteArray? = null
    }

    private object StubMirror : MirrorEngine {
        override val session = MutableStateFlow<MirrorSession?>(null)
        override val frames: Flow<MirrorFrame> = emptyFlow()
        override val status = flowOf("stopped")
        override suspend fun connect(serial: String, config: MirrorVideoConfig) = CommandResult.failure("unused")
        override suspend fun disconnect(immediate: Boolean) = Unit
        override suspend fun sendInput(input: MirrorInput) = CommandResult.failure("unused")
        override suspend fun screenshot(serial: String): ByteArray? = null
    }

    private class FakeAgentRuns : AgentRunService by UnavailableAgentRunService {
        private val acp = AgentTask(
            id = "acp-1",
            title = "ACP chat",
            prompt = "hello",
            agent = AgentKind.Codex,
            projectId = "demo",
            cwd = "/tmp/demo",
            originDir = "/tmp/demo",
            status = AgentStatus.Working,
            lane = AgentLaneKind.Acp,
            createdAtMillis = 1,
        )
        private val _tasks = MutableStateFlow(listOf(acp))
        override val tasks: StateFlow<List<AgentTask>> = _tasks
        private val eventFlows = mutableMapOf<String, MutableStateFlow<List<app.andy.model.AgentEvent>>>()

        override fun events(taskId: String) =
            eventFlows.getOrPut(taskId) { MutableStateFlow(emptyList()) }

        override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
            error("not used in this test")
        }
    }
}
