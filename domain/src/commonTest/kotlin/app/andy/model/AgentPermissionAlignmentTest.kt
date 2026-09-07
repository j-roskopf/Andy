package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentPermissionAlignmentTest {
    @Test
    fun sandboxMapsToMatchingAutonomy() {
        assertEquals(AgentAutonomy.ReadOnly, AgentSandboxMode.ReadOnly.toAutonomy())
        assertEquals(AgentAutonomy.Standard, AgentSandboxMode.WorkspaceWrite.toAutonomy())
        assertEquals(AgentAutonomy.Full, AgentSandboxMode.None.toAutonomy())
    }

    @Test
    fun permissionAutonomyPrefersExplicitSandbox() {
        val contradictory = AgentTask(
            id = "t1",
            title = "loop",
            prompt = "go",
            agent = AgentKind.Cursor,
            status = AgentStatus.Working,
            createdAtMillis = 1,
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = AgentSandboxMode.None,
        )
        assertEquals(AgentAutonomy.Full, contradictory.permissionAutonomy())
        assertEquals(AgentSandboxMode.None, contradictory.permissionSandbox())
    }

    @Test
    fun permissionAutonomyFallsBackWhenSandboxUnset() {
        val task = AgentTask(
            id = "t1",
            title = "loop",
            prompt = "go",
            agent = AgentKind.Codex,
            status = AgentStatus.Working,
            createdAtMillis = 1,
            autonomy = AgentAutonomy.Full,
            sandboxMode = null,
        )
        assertEquals(AgentAutonomy.Full, task.permissionAutonomy())
        assertEquals(AgentSandboxMode.None, task.permissionSandbox())
    }

    @Test
    fun draftAlignmentUpdatesAutonomyFromSandbox() {
        val draft = AgentTaskDraft(
            title = "x",
            prompt = "y",
            agent = AgentKind.Cursor,
            projectId = null,
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = AgentSandboxMode.None,
        ).withAlignedPermissions()
        assertEquals(AgentAutonomy.Full, draft.autonomy)
        assertEquals(AgentSandboxMode.None, draft.sandboxMode)
    }

    @Test
    fun draftAlignmentNoopsWithoutSandbox() {
        val draft = AgentTaskDraft(
            title = "x",
            prompt = "y",
            agent = AgentKind.Cursor,
            projectId = null,
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = null,
        ).withAlignedPermissions()
        assertEquals(AgentAutonomy.ReadOnly, draft.autonomy)
        assertNull(draft.sandboxMode)
    }

    @Test
    fun providerDefaultsAlignmentUpdatesAutonomyFromSandbox() {
        val defaults = AgentProviderDefaults(
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = AgentSandboxMode.None,
        ).withAlignedPermissions()
        assertEquals(AgentAutonomy.Full, defaults.autonomy)
        assertEquals(AgentSandboxMode.None, defaults.sandboxMode)
    }
}
