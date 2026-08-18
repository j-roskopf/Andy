package app.andy.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A localhost TCP listener that looks like a local development server. */
data class LocalServerProcess(
    val pid: Int,
    val ppid: Int? = null,
    val ports: List<Int>,
    val displayName: String,
    val commandLine: String? = null,
    val cwd: String? = null,
    val projectId: String? = null,
    /** Project runbook / dock terminal that owns this listener, when known. */
    val runId: String? = null,
    val actionName: String? = null,
    val isStoppable: Boolean = true,
    val stopDisabledReason: String? = null,
) {
    val addressLabel: String
        get() = when {
            ports.isEmpty() -> "localhost"
            else -> ports.joinToString(", ") { "localhost:$it" }
        }

    val folderLabel: String?
        get() {
            val path = cwd?.trim().orEmpty()
            if (path.isEmpty()) return null
            return path.split('/', '\\').lastOrNull { it.isNotEmpty() }
        }

    val ownerLabel: String?
        get() = actionName?.takeIf { it.isNotBlank() }?.let { "Action: $it" }

    /** URL to load in Andy's Browser dock. Local servers are HTTP, never HTTPS. */
    val browserUrl: String?
        get() = ports.firstOrNull()?.let { "http://localhost:$it" }
}

/** Host-wide scan of local development servers (desktop only). */
interface LocalServerService {
    val servers: StateFlow<List<LocalServerProcess>>
    /**
     * Begin/end background polling. Scans are expensive (`lsof`/`ps` forks) — callers must
     * only watch while the Local Servers UI is actually composed, never from app startup.
     */
    fun startWatching()
    fun stopWatching()
    suspend fun refresh()
    suspend fun stop(pid: Int, port: Int): CommandResult
}

object UnavailableLocalServerService : LocalServerService {
    override val servers: StateFlow<List<LocalServerProcess>> = MutableStateFlow(emptyList())
    override fun startWatching() = Unit
    override fun stopWatching() = Unit
    override suspend fun refresh() = Unit
    override suspend fun stop(pid: Int, port: Int) =
        CommandResult.failure("Local server management is unavailable on this host.")
}

/** Identity used to match a listener back to an Andy agent chat or project action. */
data class LocalServerOwnerIdentity(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val cwd: String? = null,
    val worktreePath: String? = null,
    val rootPid: Long? = null,
    val kind: Kind = Kind.Chat,
) {
    enum class Kind { Chat, Action }
}

/**
 * Pure parsers / classifiers for localhost listen scans.
 * Desktop feeds `lsof`/`ps` text; tests use fixtures.
 */
object LocalServerScan {
    internal const val ProcessLineageMaxDepth = 4

    private val ExcludedProcessPatterns = listOf(
        "airplayxpchelper",
        "controlcenter",
        "cursor helper",
        "figma",
        "google chrome",
        "linear helper",
        "logioptionsplus",
        "openclaw",
        "opencode",
        "rapportd",
        "raycast",
        "safari",
        "spotify",
        "synara",
    )

    private val ExcludedProcessCommands = setOf(
        "electron",
        "electron helper",
        "electron helper (renderer)",
        "openclaw",
        "opencode",
        "synara",
        "andy",
        "andyd",
    )

    private val ChromiumChildArgsPattern =
        Regex("""--type=(?:renderer|gpu-process|gpu|utility|zygote|plugin|ppapi|broker|crashpad-handler)\b""", RegexOption.IGNORE_CASE)

    private val AppHelperCommandPattern =
        Regex("""\bhelper\s*\((?:renderer|gpu|plugin|alerts)\)""", RegexOption.IGNORE_CASE)

    /** IDE / language-server noise on the *leaf* process (not parent lineage). */
    private val ToolingNoisePattern = Regex(
        """language[-_]?server|typescript-language|eslint|prettier|tailwindcss-language|vue-language|kotlin-language|jetbrains|cursor-server|copilot|telemetry|chrome-devtools|devtools-frontend|node_modules/\.bin/(?:tsserver|eslint)|vscode-jsonrpc""",
        RegexOption.IGNORE_CASE,
    )

    /** Leaf process is a build daemon, not an app server — even if it holds a port. */
    private val BuildDaemonPattern = Regex(
        """\b(?:GradleDaemon|KotlinCompileDaemon|gradle-daemon)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DevCommandLabels = mapOf(
        "air" to "Air",
        "artisan" to "Laravel",
        "astro" to "Astro",
        "bunx" to "Bun",
        "caddy" to "Caddy",
        "docusaurus" to "Docusaurus",
        "ember" to "Ember",
        "expo" to "Expo",
        "fastapi" to "FastAPI",
        "flask" to "Flask",
        "gatsby" to "Gatsby",
        "gunicorn" to "Gunicorn",
        "hugo" to "Hugo",
        "hypercorn" to "Hypercorn",
        "jekyll" to "Jekyll",
        "miniserve" to "Miniserve",
        "next" to "Next.js",
        "nuxt" to "Nuxt",
        "parcel" to "Parcel",
        "phoenix" to "Phoenix",
        "rails" to "Rails",
        "remix" to "Remix",
        "rsbuild" to "Rsbuild",
        "rspack" to "Rspack",
        "storybook" to "Storybook",
        "svelte-kit" to "SvelteKit",
        "uvicorn" to "Uvicorn",
        "vite" to "Vite",
        "webpack" to "Webpack",
        "webpack-dev-server" to "Webpack",
        "wrangler" to "Wrangler",
    )

    private val DatabaseOrSystemCommands = setOf(
        "memcached",
        "mongod",
        "mysql",
        "mysqld",
        "postgres",
        "postgresql",
        "redis-server",
    )

    private val DevScriptNamePattern =
        Regex(
            """^(?:dev|dev[:_-].+|.+[:_-]dev|start|serve|preview|storybook|electron:dev|dev:electron|dev:desktop|desktop:dev|start:desktop|web|wasm)$""",
            RegexOption.IGNORE_CASE,
        )

    private val DevArgsPattern = Regex(
        """\b(""" +
            """astro|expo|flask|fastapi|uvicorn|gunicorn|hypercorn|next\s+dev|nodemon|nuxt|parcel|""" +
            """react-scripts\s+start|remix|rsbuild|rspack|svelte-kit|sveltekit|solid-start|qwik|""" +
            """turbo|vite|webpack(?:-dev-server)?|storybook|docusaurus|gatsby|hugo|jekyll|""" +
            """wrangler|miniserve|caddy|ember|nest\s+start|prisma\s+studio|""" +
            """bootRun|spring-boot|ktor|quarkus|micronaut|http-server|live-server|""" +
            """actix|axum|warp|rocket|phoenix|puma|unicorn|webrick|shotgun""" +
            """)\b""" +
            """|(?:manage\.py\s+runserver)|(?:php\s+(?:artisan\s+serve|-S\s+))|(?:rails\s+(?:s|server))""" +
            """|(?:uvicorn\b)|(?:webpack\s+serve)|(?:go\s+run\b)|(?:cargo\s+run\b)""" +
            """|(?:dotnet\s+(?:watch|run)\b)|(?:deno\s+(?:task\s+)?(?:dev|serve|run)\b)""" +
            """|(?:python3?\s+-m\s+http\.server\b)""" +
            """|(?:gradlew?\s+\S*(?:bootRun|run|BrowserDevelopmentRun|BrowserProductionRun|wasmJs|jsBrowser)\S*)""" +
            """|(?:npm|pnpm|yarn|bun)\s+(?:run\s+)?(?:dev|start|serve|preview|storybook)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val DevRuntimeLabels = mapOf(
        "node" to "Node",
        "nodejs" to "Node",
        "bun" to "Bun",
        "deno" to "Deno",
        "python" to "Python",
        "python3" to "Python",
        "ruby" to "Ruby",
        "php" to "PHP",
        "dotnet" to "Dotnet",
    )

    /**
     * Kinds that are confidently project/app servers. These may be linked to an Andy chat or
     * action via cwd. Weak kinds (generic Node/Bun) require process-lineage ownership so we do
     * not claim OpenCode/OpenClaw/etc. just because they happen to sit in a repo directory.
     */
    private val StrongProjectServerKinds = setOf(
        "Air", "Astro", "Caddy", "Cargo", "Deno", "Dev Server", "Django", "Docusaurus",
        "Dotnet", "Ember", "Expo", "FastAPI", "Flask", "Gatsby", "Go", "Gradle", "Gunicorn",
        "HTTP", "Hugo", "Jekyll", "Ktor", "Laravel", "Metro", "Miniserve", "Next.js", "Nuxt",
        "Parcel", "PHP", "Python", "Rails", "React", "Remix", "Rspack", "Rsbuild", "Ruby",
        "Rust", "Spring", "Storybook", "SvelteKit", "Uvicorn", "Vite", "Webpack", "Wrangler",
    )

    private val AndyOwnedArgsPattern = Regex(
        """\b(andyd|andy-mcp|andy_mitm_addon|mitmdump)\b""",
        RegexOption.IGNORE_CASE,
    )

    data class ParsedLsofListener(
        val pid: Int,
        val command: String,
        val host: String,
        val port: Int,
    )

    data class ProcessInfo(
        val ppid: Int,
        val commandLine: String,
        val rawCommandLine: String = commandLine,
        val andyTaskId: String? = null,
    )

    data class DevServerCandidate(
        val command: String,
        val args: String,
        val ports: List<Int>,
    )

    fun parseLsofTcpListenOutput(output: String): List<ParsedLsofListener> {
        val listeners = mutableListOf<ParsedLsofListener>()
        var currentPid: Int? = null
        var currentCommand = ""
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.length < 2) continue
            val field = line[0]
            val value = line.substring(1)
            when (field) {
                'p' -> {
                    currentPid = value.toIntOrNull()?.takeIf { it > 0 }
                    currentCommand = ""
                }
                'c' -> currentCommand = value
                'n' -> {
                    val pid = currentPid ?: continue
                    val endpoint = parseLsofEndpoint(value) ?: continue
                    listeners += ParsedLsofListener(
                        pid = pid,
                        command = currentCommand,
                        host = endpoint.first,
                        port = endpoint.second,
                    )
                }
            }
        }
        return listeners
    }

    /** Parses `lsof -d cwd -Fn` into pid → cwd. */
    fun parseLsofCwdOutput(output: String): Map<Int, String> {
        val cwdByPid = mutableMapOf<Int, String>()
        var currentPid: Int? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.length < 2) continue
            when (line[0]) {
                'p' -> currentPid = line.substring(1).toIntOrNull()?.takeIf { it > 0 }
                'n' -> {
                    val pid = currentPid ?: continue
                    val cwd = line.substring(1).trim()
                    if (cwd.isNotEmpty() && pid !in cwdByPid) {
                        cwdByPid[pid] = cwd
                    }
                }
            }
        }
        return cwdByPid
    }

    /**
     * Parses `ps -o pid=,ppid=,command=` (or `ps eww`) lines.
     * Optional `ANDY_TASK_ID=…` is extracted when present in the command/env text.
     */
    fun parsePsProcessTable(output: String): Map<Int, ProcessInfo> {
        val byPid = mutableMapOf<Int, ProcessInfo>()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = Regex("""^(\d+)\s+(\d+)\s+(.*)$""").matchEntire(line) ?: continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val ppid = match.groupValues[2].toIntOrNull() ?: continue
            val commandLine = match.groupValues[3].trim()
            if (commandLine.isEmpty()) continue
            byPid[pid] = ProcessInfo(
                ppid = ppid,
                commandLine = redactSensitiveArgs(commandLine),
                rawCommandLine = commandLine,
                andyTaskId = extractAndyTaskId(commandLine),
            )
        }
        return byPid
    }

    fun extractAndyTaskId(environOrCommand: String): String? {
        val match = Regex("""\bANDY_TASK_ID=([^\s]+)""").find(environOrCommand) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    fun isIgnoredLocalServerProcess(input: DevServerCandidate): Boolean {
        val text = normalizeProcessText(input.command, input.args)
        val commandName = normalizeCommandName(input.command, input.args)
        if (input.ports.all { it < 1024 }) return true
        if (commandName in DatabaseOrSystemCommands) return true
        if (ChromiumChildArgsPattern.containsMatchIn(input.args) ||
            AppHelperCommandPattern.containsMatchIn(input.command)
        ) {
            return true
        }
        if (commandName in ExcludedProcessCommands) return true
        if (AndyOwnedArgsPattern.containsMatchIn(text)) return true
        if (ToolingNoisePattern.containsMatchIn(text)) return true
        if (BuildDaemonPattern.containsMatchIn(text)) return true
        return ExcludedProcessPatterns.any { pattern -> text.contains(pattern) }
    }

    fun detectDevServerKind(input: DevServerCandidate): String? {
        val commandName = normalizeCommandName(input.command, input.args)
        val text = normalizeProcessText(input.command, input.args)
        if (isExpoDevServerCommand(input.command, input.args)) return "Expo"
        if (isMetroDevServerCommand(input.command, input.args)) return "Metro"

        DevCommandLabels[commandName]?.let { label ->
            if (commandName == "next") {
                val nextDev = Regex("""\bnext\s+dev\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn("${input.command} ${input.args}") ||
                    Regex("""^\s*dev\b""", RegexOption.IGNORE_CASE).containsMatchIn(input.args)
                if (!nextDev) return null
            }
            return label
        }
        if (Regex("""(^|[\s/\\])vite(?:\.js|\.mjs|\.cjs)?(?:\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Vite"
        }
        if (Regex("""\bnext\s+dev\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Next.js"
        if (Regex("""\bnuxt\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Nuxt"
        if (Regex("""\bastro\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Astro"
        // Kotlin/JS and many CLIs rewrite argv[0] to bare "webpack" while lsof still reports "node".
        if (Regex("""\bwebpack(?:-dev-server)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\bwebpack\s+serve\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            return "Webpack"
        }
        if (Regex("""\bparcel\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Parcel"
        if (Regex("""\brspack\b|\brsbuild\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Rspack"
        if (Regex("""\bsvelte-?kit\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "SvelteKit"
        if (Regex("""\bremix\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Remix"
        if (Regex("""\bstorybook\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Storybook"
        if (Regex("""\buvicorn\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Uvicorn"
        if (Regex("""\bgunicorn\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Gunicorn"
        if (Regex("""\bfastapi\b|\bhypercorn\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "FastAPI"
        if (Regex("""\bflask\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Flask"
        if (Regex("""(?:manage\.py\s+runserver)|\bdjango\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Django"
        if (Regex("""(?:php\s+artisan\s+serve)|\blaravel\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Laravel"
        if (Regex("""\brails\s+(?:s|server)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Rails"
        if (Regex("""\b(puma|unicorn|webrick|shotgun|phoenix)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Ruby"
        if (Regex("""\bgo\s+run\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Go"
        if (Regex("""\b(actix|axum|warp)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Rust"
        if (Regex("""\bcargo\s+run\b|\brocket\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Cargo"
        if (Regex("""\bdotnet\s+(?:watch|run)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Dotnet"
        if (Regex("""\bdeno\s+(?:task\s+)?(?:dev|serve|run)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Deno"
        if (Regex("""\bpython3?\s+-m\s+http\.server\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Python"
        if (Regex("""\bphp\s+-S\s+""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "PHP"
        if (Regex("""\breact-scripts\s+start\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "React"
        if (Regex("""\bbootRun\b|\bspring-boot\b|\bspringframework\.boot\b|\bquarkus\b|\bmicronaut\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Spring"
        }
        if (Regex("""\bktor\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Ktor"
        if (Regex("""\b(http-server|live-server)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "HTTP"
        if (Regex("""\bwrangler\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "Wrangler"
        if (Regex("""\bBrowser(?:Development|Production)Run\b|\bwasmJs\b|\bjsBrowser\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Gradle"
        }
        if (commandName == "node" && Regex("""(?:--port|-p)\s*\d{2,5}\b""").containsMatchIn(text)) return "Node"
        if (commandName in setOf("python", "python3") &&
            Regex("""(?:--port|-p)\s*\d{2,5}\b|http\.server|\.py\b""").containsMatchIn(text)
        ) {
            return "Python"
        }

        val scriptName = Regex(
            """\b(?:bun|npm|pnpm|yarn)\s+(?:run\s+)?([A-Za-z0-9:_-]+)\b""",
            RegexOption.IGNORE_CASE,
        ).find(input.args)?.groupValues?.getOrNull(1)
        if (scriptName != null && DevScriptNamePattern.matches(scriptName)) return "Dev Server"
        if (DevArgsPattern.containsMatchIn(text)) return "Dev Server"
        return null
    }

    fun isStrongProjectServerKind(kind: String): Boolean = kind in StrongProjectServerKinds

    fun isLikelyDevServerProcess(input: DevServerCandidate): Boolean =
        !isIgnoredLocalServerProcess(input) && detectDevServerKind(input) != null

    fun buildLocalServerProcesses(
        listeners: List<ParsedLsofListener>,
        processInfoByPid: Map<Int, ProcessInfo>,
        cwdByPid: Map<Int, String>,
        owners: List<LocalServerOwnerIdentity>,
        isStoppable: (Int) -> Boolean = { true },
    ): List<LocalServerProcess> {
        val ownedRootPids = owners.mapNotNull { it.rootPid }.toSet()
        fun isOwnedDescendant(pid: Int): Boolean {
            val seen = mutableSetOf<Int>()
            var current = pid
            repeat(ProcessLineageMaxDepth + 2) {
                if (!seen.add(current)) return false
                if (current.toLong() in ownedRootPids) return true
                val ppid = processInfoByPid[current]?.ppid ?: return false
                if (ppid <= 1) return false
                current = ppid
            }
            return false
        }

        val grouped = listeners.groupBy { it.pid }
        return grouped.mapNotNull { (pid, group) ->
            val ports = group.map { it.port }.distinct().sorted()
            val command = group.firstOrNull()?.command.orEmpty()
            val processInfo = processInfoByPid[pid]
            val rawCommandLine = processInfo?.rawCommandLine.orEmpty()
            val args = processInfo?.rawCommandLine?.let { stripLeadingCommand(it, command) }.orEmpty()
            val lineageText = processLineageCommandLines(pid, processInfoByPid).orEmpty()
            // Prefer the process-table name when lsof only has a generic runtime (node/java) but
            // the process rewrote argv0 to a tool name like "webpack".
            val effectiveCommand = when {
                command.isNotBlank() &&
                    command.lowercase() !in setOf("node", "nodejs", "java", "python", "python3") -> command
                rawCommandLine.isNotBlank() -> rawCommandLine.substringBefore(' ').ifBlank { command }
                else -> command.ifBlank { rawCommandLine.substringBefore(' ') }
            }
            val leafArgs = listOf(args, rawCommandLine).filter { it.isNotBlank() }.joinToString(" ")
            val detectArgs = listOf(leafArgs, lineageText).filter { it.isNotBlank() }.joinToString(" ")
            val leafCandidate = DevServerCandidate(
                command = effectiveCommand.ifBlank { command.ifBlank { "unknown" } },
                args = leafArgs,
                ports = ports,
            )
            // Lineage is useful for positive keyword matches (e.g. gradle task names) but must
            // not be used for ignore rules — a webpack child of GradleDaemon is still a server.
            if (isIgnoredLocalServerProcess(leafCandidate)) return@mapNotNull null
            val candidate = leafCandidate.copy(args = detectArgs)
            val owned = isOwnedDescendant(pid)
            val andyTaskId = resolveAndyTaskId(pid, processInfoByPid)
            val cwd = resolveProcessCwd(pid, processInfoByPid, cwdByPid)
            val lineageOwner = findOwnerForLineage(pid, processInfoByPid, owners)
            val taskOwner = andyTaskId?.let { id ->
                owners.firstOrNull { it.kind == LocalServerOwnerIdentity.Kind.Chat && it.id == id }
            }
            val processOwned = owned || lineageOwner != null || taskOwner != null
            // Cwd matching is action-only — never claim a listener for a chat just because it
            // shares a project directory (OpenCode / other host tools often do).
            val actionOwners = owners.filter { it.kind == LocalServerOwnerIdentity.Kind.Action }
            val cwdOwner = cwd?.let { path ->
                attributeToOwner(pid, processInfo?.ppid, path, null, actionOwners)
            }
            // Prefer leaf classification so parent daemons (GradleDaemon) do not poison ignore
            // rules; fall back to lineage text only for positive keyword matches.
            val kind = detectDevServerKind(leafCandidate)
                ?: detectDevServerKind(candidate)
                ?: if (processOwned) {
                    DevRuntimeLabels[normalizeCommandName(leafCandidate.command, leafCandidate.args)]
                        ?: normalizeCommandName(leafCandidate.command, leafCandidate.args)
                            .replaceFirstChar { it.uppercase() }
                            .ifBlank { "Server" }
                } else {
                    null
                }
                ?: return@mapNotNull null

            // Require Andy relevance: process lineage / ANDY_TASK_ID, or a strong framework
            // living under an Andy action cwd. Chat linking is intentionally not surfaced.
            val attributed = when {
                lineageOwner?.kind == LocalServerOwnerIdentity.Kind.Action -> lineageOwner
                isStrongProjectServerKind(kind) && cwdOwner != null -> cwdOwner
                else -> null
            }
            if (!processOwned && attributed == null) return@mapNotNull null

            val stoppable = isStoppable(pid)
            LocalServerProcess(
                pid = pid,
                ppid = processInfo?.ppid?.takeIf { it > 0 },
                ports = ports,
                displayName = kind,
                commandLine = processInfo?.commandLine,
                cwd = cwd,
                projectId = attributed?.projectId
                    ?: lineageOwner?.takeIf { it.kind == LocalServerOwnerIdentity.Kind.Action }?.projectId
                    ?: taskOwner?.projectId,
                runId = attributed?.id,
                actionName = attributed?.title,
                isStoppable = stoppable,
                stopDisabledReason = if (stoppable) null else "Andy cannot signal this process.",
            )
        }.sortedWith(compareBy({ it.ports.firstOrNull() ?: Int.MAX_VALUE }, { it.pid }))
    }

    fun attributeToOwner(
        pid: Int,
        ppid: Int?,
        cwd: String?,
        andyTaskId: String?,
        owners: List<LocalServerOwnerIdentity>,
    ): LocalServerOwnerIdentity? {
        if (owners.isEmpty()) return null
        andyTaskId?.let { id ->
            owners.firstOrNull { it.kind == LocalServerOwnerIdentity.Kind.Chat && it.id == id }?.let { return it }
        }
        owners.firstOrNull { owner ->
            val root = owner.rootPid ?: return@firstOrNull false
            pid.toLong() == root || ppid?.toLong() == root
        }?.let { return it }
        if (cwd.isNullOrBlank()) return null
        val normalizedCwd = normalizePath(cwd)
        return owners
            .mapNotNull { owner ->
                val roots = listOfNotNull(owner.worktreePath, owner.cwd)
                    .map(::normalizePath)
                    .filter { it.isNotEmpty() }
                val match = roots.any { root ->
                    normalizedCwd == root || normalizedCwd.startsWith("$root/")
                }
                if (match) owner to (roots.maxOfOrNull { it.length } ?: 0) else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun findOwnerForLineage(
        pid: Int,
        processInfoByPid: Map<Int, ProcessInfo>,
        owners: List<LocalServerOwnerIdentity>,
    ): LocalServerOwnerIdentity? {
        val seen = mutableSetOf<Int>()
        var current = pid
        repeat(ProcessLineageMaxDepth + 2) {
            if (!seen.add(current)) return null
            owners.firstOrNull { it.rootPid == current.toLong() }?.let { return it }
            val ppid = processInfoByPid[current]?.ppid ?: return null
            if (ppid <= 1) return null
            current = ppid
        }
        return null
    }

    fun isWorkspaceRootWithin(candidateCwd: String, workspaceRoot: String): Boolean {
        val cwd = normalizePath(candidateCwd)
        val root = normalizePath(workspaceRoot)
        if (cwd.isEmpty() || root.isEmpty()) return false
        return cwd == root || cwd.startsWith("$root/")
    }

    private fun resolveAndyTaskId(pid: Int, processInfoByPid: Map<Int, ProcessInfo>): String? {
        val seen = mutableSetOf<Int>()
        var current = pid
        repeat(ProcessLineageMaxDepth) {
            if (!seen.add(current)) return null
            processInfoByPid[current]?.andyTaskId?.let { return it }
            val ppid = processInfoByPid[current]?.ppid ?: return null
            if (ppid <= 1) return null
            current = ppid
        }
        return null
    }

    private fun resolveProcessCwd(
        pid: Int,
        processInfoByPid: Map<Int, ProcessInfo>,
        cwdByPid: Map<Int, String>,
    ): String? {
        val seen = mutableSetOf<Int>()
        var current = pid
        repeat(ProcessLineageMaxDepth) {
            if (!seen.add(current)) return null
            cwdByPid[current]?.let { return it }
            val ppid = processInfoByPid[current]?.ppid ?: return null
            if (ppid <= 1) return null
            current = ppid
        }
        return null
    }

    private fun processLineageCommandLines(
        pid: Int,
        processInfoByPid: Map<Int, ProcessInfo>,
    ): String? {
        val lines = mutableListOf<String>()
        val seen = mutableSetOf<Int>()
        var current = pid
        repeat(ProcessLineageMaxDepth) {
            if (!seen.add(current)) return@repeat
            val info = processInfoByPid[current] ?: return@repeat
            val commandLine = info.rawCommandLine
            if (commandLine.isNotBlank()) lines += commandLine
            if (info.ppid <= 1) return@repeat
            current = info.ppid
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun parseLsofEndpoint(name: String): Pair<String, Int>? {
        val cleaned = name.replace(Regex("""\s+\(LISTEN\)$""", RegexOption.IGNORE_CASE), "").trim()
        val bracket = Regex("""^\[([^\]]+)]:(\d+)$""").matchEntire(cleaned)
        if (bracket != null) {
            val port = bracket.groupValues[2].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return bracket.groupValues[1] to port
        }
        val separator = cleaned.lastIndexOf(':')
        if (separator < 0) return null
        val host = cleaned.substring(0, separator).trim().ifEmpty { "*" }
        val port = cleaned.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun normalizeProcessText(command: String, args: String): String =
        "$command $args".lowercase()

    private fun normalizeCommandName(command: String, args: String): String {
        val token = command.trim().ifEmpty {
            args.trim().substringBefore(' ')
        }
        return token.substringAfterLast('/').substringAfterLast('\\').lowercase()
    }

    private fun stripLeadingCommand(commandLine: String, command: String): String {
        val trimmed = commandLine.trim()
        if (command.isBlank()) return trimmed
        val name = command.substringAfterLast('/').substringAfterLast('\\')
        val idx = trimmed.indexOf(name)
        if (idx < 0) return trimmed
        return trimmed.substring(idx + name.length).trim()
    }

    private fun isExpoDevServerCommand(command: String, args: String): Boolean {
        val tokens = processCommandTokens(command, args)
        return tokens.any { token ->
            commandTokenName(token) == "expo" ||
                token.replace('\\', '/').lowercase().contains("/node_modules/@expo/cli/")
        }
    }

    private fun isMetroDevServerCommand(command: String, args: String): Boolean {
        val tokens = processCommandTokens(command, args)
        val tokenNames = tokens.map(::commandTokenName)
        val reactNativeIndex = tokenNames.indexOf("react-native")
        return tokenNames.contains("metro") ||
            tokens.any { it.replace('\\', '/').lowercase().contains("/node_modules/metro/") } ||
            (reactNativeIndex >= 0 && tokenNames.drop(reactNativeIndex + 1).contains("start"))
    }

    private fun processCommandTokens(command: String, args: String): List<String> =
        "$command $args".trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    private fun commandTokenName(token: String): String =
        token.substringAfterLast('/').substringAfterLast('\\').lowercase()

    private fun normalizePath(path: String): String =
        path.trim().trimEnd('/', '\\').replace('\\', '/')

    private fun redactSensitiveArgs(commandLine: String): String {
        // Keep presentation short / non-secret; drop common token-looking flags.
        return commandLine
            .replace(Regex("""(--(?:token|auth|password|secret|api[_-]?key)=)\S+""", RegexOption.IGNORE_CASE), "$1***")
            .take(1000)
    }
}
