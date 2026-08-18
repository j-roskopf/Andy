package app.andy.desktop.service

import app.andy.desktop.service.agents.AndyStatusHookInstaller
import app.andy.model.ActionRunStatus
import app.andy.service.ActionRunService
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.LocalServerOwnerIdentity
import app.andy.service.LocalServerProcess
import app.andy.service.LocalServerScan
import app.andy.service.LocalServerService
import java.lang.ProcessHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop host scan of likely local dev servers via `lsof` / `ps`, with best-effort
 * attribution to Andy agent chats and project action terminals.
 *
 * Polling is opt-in via [startWatching]/[stopWatching]. An always-on `init` poll forked
 * `lsof`/`ps` from app launch and showed up as sustained `ProcessImpl.forkAndExec` CPU even
 * before the Projects tab (or Local Servers UI) was opened.
 */
class DesktopLocalServerService(
    private val runner: CommandRunner,
    private val agentRuns: AgentRunService,
    private val actionRuns: ActionRunService,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 15_000L,
) : LocalServerService {
    private val _servers = MutableStateFlow<List<LocalServerProcess>>(emptyList())
    override val servers: StateFlow<List<LocalServerProcess>> = _servers

    private val scanMutex = Mutex()
    private val watchCount = AtomicInteger(0)
    private var pollJob: Job? = null

    // Every ProcessBuilder.start() here forks the whole Andy JVM address space (macOS
    // requires jdk.lang.Process.launchMechanism=FORK to launch agent CLIs reliably — see
    // build.gradle.kts — so posix_spawn is not an option). That made a full poll (lsof +
    // ps + ps-for-parents + lsof-cwd) show up as ~75% of process CPU under async-profiler
    // even though each individual call is "just" a subprocess. The listening-pid set rarely
    // changes between polls, so cache the expensive ps/cwd lookups and only redo them when
    // the pid set actually changes.
    private var cachedPids: Set<Int> = emptySet()
    private var cachedProcessInfo: Map<Int, LocalServerScan.ProcessInfo> = emptyMap()
    private var cachedCwdByPid: Map<Int, String> = emptyMap()

    override fun startWatching() {
        if (watchCount.getAndIncrement() > 0) return
        pollJob = scope.launch {
            refresh()
            while (isActive) {
                delay(pollIntervalMs)
                refresh()
            }
        }
    }

    override fun stopWatching() {
        if (watchCount.updateAndGet { (it - 1).coerceAtLeast(0) } > 0) return
        pollJob?.cancel()
        pollJob = null
    }

    fun dispose() {
        watchCount.set(0)
        pollJob?.cancel()
        pollJob = null
    }

    override suspend fun refresh() {
        if (!supportsPosixScan()) {
            _servers.value = emptyList()
            return
        }
        scanMutex.withLock {
            _servers.value = withContext(Dispatchers.IO) { scanOnce() }
        }
    }

    override suspend fun stop(pid: Int, port: Int): CommandResult = withContext(Dispatchers.IO) {
        if (pid <= 0 || port !in 1..65535) {
            return@withContext CommandResult.failure("Invalid process or port")
        }
        val snapshot = scanOnce()
        val target = snapshot.firstOrNull { it.pid == pid && port in it.ports }
            ?: return@withContext CommandResult.failure("That process is no longer a listed local server.")
        if (!target.isStoppable) {
            return@withContext CommandResult.failure(target.stopDisabledReason ?: "Cannot stop this process.")
        }
        val handle = ProcessHandle.of(pid.toLong())
        if (handle.isEmpty || !handle.get().isAlive) {
            refresh()
            return@withContext CommandResult.failure("Process $pid is not running.")
        }
        val process = handle.get()
        process.destroy()
        delay(STOP_SIGNAL_SETTLE_MS)
        if (process.isAlive) {
            process.destroyForcibly()
            delay(STOP_SIGNAL_SETTLE_MS)
        }
        if (process.isAlive) {
            runner.run(listOf("kill", "-9", pid.toString()), timeoutSeconds = 5)
        }
        refresh()
        if (ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)) {
            CommandResult.failure("Failed to stop pid $pid")
        } else {
            CommandResult.success("Stopped ${target.displayName} on localhost:$port")
        }
    }

    private suspend fun scanOnce(): List<LocalServerProcess> {
        val lsof = runner.run(
            listOf("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcPn"),
            timeoutSeconds = 8,
        )
        if (!lsof.isSuccess && lsof.stdout.isBlank()) {
            cachedPids = emptySet()
            return emptyList()
        }
        val listeners = LocalServerScan.parseLsofTcpListenOutput(lsof.stdout)
        if (listeners.isEmpty()) {
            cachedPids = emptySet()
            return emptyList()
        }

        val pids = listeners.map { it.pid }.distinct()
        val pidSet = pids.toSet()
        val processInfo: Map<Int, LocalServerScan.ProcessInfo>
        val cwdByPid: Map<Int, String>
        if (pidSet == cachedPids) {
            processInfo = cachedProcessInfo
            cwdByPid = cachedCwdByPid
        } else {
            processInfo = readProcessInfo(pids)
            val lineagePids = expandLineagePids(pids, processInfo)
            cwdByPid = readProcessCwds(lineagePids)
            cachedPids = pidSet
            cachedProcessInfo = processInfo
            cachedCwdByPid = cwdByPid
        }
        val owners = ownerIdentities()
        // Do not call ProcessHandle.isAlive per pid here — that is another syscall storm on
        // every poll. Stopability is re-checked in [stop] before signaling.
        return LocalServerScan.buildLocalServerProcesses(
            listeners = listeners,
            processInfoByPid = processInfo,
            cwdByPid = cwdByPid,
            owners = owners,
        )
    }

    private fun ownerIdentities(): List<LocalServerOwnerIdentity> {
        // agentRuns.tasks is the full chat history, not just open/live chats. For any task
        // this app run has no in-memory session for, sessionRootPid() falls through to a
        // `tmux has-session` + `tmux display-message` fork pair (TmuxAndy.panePid) to check a
        // prior run's tmux server — that answer is always null for a chat with no live
        // session, so calling it per historical task forked tmux dozens of times a poll on a
        // deep chat history. Only chats this run still hosts an interactive session for can
        // possibly own a live listener, so gate the lookup on that (cheap, in-memory) set.
        val liveTaskIds = agentRuns.interactiveTerminalTaskIds.value
        val chats = agentRuns.tasks.value.map { task ->
            LocalServerOwnerIdentity(
                id = task.id,
                title = task.title.ifBlank { task.id },
                projectId = task.projectId,
                cwd = task.cwd,
                worktreePath = task.worktreePath?.takeIf { it.isNotBlank() },
                rootPid = if (task.id in liveTaskIds) agentRuns.sessionRootPid(task.id) else null,
                kind = LocalServerOwnerIdentity.Kind.Chat,
            )
        }
        val activeStatuses = setOf(ActionRunStatus.Starting, ActionRunStatus.Running)
        val actions = actionRuns.running.value
            .filter { it.status in activeStatuses }
            .map { run ->
                LocalServerOwnerIdentity(
                    id = run.runId,
                    title = run.actionName.ifBlank { run.runId },
                    projectId = run.projectId,
                    cwd = run.cwd,
                    rootPid = actionRuns.sessionRootPid(run.runId),
                    kind = LocalServerOwnerIdentity.Kind.Action,
                )
            }
        return chats + actions
    }

    private suspend fun readProcessInfo(pids: List<Int>): Map<Int, LocalServerScan.ProcessInfo> {
        if (pids.isEmpty()) return emptyMap()
        val byPid = mutableMapOf<Int, LocalServerScan.ProcessInfo>()
        // Prefer env-aware listing so ANDY_TASK_ID is visible when inherited.
        val eww = runner.run(
            listOf("ps", "eww", "-ww", "-o", "pid=,ppid=,command=", "-p", pids.joinToString(",")),
            timeoutSeconds = 8,
        )
        if (eww.isSuccess || eww.stdout.isNotBlank()) {
            byPid.putAll(LocalServerScan.parsePsProcessTable(eww.stdout))
        }
        val missing = pids.filter { it !in byPid }
        if (missing.isNotEmpty()) {
            val plain = runner.run(
                listOf("ps", "-ww", "-o", "pid=,ppid=,command=", "-p", missing.joinToString(",")),
                timeoutSeconds = 8,
            )
            if (plain.isSuccess || plain.stdout.isNotBlank()) {
                byPid.putAll(LocalServerScan.parsePsProcessTable(plain.stdout))
            }
        }
        // Walk parents iteratively so lineage classification / ANDY_TASK_ID can climb
        // past wrapper processes (shell -> npm -> node) up to ProcessLineageMaxDepth.
        repeat(LocalServerScan.ProcessLineageMaxDepth) {
            val parents = byPid.values.map { it.ppid }.filter { it > 1 && it !in byPid }.distinct()
            if (parents.isEmpty()) return@repeat
            val parentPs = runner.run(
                listOf("ps", "eww", "-ww", "-o", "pid=,ppid=,command=", "-p", parents.joinToString(",")),
                timeoutSeconds = 8,
            )
            val before = byPid.size
            if (parentPs.isSuccess || parentPs.stdout.isNotBlank()) {
                byPid.putAll(LocalServerScan.parsePsProcessTable(parentPs.stdout))
            }
            if (byPid.size == before) return@repeat
        }
        // Fallback: read /proc/<pid>/environ on Linux when ps eww omitted the env.
        for (pid in pids) {
            val existing = byPid[pid] ?: continue
            if (existing.andyTaskId != null) continue
            readProcEnvironTaskId(pid)?.let { taskId ->
                byPid[pid] = existing.copy(andyTaskId = taskId)
            }
        }
        return byPid
    }

    private suspend fun readProcessCwds(pids: List<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()
        val result = runner.run(
            listOf("lsof", "-a", "-d", "cwd", "-Fn", "-p", pids.joinToString(",")),
            timeoutSeconds = 8,
        )
        if (!result.isSuccess && result.stdout.isBlank()) return emptyMap()
        return LocalServerScan.parseLsofCwdOutput(result.stdout)
    }

    private fun expandLineagePids(
        pids: List<Int>,
        processInfo: Map<Int, LocalServerScan.ProcessInfo>,
    ): List<Int> {
        val all = linkedSetOf<Int>()
        all.addAll(pids)
        for (pid in pids) {
            var current = pid
            repeat(LocalServerScan.ProcessLineageMaxDepth) {
                val ppid = processInfo[current]?.ppid ?: return@repeat
                if (ppid <= 1) return@repeat
                all += ppid
                current = ppid
            }
        }
        return all.toList()
    }

    private fun readProcEnvironTaskId(pid: Int): String? {
        val file = java.io.File("/proc/$pid/environ")
        if (!file.isFile) return null
        return runCatching {
            val text = file.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            LocalServerScan.extractAndyTaskId(text)
                ?: Regex("""${AndyStatusHookInstaller.TASK_ID_ENV}=([^\s]+)""")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun supportsPosixScan(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return !os.contains("windows")
    }

    companion object {
        private const val STOP_SIGNAL_SETTLE_MS = 450L
    }
}
