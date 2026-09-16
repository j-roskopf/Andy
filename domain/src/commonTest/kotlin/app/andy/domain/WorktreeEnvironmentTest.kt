package app.andy.domain

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorktreeEnvironmentTest {

    private fun task(
        id: String,
        cwd: String? = null,
        originDir: String? = null,
        worktreePath: String? = null,
        branchName: String? = null,
        ownsWorktree: Boolean = false,
        parentWorktreeTaskId: String? = null,
        projectId: String = "proj",
        createdAtMillis: Long = 0,
    ) = AgentTask(
        id = id,
        title = id,
        prompt = id,
        agent = AgentKind.Codex,
        projectId = projectId,
        cwd = cwd,
        originDir = originDir,
        worktreePath = worktreePath,
        branchName = branchName,
        ownsWorktree = ownsWorktree,
        parentWorktreeTaskId = parentWorktreeTaskId,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun owningTaskResolvesToItself() {
        val owner = task(
            id = "owner",
            cwd = "/wt/one",
            originDir = "/repo",
            worktreePath = "/wt/one",
            branchName = "andy/one",
            ownsWorktree = true,
        )
        val resolved = resolveWorktreeEnvironment(owner, listOf(owner))!!
        assertEquals("/wt/one", resolved.path)
        assertEquals("andy/one", resolved.branchName)
        assertEquals("owner", resolved.ownerTaskId)
        assertEquals("/repo", resolved.mergeTargetDir)
    }

    @Test
    fun handoffChildSharingParentWorktreeResolvesToParent() {
        val parent = task(
            id = "parent",
            cwd = "/wt/one",
            originDir = "/repo",
            worktreePath = "/wt/one",
            branchName = "andy/one",
            ownsWorktree = true,
        )
        val child = task(
            id = "child",
            cwd = "/wt/one",
            originDir = "/wt/one",
        )
        val resolved = resolveWorktreeEnvironment(child, listOf(parent, child))!!
        assertEquals("/wt/one", resolved.path)
        assertEquals("andy/one", resolved.branchName)
        assertEquals("parent", resolved.ownerTaskId)
        assertEquals("/repo", resolved.mergeTargetDir)
    }

    @Test
    fun trailingSeparatorStillMatches() {
        val parent = task(id = "parent", worktreePath = "/wt/one", ownsWorktree = true, originDir = "/repo")
        val child = task(id = "child", cwd = "/wt/one/")
        val resolved = resolveWorktreeEnvironment(child, listOf(parent, child))!!
        assertEquals("/wt/one", resolved.path)
    }

    @Test
    fun taskWithItsOwnWorktreePathResolvesToItself() {
        val owner = task(id = "owner", worktreePath = "/wt/one", branchName = "andy/one", ownsWorktree = true)
        val reuser = task(id = "reuser", cwd = "/wt/one", worktreePath = "/wt/one", branchName = "andy/one")
        val resolved = resolveWorktreeEnvironment(reuser, listOf(owner, reuser))!!
        assertEquals("reuser", resolved.ownerTaskId)
    }

    @Test
    fun owningSiblingWinsWhenSeveralShareThePath() {
        val owner = task(
            id = "owner",
            worktreePath = "/wt/one",
            ownsWorktree = true,
            createdAtMillis = 20,
        )
        val shared = task(
            id = "shared",
            cwd = "/wt/one",
            worktreePath = "/wt/one",
            ownsWorktree = false,
            createdAtMillis = 10,
        )
        val child = task(id = "child", cwd = "/wt/one", createdAtMillis = 30)
        val resolved = resolveWorktreeEnvironment(child, listOf(shared, owner, child))!!
        assertEquals("owner", resolved.ownerTaskId)
    }

    @Test
    fun mergeTargetPrefersParentWorktreeOverOrigin() {
        val grandparent = task(id = "grandparent", worktreePath = "/wt/root", ownsWorktree = true)
        val parent = task(
            id = "parent",
            cwd = "/wt/branch",
            originDir = "/repo",
            worktreePath = "/wt/branch",
            branchName = "andy/branch",
            parentWorktreeTaskId = "grandparent",
        )
        val resolved = resolveWorktreeEnvironment(parent, listOf(grandparent, parent))!!
        assertEquals("/wt/root", resolved.mergeTargetDir)
    }

    @Test
    fun nonWorktreeTaskWithNoSiblingResolvesToNull() {
        val plain = task(id = "plain", cwd = "/repo", originDir = "/repo")
        assertNull(resolveWorktreeEnvironment(plain, listOf(plain)))
    }

    @Test
    fun siblingInAnotherProjectDoesNotMatch() {
        val parent = task(id = "parent", projectId = "other", worktreePath = "/wt/one", ownsWorktree = true)
        val child = task(id = "child", projectId = "proj", cwd = "/wt/one")
        assertNull(resolveWorktreeEnvironment(child, listOf(parent, child)))
    }
}
