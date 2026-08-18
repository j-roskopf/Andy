package app.andy.desktop.service

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.UnavailableActionRunService
import app.andy.service.UnavailableAgentRunService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Fake with a large chat history, mirroring a real Andy install with hundreds of past tasks. */
private class FakeHistoryAgentRunService(
    historyTaskIds: List<String>,
    private val liveTaskIds: Set<String>,
) : AgentRunService by UnavailableAgentRunService {
    val sessionRootPidCalls = mutableListOf<String>()
    override val tasks: StateFlow<List<AgentTask>> = MutableStateFlow(
        historyTaskIds.map { id ->
            AgentTask(
                id = id,
                title = id,
                prompt = "",
                agent = AgentKind.Codex,
                status = AgentStatus.Done,
                createdAtMillis = 1,
                autonomy = AgentAutonomy.Standard,
            )
        },
    )
    override val interactiveTerminalTaskIds: StateFlow<Set<String>> = MutableStateFlow(liveTaskIds)
    override fun sessionRootPid(taskId: String): Long? {
        sessionRootPidCalls += taskId
        return null
    }
}

class DesktopLocalServerServiceTest {
    @Test
    fun doesNotScanUntilStartWatching() = runBlocking {
        val runs = AtomicInteger(0)
        val runner = CommandRunner { _, _ ->
            runs.incrementAndGet()
            CommandResult.success("")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DesktopLocalServerService(
            runner = runner,
            agentRuns = UnavailableAgentRunService,
            actionRuns = UnavailableActionRunService,
            scope = scope,
            pollIntervalMs = 50L,
        )
        try {
            delay(150)
            assertEquals(0, runs.get(), "init must not fork lsof/ps before startWatching")

            service.startWatching()
            withTimeout(2_000) {
                while (runs.get() < 1) delay(10)
            }
            assertTrue(runs.get() >= 1)

            service.stopWatching()
            val afterStop = runs.get()
            delay(200)
            assertEquals(afterStop, runs.get(), "stopWatching must cancel the poll loop")
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    @Test
    fun repeatedScanWithUnchangedPidsSkipsPsAndCwdForks() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            when (command.firstOrNull()) {
                "lsof" -> if (command.contains("cwd")) {
                    CommandResult.success("p4242\nn/tmp/app")
                } else {
                    CommandResult.success("p4242\ncnode\nn127.0.0.1:5173")
                }
                "ps" -> CommandResult.success("4242 1 node /tmp/app/server.js")
                else -> CommandResult.success("")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DesktopLocalServerService(
            runner = runner,
            agentRuns = UnavailableAgentRunService,
            actionRuns = UnavailableActionRunService,
            scope = scope,
        )
        try {
            service.refresh()
            val forksAfterFirstScan = commands.size
            assertTrue(forksAfterFirstScan > 1, "first scan must fork ps/lsof-cwd to attribute the new pid")

            commands.clear()
            service.refresh()
            // Same pid still listening on the same port: only the cheap lsof listener
            // scan should fork again, not ps or lsof -d cwd.
            assertEquals(1, commands.size, "unchanged pid set must skip ps/lsof-cwd forks")
            assertEquals("lsof", commands.single().first())
            assertFalse(commands.single().contains("cwd"))
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    @Test
    fun ownerAttributionOnlyForksTmuxForLiveChats() = runBlocking {
        // p9999 has no matching Andy task/cwd, so attribution is never satisfied and every
        // task in a deep chat history would previously be probed via a tmux fork pair.
        val runner = CommandRunner { command, _ ->
            when (command.firstOrNull()) {
                "lsof" -> if (command.contains("cwd")) {
                    CommandResult.success("p9999\nn/tmp/unrelated")
                } else {
                    CommandResult.success("p9999\ncnode\nn127.0.0.1:5173")
                }
                "ps" -> CommandResult.success("9999 1 node /tmp/unrelated/server.js")
                else -> CommandResult.success("")
            }
        }
        val historyIds = (1..200).map { "task-$it" }
        val agentRuns = FakeHistoryAgentRunService(
            historyTaskIds = historyIds,
            liveTaskIds = setOf("task-5", "task-42"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DesktopLocalServerService(
            runner = runner,
            agentRuns = agentRuns,
            actionRuns = UnavailableActionRunService,
            scope = scope,
        )
        try {
            service.refresh()
            assertEquals(
                setOf("task-5", "task-42"),
                agentRuns.sessionRootPidCalls.toSet(),
                "only chats with a live in-memory session may fork tmux to resolve a root pid",
            )
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    @Test
    fun processInfoWalksGrandparentPids() = runBlocking {
        val psPFlags = mutableListOf<String>()
        val runner = CommandRunner { command, _ ->
            when (command.firstOrNull()) {
                "lsof" -> if (command.contains("cwd")) {
                    CommandResult.success("p300\nn/tmp/app")
                } else {
                    CommandResult.success("p300\ncnode\nn127.0.0.1:5173")
                }
                "ps" -> {
                    val pFlag = command.getOrNull(command.indexOf("-p") + 1).orEmpty()
                    psPFlags += pFlag
                    val wanted = pFlag.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val rows = buildList {
                        if ("300" in wanted) add("300 200 node /tmp/app/node_modules/vite/bin/vite.js --port 5173")
                        if ("200" in wanted) add("200 100 npm run dev")
                        if ("100" in wanted) add("100 1 bash -lc serve")
                    }
                    CommandResult.success(rows.joinToString("\n"))
                }
                else -> CommandResult.success("")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DesktopLocalServerService(
            runner = runner,
            agentRuns = UnavailableAgentRunService,
            actionRuns = UnavailableActionRunService,
            scope = scope,
        )
        try {
            service.refresh()
            assertTrue(psPFlags.any { "300" in it }, "listener pid must be queried")
            assertTrue(psPFlags.any { "200" in it }, "immediate parent must be queried")
            assertTrue(psPFlags.any { "100" in it }, "grandparent pid must be queried for lineage depth")
        } finally {
            service.dispose()
            scope.cancel()
        }
    }
}
