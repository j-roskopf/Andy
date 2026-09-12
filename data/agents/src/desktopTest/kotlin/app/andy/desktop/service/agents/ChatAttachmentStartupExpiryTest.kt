package app.andy.desktop.service.agents

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentAttachment
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.ChatAttachmentService
import app.andy.service.ChatAttachmentUploadSession
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ChatAttachmentStartupExpiryTest {
    @Test
    fun expiresAbandonedStagingDuringServiceStartup() = runBlocking {
        val root = File.createTempFile("andy-attach-expiry", null).also { it.delete(); it.mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.db"))
        store.save(
            AgentStoreState(
                binaryOverrides = AgentKind.entries.associate { it.cliName to "/bin/sh" },
            ),
        )
        val attachments = RecordingChatAttachments()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = DesktopAgentRunService(
            scope = scope,
            store = store,
            locator = AgentCliLocator(),
            adapters = mapOf(
                AgentKind.Codex to object : AgentCliAdapter {
                    override val kind: AgentKind = AgentKind.Codex
                    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
                        listOf(binary, "-c", "exit 0")
                    override fun buildInteractiveResumeCommand(
                        binary: String,
                        task: AgentTask,
                        mcpUrl: String?,
                        followUp: String?,
                        followUpImagePaths: List<String>,
                    ): List<String>? = null
                    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary
                },
            ),
            worktrees = WorktreeManager(File(root, "worktrees")),
            mcp = object : McpServerService {
                override val status = MutableStateFlow("stopped")
                override val running = MutableStateFlow(false)
                override suspend fun start(port: Int): CommandResult = CommandResult.success()
                override suspend fun stop(): CommandResult = CommandResult.success()
                override fun getSnippet(clientName: String, port: Int): String = ""
                override fun getClients(): List<String> = emptyList()
                override fun isAutoWriteSupported(clientName: String): Boolean = false
                override fun writeConfig(clientName: String, port: Int): Boolean = false
                override fun getToolNames(): List<String> = emptyList()
            },
            workspaceStore = object : WorkspaceStore {
                override suspend fun load(): WorkspaceState = WorkspaceState()
                override suspend fun save(state: WorkspaceState) = Unit
            },
            actionConfig = object : ActionConfigStore {
                override suspend fun load(): ActionsConfig = ActionsConfig(
                    projects = listOf(ActionProject("p", "P", root.absolutePath)),
                )
                override suspend fun save(config: ActionsConfig) = Unit
            },
            enableProbes = false,
            terminalMode = AgentTerminalMode.DirectPty,
            chatAttachments = attachments,
        )
        try {
            service.awaitReady()
            withTimeout(5_000) {
                while (attachments.expireCalls.get() == 0) delay(20)
            }
            assertTrue(attachments.expireCalls.get() >= 1)
        } finally {
            runCatching { service.close() }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class RecordingChatAttachments : ChatAttachmentService {
        val expireCalls = AtomicInteger(0)
        override suspend fun stageText(text: String, displayName: String, draftKey: String?): Result<AgentAttachment> =
            Result.failure(UnsupportedOperationException())
        override suspend fun discardStaged(attachmentId: String) = false
        override suspend fun materializeForTask(taskId: String, cwd: String?, attachments: List<AgentAttachment>) =
            attachments
        override suspend fun discardDraft(draftKey: String) = Unit
        override suspend fun expireAbandonedStaging(maxAgeMillis: Long) {
            expireCalls.incrementAndGet()
        }
        override suspend fun beginUpload(
            displayName: String,
            byteCount: Long,
            sha256: String,
            mediaType: String,
            draftKey: String?,
        ): Result<ChatAttachmentUploadSession> = Result.failure(UnsupportedOperationException())
        override suspend fun appendUploadChunk(uploadId: String, sequence: Int, base64Chunk: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
        override suspend fun commitUpload(uploadId: String): Result<AgentAttachment> =
            Result.failure(UnsupportedOperationException())
        override suspend fun cancelUpload(uploadId: String) = false
        override suspend fun readStagedChunk(attachmentId: String, offset: Long, maxBytes: Int): ByteArray? = null
        override suspend fun spilloverPromptIfNeeded(taskId: String, cwd: String?, prompt: String) = prompt
    }
}
