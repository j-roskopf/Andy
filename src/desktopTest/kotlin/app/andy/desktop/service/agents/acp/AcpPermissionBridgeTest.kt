package app.andy.desktop.service.agents.acp

import app.andy.model.AgentAutonomy
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentUserInputOrigin
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class AcpPermissionBridgeTest {
    private val allowOnce = PermissionOption(
        PermissionOptionId("allow-once"),
        "Allow once",
        PermissionOptionKind.ALLOW_ONCE,
    )
    private val rejectOnce = PermissionOption(
        PermissionOptionId("reject-once"),
        "Reject once",
        PermissionOptionKind.REJECT_ONCE,
    )
    private val options = listOf(allowOnce, rejectOnce)

    private fun toolCall(kind: ToolKind = ToolKind.EXECUTE, id: String = "call-1") =
        SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId(id),
            title = "curl github",
            kind = kind,
            status = null,
            content = null,
            locations = null,
            rawInput = null,
            rawOutput = null,
        )

    private fun bridge(
        autonomy: AgentAutonomy = AgentAutonomy.Standard,
        planMode: Boolean = false,
        sandboxMode: AgentSandboxMode? = AgentSandboxMode.WorkspaceWrite,
        confirmToolCalls: Boolean = false,
        onPending: (PendingAcpPermission) -> Unit = {},
        onResolved: (String, String, Boolean, String?) -> Unit = { _, _, _, _ -> },
    ): AcpPermissionBridge = AcpPermissionBridge(
        taskId = "task-1",
        autonomy = autonomy,
        planMode = planMode,
        sandboxMode = sandboxMode,
        confirmToolCalls = confirmToolCalls,
        cwd = File(System.getProperty("java.io.tmpdir")),
        onPending = onPending,
        onResolved = onResolved,
    )

    private fun selectedOptionId(response: com.agentclientprotocol.model.RequestPermissionResponse): PermissionOptionId {
        val selected = assertIs<RequestPermissionOutcome.Selected>(response.outcome)
        return selected.optionId
    }

    @Test
    fun readOnlyAutonomyPromptsForExecute() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.ReadOnly,
            onPending = { pending = it },
        )
        coroutineScope {
            val deferred = async { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            assertEquals(AgentUserInputOrigin.AcpPermission, pending!!.request.origin)
            assertTrue(subject.respond(pending!!.request.id, allowOnce.name))
            assertEquals(allowOnce.optionId, selectedOptionId(deferred.await()))
        }
    }

    @Test
    fun planModePromptsForExecute() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Full,
            planMode = true,
            sandboxMode = AgentSandboxMode.ReadOnly,
            onPending = { pending = it },
        )
        coroutineScope {
            val deferred = async { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            assertNotNull(pending)
            assertTrue(subject.respond(pending!!.request.id, rejectOnce.name))
            assertEquals(rejectOnce.optionId, selectedOptionId(deferred.await()))
        }
    }

    @Test
    fun standardAutonomyWaitsForUserOnExecute() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(onPending = { pending = it })
        val response = coroutineScope {
            val deferred = async { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            val shown = pending!!
            assertEquals(AgentUserInputOrigin.AcpPermission, shown.request.origin)
            assertTrue(subject.respond(shown.request.id, allowOnce.name))
            deferred.await()
        }
        assertEquals(allowOnce.optionId, selectedOptionId(response))
    }

    @Test
    fun respondDoesNotFallbackToRejectForUnknownAnswer() = runBlocking {
        var pending: PendingAcpPermission? = null
        var resolved: Boolean? = null
        val subject = bridge(
            onPending = { pending = it },
            onResolved = { _, _, allowed, _ -> resolved = allowed },
        )
        coroutineScope {
            val deferred = async { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            val shown = pending!!
            assertFalse(subject.respond(shown.request.id, "typed something unexpected"))
            assertNull(resolved)
            assertTrue(subject.respond(shown.request.id, rejectOnce.name))
            deferred.await()
        }
        assertEquals(false, resolved)
    }

    @Test
    fun cancelAllCompletesPendingWithSessionStoppedNote() = runBlocking {
        var pending: PendingAcpPermission? = null
        var resolvedNote: String? = null
        val subject = bridge(
            onPending = { pending = it },
            onResolved = { _, _, _, note -> resolvedNote = note },
        )
        coroutineScope {
            launch { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            subject.cancelAll()
        }
        assertNotNull(resolvedNote)
        assertTrue(resolvedNote!!.contains("session stopped"))
    }

    @Test
    fun fullAutonomyWithNoSandboxAutoAllowsExecute() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            onPending = { pending = it },
        )
        val response = subject.request(toolCall(), options, null)
        assertEquals(allowOnce.optionId, selectedOptionId(response))
        assertNull(pending)
    }

    @Test
    fun skipPermissionsAutoAllowsExecuteWithoutFullAutonomy() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Standard,
            sandboxMode = AgentSandboxMode.None,
            onPending = { pending = it },
        )
        val response = subject.request(toolCall(), options, null)
        assertEquals(allowOnce.optionId, selectedOptionId(response))
        assertNull(pending)
    }

    @Test
    fun fullAutonomyWithDefaultSandboxAutoAllowsExecute() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Full,
            sandboxMode = null,
            onPending = { pending = it },
        )
        val response = subject.request(toolCall(), options, null)
        assertEquals(allowOnce.optionId, selectedOptionId(response))
        assertNull(pending)
    }

    @Test
    fun confirmToolCallsPromptsForReadEvenWhenAutonomyWouldAutoAllow() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Standard,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            confirmToolCalls = true,
            onPending = { pending = it },
        )
        coroutineScope {
            val deferred = async { subject.request(toolCall(kind = ToolKind.READ), options, null) }
            while (pending == null) yield()
            assertNotNull(pending)
            assertTrue(subject.respond(pending!!.request.id, allowOnce.name))
            assertEquals(allowOnce.optionId, selectedOptionId(deferred.await()))
        }
    }

    @Test
    fun confirmToolCallsPromptsEvenWithFullBypassSandbox() = runBlocking {
        var pending: PendingAcpPermission? = null
        val subject = bridge(
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            confirmToolCalls = true,
            onPending = { pending = it },
        )
        coroutineScope {
            val deferred = async { subject.request(toolCall(), options, null) }
            while (pending == null) yield()
            assertNotNull(pending)
            assertTrue(subject.respond(pending!!.request.id, allowOnce.name))
            assertEquals(allowOnce.optionId, selectedOptionId(deferred.await()))
        }
    }

    @Test
    fun planModePromptsForWorkspaceEdit() = runBlocking {
        var pending: PendingAcpPermission? = null
        val cwd = File.createTempFile("acp-permission", null).parentFile!!
        val inside = File(cwd, "src/Main.kt").apply { parentFile?.mkdirs(); writeText("fun main() {}") }
        val subject = AcpPermissionBridge(
            taskId = "task-1",
            autonomy = AgentAutonomy.Full,
            planMode = true,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            confirmToolCalls = false,
            cwd = cwd,
            onPending = { pending = it },
            onResolved = { _, _, _, _ -> },
        )
        val editCall = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("edit-1"),
            title = "edit Main.kt",
            kind = ToolKind.EDIT,
            status = null,
            content = null,
            locations = listOf(com.agentclientprotocol.model.ToolCallLocation(inside.path)),
            rawInput = null,
            rawOutput = null,
        )
        coroutineScope {
            val deferred = async { subject.request(editCall, options, null) }
            while (pending == null) yield()
            assertTrue(subject.respond(pending!!.request.id, rejectOnce.name))
            assertEquals(rejectOnce.optionId, selectedOptionId(deferred.await()))
        }
    }

    @Test
    fun readOnlyAutonomyPromptsForWorkspaceEdit() = runBlocking {
        var pending: PendingAcpPermission? = null
        val cwd = File.createTempFile("acp-permission", null).parentFile!!
        val inside = File(cwd, "src/Main.kt").apply { parentFile?.mkdirs(); writeText("fun main() {}") }
        val subject = AcpPermissionBridge(
            taskId = "task-1",
            autonomy = AgentAutonomy.ReadOnly,
            planMode = false,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            confirmToolCalls = false,
            cwd = cwd,
            onPending = { pending = it },
            onResolved = { _, _, _, _ -> },
        )
        val editCall = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("edit-1"),
            title = "edit Main.kt",
            kind = ToolKind.EDIT,
            status = null,
            content = null,
            locations = listOf(com.agentclientprotocol.model.ToolCallLocation(inside.path)),
            rawInput = null,
            rawOutput = null,
        )
        coroutineScope {
            val deferred = async { subject.request(editCall, options, null) }
            while (pending == null) yield()
            assertTrue(subject.respond(pending!!.request.id, allowOnce.name))
            assertEquals(allowOnce.optionId, selectedOptionId(deferred.await()))
        }
    }
}
