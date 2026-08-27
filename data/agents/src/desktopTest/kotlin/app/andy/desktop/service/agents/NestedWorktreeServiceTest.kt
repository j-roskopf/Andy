package app.andy.desktop.service.agents

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.WorktreeDeleteOutcome
import app.andy.model.WorktreeMergeOutcome
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NestedWorktreeServiceTest {
    @Test
    fun launchingWithBaseWorktreeSetsParentAndForksFromBaseTip() = runBlocking {
        withHarness { harness ->
            val baseTip = harness.commitOnBranch("base-feature", "feature.txt", "feature\n")
            harness.checkout("main")

            val parent = harness.startWorktreeTask(title = "parent chat")
            assertNull(parent.parentWorktreeTaskId)
            assertNotNull(parent.branchName)
            assertNotNull(parent.worktreePath)

            // Move parent branch tip ahead of main so the child's merge-base is unambiguous.
            val parentPath = assertNotNull(parent.worktreePath)
            val parentBranch = assertNotNull(parent.branchName)
            File(parentPath, "parent.txt").writeText("parent work\n")
            git(File(parentPath), "add", ".")
            git(File(parentPath), "commit", "-m", "parent tip")
            val parentTip = revParse(harness.repo, parentBranch)

            val child = harness.startWorktreeTask(
                title = "child chat",
                baseWorktreeTaskId = parent.id,
            )
            assertEquals(parent.id, child.parentWorktreeTaskId)
            val childBranch = assertNotNull(child.branchName)
            assertEquals(parentTip, revParse(harness.repo, childBranch))
            assertEquals(
                parentTip,
                gitOutput(harness.repo, "merge-base", childBranch, parentBranch).trim(),
            )
            // Origin stayed on main; child still forked from parent tip, not origin HEAD.
            assertEquals(baseTip, revParse(harness.repo, "base-feature"))
            assertTrue(revParse(harness.repo, "main") != parentTip)
        }
    }

    @Test
    fun parentWorktreeTaskIdRoundTripsThroughStoreRestart() = runBlocking {
        withHarness { harness ->
            val parent = harness.startWorktreeTask(title = "parent")
            val child = harness.startWorktreeTask(title = "child", baseWorktreeTaskId = parent.id)
            assertEquals(parent.id, child.parentWorktreeTaskId)

            val reloaded = DesktopAgentTaskStore(harness.dbFile).load()
            val loadedChild = reloaded.tasks.first { it.id == child.id }
            assertEquals(parent.id, loadedChild.parentWorktreeTaskId)
        }
    }

    @Test
    fun mergeBranchAppliesDirtyWorkToWorkingTreeWithoutCommittingThenDeleteClearsWorktree() = runBlocking {
        withHarness { harness ->
            val task = harness.startWorktreeTask(title = "feature")
            val worktreePath = assertNotNull(task.worktreePath)
            val branch = assertNotNull(task.branchName)
            val headBefore = revParse(harness.repo, "HEAD")

            File(worktreePath, "from-agent.txt").writeText("agent work\n")

            assertEquals(
                WorktreeMergeOutcome.Applied,
                harness.service.mergeBranch(
                    targetDir = harness.repo.absolutePath,
                    branch = branch,
                    sourceWorktreePath = worktreePath,
                ),
            )
            assertTrue(File(harness.repo, "from-agent.txt").isFile)
            assertEquals("main", harness.service.currentBranch(harness.repo.absolutePath))
            assertEquals(headBefore, revParse(harness.repo, "HEAD"))
            assertTrue(gitOutput(harness.repo, "status", "--porcelain").contains("from-agent.txt"))

            assertEquals(WorktreeDeleteOutcome.Deleted, harness.service.delete(task.id, removeWorktree = true))
            assertTrue(harness.service.tasks.value.none { it.id == task.id })
            assertFalse(File(worktreePath).exists())
            assertTrue(File(harness.repo, "from-agent.txt").isFile)
            assertEquals(headBefore, revParse(harness.repo, "HEAD"))
        }
    }

    @Test
    fun deleteBlockedByChildrenUnlessForced() = runBlocking {
        withHarness { harness ->
            val parent = harness.startWorktreeTask(title = "parent")
            val child = harness.startWorktreeTask(title = "child", baseWorktreeTaskId = parent.id)
            val parentPath = parent.worktreePath!!
            val childPath = child.worktreePath!!

            val blocked = harness.service.delete(parent.id, removeWorktree = true)
            val blockedChildren = assertNotNull(blocked as? WorktreeDeleteOutcome.BlockedByChildren)
            assertEquals(listOf(child.id), blockedChildren.children.map { it.taskId })
            assertTrue(harness.service.tasks.value.any { it.id == parent.id })
            assertTrue(File(parentPath).isDirectory)
            assertTrue(File(childPath).isDirectory)

            val deleted = harness.service.delete(parent.id, removeWorktree = true, force = true)
            assertEquals(WorktreeDeleteOutcome.Deleted, deleted)
            assertTrue(harness.service.tasks.value.none { it.id == parent.id })
            assertFalse(File(parentPath).exists())
            val orphan = harness.service.tasks.value.first { it.id == child.id }
            assertNull(orphan.parentWorktreeTaskId)
            assertTrue(File(childPath).isDirectory)
        }
    }

    @Test
    fun existingWorktreePathTakesPrecedenceWhenUseWorktreeAlsoTrue() = runBlocking {
        withHarness { harness ->
            val owner = harness.startWorktreeTask(title = "owner")
            val path = assertNotNull(owner.worktreePath)
            val branch = assertNotNull(owner.branchName)
            val reused = harness.service.createAndStart(
                AgentTaskDraft(
                    title = "reuse with both flags",
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = harness.repo.absolutePath,
                    useWorktree = true,
                    existingWorktreePath = path,
                    existingBranchName = branch,
                ),
            )
            withTimeout(15_000) {
                while (true) {
                    val current = harness.service.tasks.value.first { it.id == reused.id }
                    if (current.worktreePath != null || current.status == AgentStatus.Error) break
                    delay(25)
                }
            }
            val reuseTask = harness.service.tasks.value.first { it.id == reused.id }
            assertNull(reuseTask.errorMessage, reuseTask.errorMessage)
            assertEquals(path, reuseTask.worktreePath)
            assertFalse(reuseTask.ownsWorktree)
            assertFalse(reuseTask.useWorktree, "reuse must clear useWorktree so reloads cannot claim ownership")
            // Must not have created a second worktree under the manager root.
            assertEquals(
                1,
                harness.service.tasks.value.count { it.ownsWorktree && it.worktreePath != null },
            )
        }
    }

    @Test
    fun useWorktreeWithoutDirectoryReturnsErrorShapedTask() = runBlocking {
        withHarness { harness ->
            val task = harness.service.createAndStart(
                AgentTaskDraft(
                    title = "no directory",
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = null,
                    useWorktree = true,
                ),
            )
            assertEquals(AgentStatus.Error, task.status)
            assertEquals("a project directory is required to create a worktree", task.errorMessage)
        }
    }

    @Test
    fun invalidExistingWorktreePathReturnsErrorWithoutScratchFallback() = runBlocking {
        withHarness { harness ->
            val missing = File(harness.repo, "does-not-exist-worktree").absolutePath
            val task = harness.service.createAndStart(
                AgentTaskDraft(
                    title = "bad reuse",
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = harness.repo.absolutePath,
                    existingWorktreePath = missing,
                ),
            )
            assertEquals(AgentStatus.Error, task.status)
            assertEquals("existing worktree path is missing or not a directory", task.errorMessage)
            assertEquals(missing, task.worktreePath)
            assertNull(task.cwd)
            // Must not have launched against the scratch fallback directory.
            val scratch = AgentScratchWorkspace.path().absolutePath
            assertTrue(harness.service.tasks.value.none { it.id == task.id && it.cwd == scratch })
        }
    }

    @Test
    fun worktreeTreePrefersOwnsWorktreeOwnerWhenPathIsShared() = runBlocking {
        withHarness { harness ->
            val owner = harness.startWorktreeTask(title = "owner")
            val path = assertNotNull(owner.worktreePath)
            val branch = assertNotNull(owner.branchName)
            // Workflow reuse: a second task points at the same worktree but does not own it.
            val reused = harness.service.createAndStart(
                AgentTaskDraft(
                    title = "review reuse",
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = harness.repo.absolutePath,
                    useWorktree = false,
                    existingWorktreePath = path,
                    existingBranchName = branch,
                ),
            )
            withTimeout(15_000) {
                while (true) {
                    val current = harness.service.tasks.value.first { it.id == reused.id }
                    if (current.worktreePath != null || current.status == AgentStatus.Error) break
                    delay(25)
                }
            }
            val reuseTask = harness.service.tasks.value.first { it.id == reused.id }
            assertEquals(path, reuseTask.worktreePath)
            assertFalse(reuseTask.ownsWorktree)

            val node = harness.service.worktreeTree(harness.repo.absolutePath)
                .first { File(it.path).canonicalPath == File(path).canonicalPath }
            assertEquals(owner.id, node.taskId)
            assertTrue(node.tracked)
        }
    }

    @Test
    fun deadBaseWorktreeIsExcludedFromPickerAndFailsFast() = runBlocking {
        withHarness { harness ->
            val parent = harness.startWorktreeTask(title = "parent")
            val parentPath = assertNotNull(parent.worktreePath)
            // Remove the on-disk worktree while leaving the Andy task record (and branch) intact.
            // Linux CI occasionally leaves residue after `git worktree remove --force` ("Directory not
            // empty" or a surviving directory); retry force-delete + prune until gone.
            val dead = File(parentPath)
            runCatching { git(harness.repo, "worktree", "remove", "--force", parentPath) }
            for (attempt in 0 until 8) {
                if (!dead.exists()) break
                dead.walkBottomUp().forEach { runCatching { it.setWritable(true); it.delete() } }
                dead.deleteRecursively()
                runCatching { git(harness.repo, "worktree", "prune") }
                if (dead.exists() && attempt < 7) Thread.sleep(50L * (attempt + 1))
            }
            assertFalse(dead.exists(), "worktree dir still present after remove/prune: $parentPath")
            assertTrue(harness.service.tasks.value.any { it.id == parent.id && it.branchName != null })

            val options = harness.service.worktreeBaseOptions(harness.repo.absolutePath)
            assertTrue(options.none { it.taskId == parent.id })

            val child = harness.service.createAndStart(
                AgentTaskDraft(
                    title = "child",
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = harness.repo.absolutePath,
                    useWorktree = true,
                    baseWorktreeTaskId = parent.id,
                ),
            )
            withTimeout(15_000) {
                while (true) {
                    val current = harness.service.tasks.value.first { it.id == child.id }
                    if (current.status == AgentStatus.Error || current.worktreePath != null) break
                    delay(25)
                }
            }
            val failed = harness.service.tasks.value.first { it.id == child.id }
            assertEquals(AgentStatus.Error, failed.status)
            assertEquals("base worktree no longer exists", failed.errorMessage)
            assertNull(failed.worktreePath)
            assertNull(failed.parentWorktreeTaskId)
        }
    }

    @Test
    fun worktreeTreeMarksExternalAndOrphanedWorktreesUntracked() = runBlocking {
        withHarness { harness ->
            val tracked = harness.startWorktreeTask(title = "tracked")
            val external = File(harness.repo, "external-wt")
            git(harness.repo, "worktree", "add", "-b", "external-branch", external.absolutePath)

            val orphanPath = tracked.worktreePath!!
            harness.service.delete(tracked.id, removeWorktree = false)
            assertTrue(File(orphanPath).isDirectory)

            val tree = harness.service.worktreeTree(harness.repo.absolutePath)
            val externalNode = tree.first { File(it.path).canonicalPath == external.canonicalPath }
            assertFalse(externalNode.tracked)
            assertEquals("external-branch", externalNode.branch)

            val orphanNode = tree.first { File(it.path).canonicalPath == File(orphanPath).canonicalPath }
            assertFalse(orphanNode.tracked)
            assertEquals(tracked.branchName, orphanNode.branch)

            val main = tree.first { it.isMain }
            assertTrue(main.tracked.not() || main.taskId == null || main.isMain)
        }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return

        val root = File.createTempFile("andy-nested-wt", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val repo = File(root, "repo").also { it.mkdirs() }
            git(repo, "init")
            git(repo, "config", "user.email", "andy@example.test")
            git(repo, "config", "user.name", "Andy Test")
            git(repo, "checkout", "-B", "main")
            File(repo, "README").writeText("root\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")

            val dbFile = File(root, "agents.db")
            val store = DesktopAgentTaskStore(dbFile)
            store.save(
                AgentStoreState(
                    binaryOverrides = harnessBinaryOverrides(),
                    providerDefaults = harnessTerminalProviderDefaults(),
                ),
            )
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to FastExitAdapter()),
                worktrees = WorktreeManager(File(root, "worktrees")),
                mcp = NestedFakeMcp(),
                workspaceStore = NestedFakeWorkspaceStore(),
                actionConfig = NestedFakeActionConfig(),
                enableProbes = false,
                terminalMode = AgentTerminalMode.DirectPty,
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }
            block(Harness(repo, dbFile, service))
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private inner class Harness(
        val repo: File,
        val dbFile: File,
        val service: DesktopAgentRunService,
    ) {
        suspend fun startWorktreeTask(
            title: String,
            baseWorktreeTaskId: String? = null,
        ): AgentTask {
            val task = service.createAndStart(
                AgentTaskDraft(
                    title = title,
                    prompt = "noop",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = repo.absolutePath,
                    useWorktree = true,
                    baseWorktreeTaskId = baseWorktreeTaskId,
                ),
            )
            withTimeout(15_000) {
                while (true) {
                    val current = service.tasks.value.first { it.id == task.id }
                    if (current.worktreePath != null || current.status == AgentStatus.Error) break
                    delay(25)
                }
            }
            val current = service.tasks.value.first { it.id == task.id }
            assertNull(current.errorMessage, current.errorMessage)
            assertNotNull(current.worktreePath)
            return current
        }

        fun commitOnBranch(branch: String, fileName: String, contents: String): String {
            git(repo, "checkout", "-b", branch)
            File(repo, fileName).writeText(contents)
            git(repo, "add", ".")
            git(repo, "commit", "-m", "commit $branch")
            return revParse(repo, branch)
        }

        fun checkout(branch: String) {
            git(repo, "checkout", branch)
        }
    }

    private class FastExitAdapter : AgentCliAdapter {
        override val kind = AgentKind.Codex
        override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
            listOf(binary, "-c", "printf 'done\\n'")
        override fun buildInteractiveResumeCommand(
            binary: String,
            task: AgentTask,
            mcpUrl: String?,
            followUp: String?,
            followUpImagePaths: List<String>,
        ): List<String> = listOf(binary, "-c", "printf 'resumed\\n'")
        override fun interactiveResumeCommand(binary: String, task: AgentTask): String = shellQuote(binary)
    }

    private class NestedFakeMcp : McpServerService {
        override val status = MutableStateFlow("stopped")
        override val running = MutableStateFlow(false)
        override suspend fun start(port: Int): CommandResult = CommandResult.success()
        override suspend fun stop(): CommandResult = CommandResult.success()
        override fun getSnippet(clientName: String, port: Int): String = ""
        override fun getClients(): List<String> = emptyList()
        override fun isAutoWriteSupported(clientName: String): Boolean = false
        override fun writeConfig(clientName: String, port: Int): Boolean = false
        override fun getToolNames(): List<String> = emptyList()
    }

    private class NestedFakeWorkspaceStore : WorkspaceStore {
        override suspend fun load(): WorkspaceState = WorkspaceState()
        override suspend fun save(state: WorkspaceState) = Unit
    }

    private class NestedFakeActionConfig : ActionConfigStore {
        override suspend fun load(): ActionsConfig = ActionsConfig(
            projects = listOf(ActionProject(id = "proj-1", name = "Repo", contextDir = "/tmp")),
        )
        override suspend fun save(config: ActionsConfig) = Unit
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun gitOutput(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private fun revParse(dir: File, ref: String): String = gitOutput(dir, "rev-parse", ref).trim()
}
