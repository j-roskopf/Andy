package app.andy.desktop.service.webchat

import app.andy.desktop.service.DesktopMcpServerService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.service.UnavailableAccessibilityService
import app.andy.service.UnavailableAgentRunService
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class WebChatHttpServerTest {
    private lateinit var workspaceFile: File
    private lateinit var workspaceStore: DesktopWorkspaceStore
    private lateinit var agents: FakeAgentRuns
    private lateinit var mcp: DesktopMcpServerService
    private var port: Int = 0
    private val token = "test-network-token-abcdefghijklmnopqrstuvwxyz"

    @BeforeTest
    fun setUp() {
        runBlocking {
            workspaceFile = File.createTempFile("andy-webchat-ws", ".properties")
            workspaceStore = DesktopWorkspaceStore(workspaceFile)
            workspaceStore.save(
                WorkspaceState(
                    mcpServerEnabled = true,
                    mcpServerPort = 0,
                    networkAccessEnabled = false,
                    networkAccessToken = token,
                ),
            )
            agents = FakeAgentRuns()
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
                actionConfig = FakeActionConfig,
            )
            mcp.bindAgentServices(agents, UnavailableProjectWorkflowService)
            port = ephemeralPort()
            val result = mcp.startHttpBlocking(port)
            assertTrue(result.isSuccess, result.stderr)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            mcp.stop()
            workspaceFile.delete()
        }
    }

    @Test
    fun loopbackWithoutTokenSucceeds() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/chats")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("acp-1"))
            assertTrue(!body.contains("term-1"), "terminal-lane chats must be filtered out")
        } finally {
            client.close()
        }
    }

    @Test
    fun authPluginRejectsMissingTokenForNonLoopback() {
        val limiter = AuthFailureLimiter(10, 60_000, 60_000) { 0L }
        assertEquals(
            HttpStatusCode.Unauthorized,
            evaluateNetworkAccessAuth("203.0.113.10", null, token, limiter),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            evaluateNetworkAccessAuth("203.0.113.10", "wrong", token, limiter),
        )
        assertEquals(
            null,
            evaluateNetworkAccessAuth("203.0.113.10", token, token, limiter),
        )
    }

    @Test
    fun modelsListsProviderDefaultAndCatalogOptions() = runBlocking {
        agents.setProviderModels(
            AgentKind.Codex to listOf(
                AgentModelOption("gpt-5.6-sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.High)),
            ),
        )
        agents.setProviderDefaults(
            AgentKind.Codex to AgentProviderDefaults(model = "gpt-5.6-sol"),
        )
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/models?agent=Codex")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Provider default"))
            assertTrue(body.contains("gpt-5.6-sol"))
            assertTrue(body.contains("\"defaultModel\":\"gpt-5.6-sol\""))
        } finally {
            client.close()
        }
    }

    @Test
    fun startRejectsNonAcpAgent() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/chats/start") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"prompt":"hi","agent":"Antigravity","directory":"/tmp","autonomy":"Standard"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("ACP-lane"))
        } finally {
            client.close()
        }
    }

    @Test
    fun replyRejectsNonStringMessageWithBadRequest() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/chats/acp-1/reply") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":{"nested":true}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("message must be string"))
        } finally {
            client.close()
        }
    }

    @Test
    fun respondRejectsNonStringAnswerValuesWithBadRequest() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/chats/acp-1/respond") {
                contentType(ContentType.Application.Json)
                setBody("""{"requestId":"r1","answers":{"q1":["not","a","string"]}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("answers values must be strings"))
        } finally {
            client.close()
        }
    }

    @Test
    fun startRejectsNonStringPromptWithBadRequest() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/chats/start") {
                contentType(ContentType.Application.Json)
                setBody("""{"prompt":123,"agent":"Codex"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("prompt must be string"))
        } finally {
            client.close()
        }
    }

    @Test
    fun subscribeEscapesErrorPayloadAsJson() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/push/subscribe") {
                contentType(ContentType.Application.Json)
                // Missing keys → WebPushService throws; message must not break JSON.
                setBody("""{"endpoint":"https://example.com/push"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            // Valid JSON object with an "error" string (no raw quote injection).
            assertTrue(body.startsWith("{") && body.endsWith("}"))
            assertTrue(body.contains("\"error\""))
        } finally {
            client.close()
        }
    }

    @Test
    fun loopbackPushSubscribeWithoutNetworkAccess() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.post("http://127.0.0.1:$port/api/push/subscribe") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"endpoint":"https://push.example.test/ep","keys":{"p256dh":"key","auth":"auth"}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("ok"))
        } finally {
            client.close()
        }
    }

    @Test
    fun websocketAcceptsTokenQueryParam() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            client.webSocket("ws://127.0.0.1:$port/ws/chats/acp-1?token=$token") {
                val frame = incoming.receive() as Frame.Text
                val text = frame.readText()
                assertTrue(text.contains("\"taskId\":\"acp-1\""))
                // Initial snapshot must carry replaceFrom=0 so REST-preloaded clients
                // replace history instead of appending a duplicate transcript.
                assertTrue(text.contains("\"replaceFrom\":0"), text)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun staticIndexServed() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Andy"))
        } finally {
            client.close()
        }
    }

    @Test
    fun staticAssetsAllowedWithoutTokenForNonLoopback() = runBlocking {
        mcp.authPeerAddressOverride = "203.0.113.50"
        val client = HttpClient(CIO)
        try {
            val index = client.get("http://127.0.0.1:$port/?token=$token")
            assertEquals(HttpStatusCode.OK, index.status)
            assertTrue(index.bodyAsText().contains("Andy"))

            val js = client.get("http://127.0.0.1:$port/app.js")
            assertEquals(HttpStatusCode.OK, js.status)

            val css = client.get("http://127.0.0.1:$port/styles.css")
            assertEquals(HttpStatusCode.OK, css.status)

            val manifest = client.get("http://127.0.0.1:$port/manifest.json")
            assertEquals(HttpStatusCode.OK, manifest.status)

            val api = client.get("http://127.0.0.1:$port/api/chats")
            assertEquals(HttpStatusCode.Unauthorized, api.status)
        } finally {
            mcp.authPeerAddressOverride = null
            client.close()
        }
    }

    @Test
    fun websocketRejectsNonLoopbackWithoutTokenWith4401() = runBlocking {
        mcp.authPeerAddressOverride = "203.0.113.50"
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            val session = client.webSocketSession("ws://127.0.0.1:$port/ws/chats/acp-1")
            val reason = withTimeout(5_000) { session.closeReason.await() }
            assertEquals(
                NetworkAccessAuthFailureCloseCode.toShort(),
                reason?.code,
                "expected auth close code, got $reason",
            )
        } finally {
            mcp.authPeerAddressOverride = null
            client.close()
        }
    }

    @Test
    fun websocketRejectsNonLoopbackWithWrongTokenWith4401() = runBlocking {
        mcp.authPeerAddressOverride = "203.0.113.50"
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            val session = client.webSocketSession("ws://127.0.0.1:$port/ws/chats/acp-1?token=wrong")
            val reason = withTimeout(5_000) { session.closeReason.await() }
            assertEquals(NetworkAccessAuthFailureCloseCode.toShort(), reason?.code)
        } finally {
            mcp.authPeerAddressOverride = null
            client.close()
        }
    }

    @Test
    fun websocketAcceptsNonLoopbackWithTokenQuery() = runBlocking {
        mcp.authPeerAddressOverride = "203.0.113.50"
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            client.webSocket("ws://127.0.0.1:$port/ws/chats/acp-1?token=$token") {
                val frame = incoming.receive() as Frame.Text
                assertTrue(frame.readText().contains("\"taskId\":\"acp-1\""))
            }
        } finally {
            mcp.authPeerAddressOverride = null
            client.close()
        }
    }

    @Test
    fun bearerTokenWorksOnLoopbackToo() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally {
            client.close()
        }
    }

    @Test
    fun networkAccessOnRequiresTokenEvenOnLoopback() = runBlocking {
        workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = true))
        // Restart so the auth plugin sees enabled=true from workspace on each call
        // (providers re-read workspace; no rebind required for this flag).
        val client = HttpClient(CIO)
        try {
            val denied = client.get("http://127.0.0.1:$port/api/chats")
            assertEquals(HttpStatusCode.Unauthorized, denied.status)

            val ok = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
        } finally {
            workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = false))
            client.close()
        }
    }

    @Test
    fun tailscaleOnlyRejectsLanPeerEvenWithToken() = runBlocking {
        workspaceStore.save(
            workspaceStore.load().copy(
                networkAccessEnabled = true,
                networkAccessTailscaleOnly = true,
            ),
        )
        mcp.authPeerAddressOverride = "192.168.1.50"
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            mcp.authPeerAddressOverride = null
            workspaceStore.save(
                workspaceStore.load().copy(
                    networkAccessEnabled = false,
                    networkAccessTailscaleOnly = true,
                ),
            )
            client.close()
        }
    }

    @Test
    fun tailscaleOnlyAllowsTailscalePeerWithToken() = runBlocking {
        workspaceStore.save(
            workspaceStore.load().copy(
                networkAccessEnabled = true,
                networkAccessTailscaleOnly = true,
            ),
        )
        mcp.authPeerAddressOverride = "100.72.168.32"
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally {
            mcp.authPeerAddressOverride = null
            workspaceStore.save(
                workspaceStore.load().copy(
                    networkAccessEnabled = false,
                    networkAccessTailscaleOnly = true,
                ),
            )
            client.close()
        }
    }

    @Test
    fun projectsReturnsConfiguredNames() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/projects")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"name\":\"Demo Project\""), body)
            assertTrue(body.contains("\"id\":\"demo\""), body)
            assertTrue(!body.contains("/tmp/demo"), "web API must not expose filesystem paths")
        } finally {
            client.close()
        }
    }

    @Test
    fun slashCommandsIncludesNativeGoalForCodex() = runBlocking {
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/slash-commands?agent=Codex")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"name\":\"goal\""), body)
            assertTrue(body.contains("\"name\":\"review\""), body)
        } finally {
            client.close()
        }
    }

    @Test
    fun websocketStaysOpenAfterTerminalStatus() = runBlocking {
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            client.webSocket(
                method = io.ktor.http.HttpMethod.Get,
                host = "127.0.0.1",
                port = port,
                path = "/ws/chats/acp-1",
                request = {
                    header(HttpHeaders.SecWebSocketProtocol, "bearer.$token")
                },
            ) {
                val first = (incoming.receive() as Frame.Text).readText()
                assertTrue(first.contains("\"replaceFrom\":0"), first)
                agents.setStatus("acp-1", AgentStatus.Done)
                val terminal = withTimeout(5_000) {
                    var text: String? = null
                    while (text == null || !text.contains("\"done\":true")) {
                        text = (incoming.receive() as Frame.Text).readText()
                    }
                    text
                }
                assertTrue(terminal.contains("\"terminalStatus\":\"terminal\""), terminal)
                // Socket must remain usable for a follow-up turn (no auto-close on Done).
                agents.setStatus("acp-1", AgentStatus.Working)
                val followUp = withTimeout(5_000) {
                    (incoming.receive() as Frame.Text).readText()
                }
                assertTrue(followUp.contains("\"taskId\":\"acp-1\""), followUp)
                assertNull(withTimeoutOrNull(200) { closeReason.await() })
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun restRejectsQueryTokenOutsideWebSocket() {
        // Non-loopback auth decision must ignore ?token= on REST/MCP paths.
        assertNull(
            extractAccessToken(
                authorizationHeader = null,
                path = "/api/chats",
                queryToken = token,
            ),
        )
        assertNull(
            extractAccessToken(
                authorizationHeader = null,
                path = "/mcp-http",
                queryToken = token,
            ),
        )
        assertEquals(
            token,
            extractAccessToken(
                authorizationHeader = null,
                path = "/ws/chats/acp-1",
                queryToken = token,
            ),
        )
    }

    @Test
    fun loginCodeExchangesForChatSession() = runBlocking {
        workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = true))
        val code = mcp.createNetworkLoginCode()
        assertTrue(code.isNotBlank())
        val client = HttpClient(CIO) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 3_000
            }
        }
        try {
            val response = client.post("http://127.0.0.1:$port/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("sessionToken"), body)
            val session = Regex(""""sessionToken"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            assertTrue(!session.isNullOrBlank())
            val chatOk = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $session")
            }
            assertEquals(HttpStatusCode.OK, chatOk.status)
            val mcpResponse = client.get("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Authorization, "Bearer $session")
            }
            assertEquals(HttpStatusCode.Forbidden, mcpResponse.status)
        } finally {
            workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = false))
            client.close()
        }
    }

    @Test
    fun masterTokenGrantsApiWhenNetworkAccessOn() = runBlocking {
        workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = true))
        val client = HttpClient(CIO)
        try {
            val response = client.get("http://127.0.0.1:$port/api/chats") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        } finally {
            workspaceStore.save(workspaceStore.load().copy(networkAccessEnabled = false))
            client.close()
        }
    }

    private fun ephemeralPort(): Int =
        ServerSocket(0).use { it.localPort }

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

    private object FakeActionConfig : ActionConfigStore {
        override suspend fun load(): ActionsConfig = ActionsConfig(
            projects = listOf(
                ActionProject(
                    id = "demo",
                    name = "Demo Project",
                    contextDir = "/tmp/demo",
                ),
            ),
        )

        override suspend fun save(config: ActionsConfig) = Unit
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
        private val term = AgentTask(
            id = "term-1",
            title = "Terminal chat",
            prompt = "hello",
            agent = AgentKind.Antigravity,
            status = AgentStatus.Working,
            lane = AgentLaneKind.Terminal,
            createdAtMillis = 2,
        )
        private val _tasks = MutableStateFlow(listOf(acp, term))
        override val tasks: StateFlow<List<AgentTask>> = _tasks
        private val eventFlows = mutableMapOf<String, MutableStateFlow<List<app.andy.model.AgentEvent>>>()
        private val slash = MutableStateFlow(
            listOf(AgentSlashCommand("review", "provider review command")),
        )
        private val _providerModels = MutableStateFlow<Map<AgentKind, List<AgentModelOption>>>(emptyMap())
        override val providerModels = _providerModels
        private val _providerDefaults = MutableStateFlow<Map<AgentKind, AgentProviderDefaults>>(emptyMap())
        override val providerDefaults = _providerDefaults

        fun setProviderModels(models: Pair<AgentKind, List<AgentModelOption>>) {
            _providerModels.value = mapOf(models)
        }

        fun setProviderDefaults(defaults: Pair<AgentKind, AgentProviderDefaults>) {
            _providerDefaults.value = mapOf(defaults)
        }

        override fun events(taskId: String) =
            eventFlows.getOrPut(taskId) { MutableStateFlow(emptyList()) }

        override fun slashCommands(agent: AgentKind, directory: String?) = slash

        override fun refreshSlashCommands(agent: AgentKind, directory: String?) = Unit

        fun setStatus(taskId: String, status: AgentStatus) {
            _tasks.value = _tasks.value.map { task ->
                if (task.id == taskId) task.copy(status = status) else task
            }
        }

        override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
            error("not used in this test")
        }
    }
}

