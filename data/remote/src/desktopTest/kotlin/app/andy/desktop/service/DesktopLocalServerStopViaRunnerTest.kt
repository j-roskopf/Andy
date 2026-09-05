package app.andy.desktop.service

import app.andy.desktop.service.remote.SwappableLocalServerService
import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.LocalServerProcess
import app.andy.service.LocalServerService
import app.andy.service.UnavailableActionRunService
import app.andy.service.UnavailableAgentRunService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

class DesktopLocalServerStopViaRunnerTest {
    @Test
    fun stopSignalsThroughRunnerNotLocalProcessHandle() = runBlocking {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        org.junit.Assume.assumeTrue(!os.contains("windows"))

        val kills = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            when (command.firstOrNull()) {
                "lsof" -> if (command.contains("cwd")) {
                    CommandResult.success("p4242\nn/tmp/app")
                } else {
                    CommandResult.success("p4242\ncnode\nn127.0.0.1:5173")
                }
                "ps" -> CommandResult.success("4242 1 node /tmp/app ANDY_TASK_ID=task-1 vite")
                "kill" -> {
                    kills += command
                    if (command.getOrNull(1) == "-0") {
                        if (kills.none { it.getOrNull(1) == "-TERM" }) {
                            CommandResult.success("")
                        } else {
                            CommandResult.failure("No such process", 1)
                        }
                    } else {
                        CommandResult.success("")
                    }
                }
                else -> CommandResult.success("")
            }
        }
        val agents = object : AgentRunService by UnavailableAgentRunService {
            override val tasks = MutableStateFlow(
                listOf(
                    AgentTask(
                        id = "task-1",
                        title = "t",
                        prompt = "",
                        agent = AgentKind.Codex,
                        status = AgentStatus.Working,
                        createdAtMillis = 1,
                        autonomy = AgentAutonomy.Standard,
                        cwd = "/tmp/app",
                    ),
                ),
            )
            override val interactiveTerminalTaskIds = MutableStateFlow(setOf("task-1"))
            override fun sessionRootPid(taskId: String): Long? = 4242L
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DesktopLocalServerService(
            runner = runner,
            agentRuns = agents,
            actionRuns = UnavailableActionRunService,
            scope = scope,
        )
        try {
            service.refresh()
            assertTrue(service.servers.value.any { it.pid == 4242 })
            val result = service.stop(4242, 5173)
            assertTrue(result.isSuccess, result.stderr)
            assertTrue(kills.any { it == listOf("kill", "-TERM", "4242") })
            assertTrue(kills.any { it.getOrNull(1) == "-0" })
        } finally {
            service.dispose()
            scope.cancel()
        }
    }
}

class SwappableLocalServerServiceTest {
    @Test
    fun switchTransfersWatchingAndStopRouting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aWatch = AtomicInteger(0)
        val bWatch = AtomicInteger(0)
        val a = CountingLocalServers("a", aWatch)
        val b = CountingLocalServers("b", bWatch)
        val swappable = SwappableLocalServerService(a, scope)
        try {
            swappable.startWatching()
            assertEquals(1, aWatch.get())
            assertEquals(0, bWatch.get())
            swappable.switchTo(b)
            assertEquals(0, aWatch.get())
            assertEquals(1, bWatch.get())
            assertEquals("b", swappable.stop(1, 80).stdout.trim())
        } finally {
            scope.cancel()
        }
    }

    private class CountingLocalServers(
        private val name: String,
        private val watch: AtomicInteger,
    ) : LocalServerService {
        override val servers: StateFlow<List<LocalServerProcess>> =
            MutableStateFlow(emptyList())

        override fun startWatching() {
            watch.incrementAndGet()
        }

        override fun stopWatching() {
            watch.decrementAndGet()
        }

        override suspend fun refresh() = Unit

        override suspend fun stop(pid: Int, port: Int): CommandResult =
            CommandResult.success(name)
    }
}
