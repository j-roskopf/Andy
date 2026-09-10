package app.andy.desktop.service.plugins

import app.andy.model.InstalledPluginInfo
import app.andy.model.InstalledPluginRecord
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.PluginCommandLog
import app.andy.model.PluginInvocationContext
import app.andy.model.PluginManifest
import app.andy.model.PluginManifestAction
import app.andy.model.PluginPaneOpenRequest
import app.andy.model.PluginPanePlacement
import app.andy.model.PluginPaneSession
import app.andy.model.PluginPlatform
import app.andy.model.PluginSourceInfo
import app.andy.model.PluginSourceKind
import app.andy.model.toPluginWireStatus
import app.andy.service.ActionRunService
import app.andy.service.AgentRunService
import app.andy.service.PluginService
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DesktopPluginService(
    private val scope: CoroutineScope,
    private val actionRuns: ActionRunService,
    private val store: PluginRegistryStore = PluginRegistryStore(),
    private val andyVersion: () -> String,
    private val andyBinPath: () -> String = {
        File(System.getProperty("user.home"), ".andy/bin/andy").absolutePath
    },
    private val andySocketPath: () -> String = {
        File(System.getProperty("user.home"), ".andy/andyd.sock").absolutePath
    },
    private val currentPlatform: () -> PluginPlatform = {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") || os.contains("darwin") -> PluginPlatform.Macos
            os.contains("win") -> PluginPlatform.Windows
            else -> PluginPlatform.Linux
        }
    },
) : PluginService {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val startupRan = AtomicBoolean(false)
    private val manifestCache = ConcurrentHashMap<String, PluginManifest>()
    @Volatile private var registryMtime: Long = -1L

    private val _plugins = MutableStateFlow<List<InstalledPluginInfo>>(emptyList())
    override val plugins: StateFlow<List<InstalledPluginInfo>> = _plugins.asStateFlow()

    private val _commandLogs = MutableStateFlow<List<PluginCommandLog>>(emptyList())
    override val commandLogs: StateFlow<List<PluginCommandLog>> = _commandLogs.asStateFlow()

    private val _openPanes = MutableStateFlow<List<PluginPaneSession>>(emptyList())
    override val openPanes: StateFlow<List<PluginPaneSession>> = _openPanes.asStateFlow()

    private val _paneOpenRequests = MutableSharedFlow<PluginPaneOpenRequest>(extraBufferCapacity = 16)
    override val paneOpenRequests: Flow<PluginPaneOpenRequest> = _paneOpenRequests

    init {
        // Eager load so runStartupHooks does not race an async refresh against the
        // same JVM FileChannel.lock (OverlappingFileLockException).
        runCatching { reloadFromStoreLocked() }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    override suspend fun refresh() = mutex.withLock {
        reloadFromStoreLocked()
    }

    /** Reload registry from disk when another process (CLI ↔ GUI) changed plugins.json. */
    private fun syncFromDiskIfStale() {
        val mtime = store.registryFile.lastModified()
        if (mtime == registryMtime) return
        synchronized(this) {
            val again = store.registryFile.lastModified()
            if (again == registryMtime) return
            runCatching {
                val records = store.load().plugins
                val infos = records.mapNotNull { record ->
                    runCatching { toInfo(record) }.getOrNull()
                }
                _plugins.value = infos.sortedBy { it.pluginId }
                registryMtime = again
            }
        }
    }

    private fun reloadFromStoreLocked() {
        val records = store.load().plugins
        val infos = records.mapNotNull { record ->
            runCatching { toInfo(record) }.getOrNull()
        }
        _plugins.value = infos.sortedBy { it.pluginId }
        registryMtime = store.registryFile.lastModified()
    }

    override suspend fun link(path: String, enabled: Boolean): InstalledPluginInfo = mutex.withLock {
        val root = File(path).let { file ->
            if (file.isAbsolute) file.canonicalFile else File(file.absolutePath).canonicalFile
        }
        if (!root.exists()) {
            error(
                "plugin_path_not_found: $path " +
                    "(resolved to ${root.absolutePath}; use an absolute path when linking via Settings)",
            )
        }
        val manifest = PluginManifestLoader.load(root, andyVersion())
        val existing = store.find(manifest.id)
        if (existing != null && existing.source.kind == PluginSourceKind.Github) {
            error("Installing over a GitHub-managed plugin is refused; uninstall first")
        }
        val manifestPath = File(if (root.isDirectory) root else root.parentFile, "andy-plugin.toml")
            .takeIf { it.isFile }
            ?: File(root, "andy-plugin.toml").takeIf { root.isDirectory && it.isFile }
            ?: root
        val pluginRoot = if (root.isDirectory) root else root.parentFile
        store.configDir(manifest.id)
        store.stateDir(manifest.id)
        val record = InstalledPluginRecord(
            pluginId = manifest.id,
            enabled = enabled,
            source = PluginSourceInfo(
                kind = PluginSourceKind.Local,
                path = pluginRoot.absolutePath,
            ),
            manifestPath = manifestPath.absolutePath,
            pluginRoot = pluginRoot.absolutePath,
        )
        store.upsert(record)
        manifestCache[manifest.id] = manifest
        val info = toInfo(record, manifest)
        _plugins.update { list -> (list.filterNot { it.pluginId == info.pluginId } + info).sortedBy { it.pluginId } }
        registryMtime = store.registryFile.lastModified()
        info
    }

    override suspend fun unlink(pluginId: String) = mutex.withLock {
        store.remove(pluginId)
        manifestCache.remove(pluginId)
        _plugins.update { it.filterNot { p -> p.pluginId == pluginId } }
        _openPanes.update { panes -> panes.filterNot { it.pluginId == pluginId } }
        registryMtime = store.registryFile.lastModified()
    }

    override suspend fun installGithub(spec: String, ref: String?, yes: Boolean): InstalledPluginInfo =
        mutex.withLock {
            val normalized = normalizeGithubSpec(spec)
            val existingLocal = store.findByGithubSpec(normalized)
                ?: store.load().plugins.firstOrNull {
                    it.source.kind == PluginSourceKind.Local &&
                        it.source.path.contains(normalized.substringAfterLast('/'))
                }
            if (existingLocal?.source?.kind == PluginSourceKind.Local) {
                error("Installing over a locally linked plugin is refused; unlink first")
            }
            store.managedRoot.mkdirs()
            val temp = File(store.managedRoot, ".tmp-${UUID.randomUUID()}")
            try {
                cloneGithub(normalized, ref, temp)
                val pluginDir = locatePluginDir(temp, normalized)
                val manifest = PluginManifestLoader.load(pluginDir, andyVersion())
                if (!yes) {
                    // Build commands execute arbitrary repository code as the user. Interactive
                    // preview/confirmation is CLI-side; without --yes we must not run them.
                    error(
                        "plugin_confirmation_required: " +
                            "installing $normalized runs its [[build]] commands; re-run with --yes to confirm",
                    )
                }
                runBuildCommands(manifest, pluginDir)
                val dest = store.managedCheckout(manifest.id)
                if (dest.exists()) dest.deleteRecursively()
                pluginDir.copyRecursively(dest, overwrite = true)
                store.configDir(manifest.id)
                store.stateDir(manifest.id)
                val record = InstalledPluginRecord(
                    pluginId = manifest.id,
                    enabled = true,
                    source = PluginSourceInfo(
                        kind = PluginSourceKind.Github,
                        path = dest.absolutePath,
                        githubRef = ref,
                        githubSpec = normalized,
                    ),
                    manifestPath = File(dest, "andy-plugin.toml").absolutePath,
                    pluginRoot = dest.absolutePath,
                )
                store.upsert(record)
                manifestCache[manifest.id] = manifest
                val info = toInfo(record, manifest)
                _plugins.update { list ->
                    (list.filterNot { it.pluginId == info.pluginId } + info).sortedBy { it.pluginId }
                }
                info
            } finally {
                temp.deleteRecursively()
            }
        }.also { registryMtime = store.registryFile.lastModified() }

    override suspend fun uninstall(pluginIdOrSpec: String) = mutex.withLock {
        val record = store.find(pluginIdOrSpec)
            ?: store.findByGithubSpec(normalizeGithubSpec(pluginIdOrSpec))
            ?: error("Unknown plugin: $pluginIdOrSpec")
        store.remove(record.pluginId)
        manifestCache.remove(record.pluginId)
        if (record.source.kind == PluginSourceKind.Github) {
            File(record.pluginRoot).deleteRecursively()
        }
        _openPanes.value
            .filter { it.pluginId == record.pluginId }
            .forEach { pane ->
                actionRuns.stop(pane.runId)
                actionRuns.clear(pane.runId)
            }
        _plugins.update { it.filterNot { p -> p.pluginId == record.pluginId } }
        _openPanes.update { panes -> panes.filterNot { it.pluginId == record.pluginId } }
        registryMtime = store.registryFile.lastModified()
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) = mutex.withLock {
        store.setEnabled(pluginId, enabled)
        _plugins.update { list ->
            list.map { if (it.pluginId == pluginId) it.copy(enabled = enabled) else it }
        }
        registryMtime = store.registryFile.lastModified()
    }

    override fun configDir(pluginId: String): String = store.configDir(pluginId).absolutePath

    override suspend fun listActions(pluginId: String?): List<Pair<InstalledPluginInfo, PluginManifestAction>> {
        val list = _plugins.value.filter { pluginId == null || it.pluginId == pluginId }
        return list.flatMap { info -> info.actions.map { info to it } }
    }

    override suspend fun invokeAction(
        actionId: String,
        pluginId: String?,
        context: PluginInvocationContext,
    ): PluginCommandLog {
        syncFromDiskIfStale()
        val (info, action) = resolveAction(actionId, pluginId)
        ensurePlatform(info, action.platforms)
        val ctx = context.copy(
            invocationSource = context.invocationSource ?: "action",
            correlationId = context.correlationId ?: UUID.randomUUID().toString(),
        )
        return runCommand(
            plugin = info,
            kind = "action:${action.id}",
            command = action.command,
            context = ctx,
            extraEnv = mapOf("ANDY_PLUGIN_ACTION_ID" to action.id),
        )
    }

    override suspend fun openPane(
        pluginId: String,
        entrypoint: String,
        placement: PluginPanePlacement?,
        context: PluginInvocationContext,
    ): PluginPaneSession {
        syncFromDiskIfStale()
        val info = _plugins.value.firstOrNull { it.pluginId == pluginId }
            ?: error("Unknown plugin: $pluginId")
        if (!info.enabled) error("Plugin is disabled: $pluginId")
        val pane = info.panes.firstOrNull { it.id == entrypoint }
            ?: error("Unknown pane entrypoint: $entrypoint")
        ensurePlatform(info, pane.platforms)
        val resolvedPlacement = placement ?: pane.placement
        val ctx = context.copy(
            invocationSource = context.invocationSource ?: "pane",
            correlationId = context.correlationId ?: UUID.randomUUID().toString(),
        )
        val env = buildEnv(info, ctx) + mapOf("ANDY_PLUGIN_ENTRYPOINT_ID" to pane.id)
        val runId = actionRuns.startCommand(
            title = pane.title,
            argv = pane.command,
            cwd = info.pluginRoot,
            env = env,
            projectId = "plugin:${info.pluginId}",
            actionId = "pane:${pane.id}",
        )
        val session = PluginPaneSession(
            paneId = "${info.pluginId}/${pane.id}/$runId",
            pluginId = info.pluginId,
            entrypoint = pane.id,
            title = pane.title,
            runId = runId,
            placement = resolvedPlacement,
        )
        _openPanes.update { it.filterNot { s -> s.pluginId == info.pluginId && s.entrypoint == pane.id } + session }
        _paneOpenRequests.tryEmit(PluginPaneOpenRequest(runId, pane.title, resolvedPlacement))
        return session
    }

    override suspend fun focusPane(paneId: String): PluginPaneSession? {
        val session = _openPanes.value.firstOrNull { it.paneId == paneId } ?: return null
        _paneOpenRequests.tryEmit(PluginPaneOpenRequest(session.runId, session.title, session.placement))
        return session
    }

    override suspend fun closePane(paneId: String) {
        val session = _openPanes.value.firstOrNull { it.paneId == paneId } ?: return
        actionRuns.stop(session.runId)
        actionRuns.clear(session.runId)
        _openPanes.update { it.filterNot { s -> s.paneId == paneId } }
    }

    override fun emitEvent(
        event: String,
        data: Map<String, String>,
        context: PluginInvocationContext,
    ) {
        syncFromDiskIfStale()
        val enabled = _plugins.value.filter { it.enabled }
        val hooks = enabled.flatMap { plugin ->
            plugin.events
                .filter { it.on == event }
                .filter { supportsPlatform(plugin.platforms, it.platforms) }
                .map { plugin to it }
        }
        if (hooks.isEmpty()) return
        val correlation = context.correlationId ?: UUID.randomUUID().toString()
        val ctx = context.copy(
            invocationSource = context.invocationSource ?: "event",
            correlationId = correlation,
        )
        val envelopeJson =
            """{"event":${json.encodeToString(event)},"data":${json.encodeToString(data)}}"""
        hooks.forEach { (plugin, hook) ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    runCommand(
                        plugin = plugin,
                        kind = "event:$event",
                        command = hook.command,
                        context = ctx,
                        extraEnv = mapOf(
                            "ANDY_PLUGIN_EVENT" to event,
                            "ANDY_PLUGIN_EVENT_JSON" to envelopeJson,
                        ),
                    )
                }
            }
        }
    }

    override fun runStartupHooks() {
        syncFromDiskIfStale()
        if (!startupRan.compareAndSet(false, true)) return
        val enabled = _plugins.value.filter { it.enabled }
        enabled.forEach { plugin ->
            plugin.startup
                .filter { supportsPlatform(plugin.platforms, it.platforms) }
                .forEach { hook ->
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            runCommand(
                                plugin = plugin,
                                kind = "startup",
                                command = hook.command,
                                context = PluginInvocationContext(
                                    invocationSource = "startup",
                                    correlationId = UUID.randomUUID().toString(),
                                ),
                                extraEnv = mapOf("ANDY_PLUGIN_EVENT" to "startup"),
                            )
                        }
                    }
                }
        }
    }

    /** Watch agent chat lifecycle and emit pane.* / worktree.* plugin events. */
    fun attachAgentEvents(agentRuns: AgentRunService) {
        // Focus is observed independently of task updates: focusing an already-read chat
        // mutates viewing without re-emitting a task value, so the tasks collector below
        // would otherwise never see the change and never fire pane.focused.
        var previousFocused: String? = agentRuns.viewingTaskId.value
        scope.launch {
            agentRuns.viewingTaskId.collect { focused ->
                if (focused != null && focused != previousFocused) {
                    emitEvent(
                        "pane.focused",
                        mapOf(
                            "pane_id" to focused,
                            "workspace_id" to (agentTaskSnapshots(agentRuns.tasks.value, agentRuns)[focused]?.projectId ?: ""),
                        ),
                        PluginInvocationContext(
                            focusedPaneId = focused,
                        ),
                    )
                }
                previousFocused = focused
            }
        }
        scope.launch {
            // Wait for store hydration, then seed [previous] from the loaded snapshot so
            // existing chats do not fan out pane.created / worktree.* on every boot.
            agentRuns.awaitTasksLoaded()
            var previous = agentTaskSnapshots(agentRuns.tasks.value, agentRuns)
            agentRuns.tasks.collect { tasks ->
                val next = agentTaskSnapshots(tasks, agentRuns)
                next.forEach { (id, snap) ->
                    val old = previous[id]
                    val wireStatus = snap.status?.toPluginWireStatus(seen = snap.viewing)
                    val ctx = PluginInvocationContext(
                        workspaceId = snap.projectId,
                        focusedPaneId = id,
                        focusedPaneAgent = snap.agent,
                        focusedPaneStatus = wireStatus,
                        focusedPaneCwd = snap.cwd,
                        workspaceCwd = snap.cwd,
                        worktreePath = snap.worktreePath,
                    )
                    if (old == null) {
                        emitEvent(
                            "pane.created",
                            mapOf(
                                "pane_id" to id,
                                "workspace_id" to (snap.projectId ?: ""),
                                "agent" to snap.agent,
                                "title" to (snap.title ?: ""),
                            ),
                            ctx,
                        )
                        emitEvent(
                            "pane.agent_detected",
                            mapOf(
                                "pane_id" to id,
                                "workspace_id" to (snap.projectId ?: ""),
                                "agent" to snap.agent,
                            ),
                            ctx,
                        )
                        snap.worktreePath?.let { path ->
                            emitWorktreeEvent(
                                if (snap.ownsWorktree) "worktree.created" else "worktree.opened",
                                path = path,
                                paneId = id,
                                workspaceId = snap.projectId,
                                ctx = ctx,
                            )
                        }
                    } else if (old.status != snap.status && snap.status != null) {
                        val wire = snap.status.toPluginWireStatus(seen = snap.viewing)
                        emitEvent(
                            "pane.agent_status_changed",
                            mapOf(
                                "pane_id" to id,
                                "workspace_id" to (snap.projectId ?: ""),
                                "agent" to snap.agent,
                                "agent_status" to wire,
                                "title" to (snap.title ?: ""),
                                "type" to "pane_agent_status_changed",
                            ),
                            ctx.copy(focusedPaneStatus = wire),
                        )
                    }
                    if (old != null && old.worktreePath != snap.worktreePath) {
                        old.worktreePath?.let { path ->
                            emitWorktreeEvent(
                                "worktree.removed",
                                path = path,
                                paneId = id,
                                workspaceId = snap.projectId,
                                ctx = ctx.copy(worktreePath = path),
                            )
                        }
                        snap.worktreePath?.let { path ->
                            emitWorktreeEvent(
                                if (snap.ownsWorktree) "worktree.created" else "worktree.opened",
                                path = path,
                                paneId = id,
                                workspaceId = snap.projectId,
                                ctx = ctx,
                            )
                        }
                    }
                }
                previous.keys.filter { it !in next }.forEach { id ->
                    val old = previous[id] ?: return@forEach
                    old.worktreePath?.let { path ->
                        emitWorktreeEvent(
                            "worktree.removed",
                            path = path,
                            paneId = id,
                            workspaceId = old.projectId,
                            ctx = PluginInvocationContext(
                                workspaceId = old.projectId,
                                focusedPaneId = id,
                                worktreePath = path,
                            ),
                        )
                    }
                    emitEvent(
                        "pane.closed",
                        mapOf(
                            "pane_id" to id,
                            "workspace_id" to (old.projectId ?: ""),
                        ),
                        PluginInvocationContext(
                            workspaceId = old.projectId,
                            focusedPaneId = id,
                        ),
                    )
                    emitEvent(
                        "pane.exited",
                        mapOf(
                            "pane_id" to id,
                            "workspace_id" to (old.projectId ?: ""),
                        ),
                        PluginInvocationContext(
                            workspaceId = old.projectId,
                            focusedPaneId = id,
                        ),
                    )
                }
                previous = next
            }
        }
    }

    private fun emitWorktreeEvent(
        event: String,
        path: String,
        paneId: String,
        workspaceId: String?,
        ctx: PluginInvocationContext,
    ) {
        emitEvent(
            event,
            mapOf(
                "worktree_path" to path,
                "pane_id" to paneId,
                "workspace_id" to (workspaceId ?: ""),
            ),
            ctx,
        )
    }

    private data class AgentTaskSnapshot(
        val status: AgentStatus?,
        val projectId: String?,
        val title: String?,
        val agent: String,
        val cwd: String?,
        val viewing: Boolean,
        val worktreePath: String?,
        val ownsWorktree: Boolean,
    )

    private fun agentTaskSnapshots(
        tasks: List<AgentTask>,
        agentRuns: AgentRunService,
    ): Map<String, AgentTaskSnapshot> = tasks.associate { task ->
        task.id to AgentTaskSnapshot(
            status = task.status,
            projectId = task.projectId,
            title = task.title,
            agent = task.agent.name,
            cwd = task.cwd,
            viewing = agentRuns.isViewing(task.id),
            worktreePath = task.worktreePath,
            ownsWorktree = task.ownsWorktree,
        )
    }

    private fun resolveAction(
        actionId: String,
        pluginId: String?,
    ): Pair<InstalledPluginInfo, PluginManifestAction> {
        val qualifiedPlugin = actionId.substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf { actionId.contains('.') && pluginId == null }
        val localAction = if (actionId.contains('.') && pluginId == null) {
            actionId.substringAfterLast('.')
        } else {
            actionId
        }
        val resolvedPluginId = pluginId ?: qualifiedPlugin
        val candidates = _plugins.value.filter { resolvedPluginId == null || it.pluginId == resolvedPluginId }
        val matches = candidates.mapNotNull { info ->
            info.actions.firstOrNull { it.id == localAction || "${info.pluginId}.${it.id}" == actionId }
                ?.let { info to it }
        }
        when (matches.size) {
            0 -> error("Unknown action: $actionId")
            1 -> {
                val (info, action) = matches.single()
                if (!info.enabled) error("Plugin is disabled: ${info.pluginId}")
                return info to action
            }
            else -> error("Ambiguous action id '$actionId'; pass --plugin")
        }
    }

    private fun runCommand(
        plugin: InstalledPluginInfo,
        kind: String,
        command: List<String>,
        context: PluginInvocationContext,
        extraEnv: Map<String, String>,
    ): PluginCommandLog {
        val started = System.currentTimeMillis()
        val id = "plog-${UUID.randomUUID()}"
        val env = buildEnv(plugin, context) + extraEnv
        val pb = ProcessBuilder(command)
            .directory(File(plugin.pluginRoot))
            .redirectErrorStream(false)
        pb.environment().putAll(env)
        val process = try {
            pb.start()
        } catch (e: Exception) {
            val log = PluginCommandLog(
                id = id,
                pluginId = plugin.pluginId,
                kind = kind,
                command = command,
                startedAtMillis = started,
                finishedAtMillis = System.currentTimeMillis(),
                exitCode = -1,
                stderr = e.message.orEmpty(),
            )
            appendLog(log)
            return log
        }
        val stdoutSink = StringBuffer()
        val stderrSink = StringBuffer()
        val outReader = Thread { process.inputStream.bufferedReader().use { stdoutSink.append(it.readText()) } }
        val errReader = Thread { process.errorStream.bufferedReader().use { stderrSink.append(it.readText()) } }
        outReader.start()
        errReader.start()
        // Apply the timeout before blocking on output: a plugin command that never exits must
        // be destroyed after the wait window regardless of what its pipes emit.
        val exited = process.waitFor(5, TimeUnit.MINUTES)
        if (!exited) {
            process.destroyForcibly()
        }
        outReader.join(10_000)
        errReader.join(10_000)
        val stdout = stdoutSink.toString()
        val stderr = stderrSink.toString()
        val log = PluginCommandLog(
            id = id,
            pluginId = plugin.pluginId,
            kind = kind,
            command = command,
            startedAtMillis = started,
            finishedAtMillis = System.currentTimeMillis(),
            exitCode = if (exited) process.exitValue() else -1,
            stdout = stdout.take(64_000),
            stderr = stderr.take(64_000),
        )
        appendLog(log)
        return log
    }

    private fun appendLog(log: PluginCommandLog) {
        _commandLogs.update { (listOf(log) + it).take(200) }
    }

    private fun buildEnv(plugin: InstalledPluginInfo, context: PluginInvocationContext): Map<String, String> {
        val contextJson = json.encodeToString(PluginInvocationContext.serializer(), context)
        return buildMap {
            put("ANDY_ENV", "1")
            put("ANDY_BIN_PATH", andyBinPath())
            put("ANDY_SOCKET_PATH", andySocketPath())
            put("ANDY_PLUGIN_ID", plugin.pluginId)
            put("ANDY_PLUGIN_ROOT", plugin.pluginRoot)
            put("ANDY_PLUGIN_CONFIG_DIR", plugin.configDir)
            put("ANDY_PLUGIN_STATE_DIR", plugin.stateDir)
            put("ANDY_PLUGIN_CONTEXT_JSON", contextJson)
            context.workspaceId?.let { put("ANDY_WORKSPACE_ID", it) }
            context.tabId?.let { put("ANDY_TAB_ID", it) }
            context.focusedPaneId?.let { put("ANDY_PANE_ID", it) }
            context.clickedUrl?.let { put("ANDY_PLUGIN_CLICKED_URL", it) }
            context.linkHandlerId?.let { put("ANDY_PLUGIN_LINK_HANDLER_ID", it) }
        }
    }

    private fun toInfo(record: InstalledPluginRecord, manifest: PluginManifest? = null): InstalledPluginInfo {
        val loaded = manifest ?: manifestCache[record.pluginId] ?: PluginManifestLoader.load(
            File(record.manifestPath),
            andyVersion(),
        ).also { manifestCache[record.pluginId] = it }
        return InstalledPluginInfo(
            pluginId = loaded.id,
            name = loaded.name,
            version = loaded.version,
            minAndyVersion = loaded.minAndyVersion,
            description = loaded.description,
            enabled = record.enabled,
            platforms = loaded.platforms,
            build = loaded.build,
            startup = loaded.startup,
            actions = loaded.actions,
            events = loaded.events,
            panes = loaded.panes,
            source = record.source,
            manifestPath = record.manifestPath,
            pluginRoot = record.pluginRoot,
            warnings = loaded.warnings,
            configDir = store.configDir(loaded.id).absolutePath,
            stateDir = store.stateDir(loaded.id).absolutePath,
        )
    }

    private fun ensurePlatform(info: InstalledPluginInfo, itemPlatforms: List<PluginPlatform>?) {
        if (!supportsPlatform(info.platforms, itemPlatforms)) {
            error("platform_unsupported: plugin ${info.pluginId} does not support ${currentPlatform().name.lowercase()}")
        }
    }

    private fun supportsPlatform(
        pluginPlatforms: List<PluginPlatform>?,
        itemPlatforms: List<PluginPlatform>?,
    ): Boolean {
        val effective = itemPlatforms ?: pluginPlatforms ?: return true
        return currentPlatform() in effective
    }

    private fun runBuildCommands(manifest: PluginManifest, pluginDir: File) {
        manifest.build
            .filter { supportsPlatform(manifest.platforms, it.platforms) }
            .forEachIndexed { index, build ->
                val pb = ProcessBuilder(build.command).directory(pluginDir)
                val process = pb.start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val code = process.waitFor()
                if (code != 0) {
                    error(
                        "plugin_build_failed: ${manifest.id} build[$index] exit=$code\n$stdout\n$stderr",
                    )
                }
            }
    }

    private fun cloneGithub(spec: String, ref: String?, dest: File) {
        dest.mkdirs()
        val parts = spec.split('/')
        require(parts.size >= 2) { "GitHub spec must be owner/repo[/subdir]" }
        val url = "https://github.com/${parts[0]}/${parts[1]}.git"
        val args = mutableListOf("git", "clone", "--depth", "1")
        if (ref != null) {
            args += listOf("--branch", ref)
        }
        args += listOf(url, dest.absolutePath)
        val process = ProcessBuilder(args).start()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error("git clone failed: $stderr")
    }

    private fun locatePluginDir(cloneRoot: File, spec: String): File {
        val parts = spec.split('/')
        if (parts.size <= 2) {
            val rootManifest = File(cloneRoot, "andy-plugin.toml")
            if (rootManifest.isFile) return cloneRoot
            error("andy-plugin.toml not found at repository root")
        }
        val sub = parts.drop(2).joinToString(File.separator)
        val dir = File(cloneRoot, sub)
        if (!File(dir, "andy-plugin.toml").isFile) {
            error("andy-plugin.toml not found in $sub")
        }
        return dir
    }

    private fun normalizeGithubSpec(spec: String): String =
        spec.removePrefix("https://github.com/").removePrefix("git@github.com:")
            .removeSuffix(".git").trim().trim('/')
}
