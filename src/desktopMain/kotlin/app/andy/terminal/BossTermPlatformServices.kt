package app.andy.terminal

import ai.rever.bossterm.compose.DesktopProcessService
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * BossTerm [PlatformServices] that spawn Andy's exact argv (tmux attach / DirectPty),
 * scrub IDE env, tee PTY output into [ScrollbackAnsiTee], and optionally sanitize
 * agent-CLI cursor flicker sequences before they reach the emulator.
 */
internal class AndyBossTermPlatformServices(
    private val argvProvider: () -> List<String>,
    private val cwdProvider: () -> String?,
    private val envOverridesProvider: () -> Map<String, String>,
    private val scrollbackTee: ScrollbackAnsiTee,
    private val agentCliMode: Boolean,
    private val onHandle: (PlatformServices.ProcessService.ProcessHandle) -> Unit = {},
) : PlatformServices by getPlatformServices() {
    private val defaults = getPlatformServices()
    private val processService = AndyBossTermProcessService(
        argvProvider = argvProvider,
        cwdProvider = cwdProvider,
        envOverridesProvider = envOverridesProvider,
        scrollbackTee = scrollbackTee,
        agentCliMode = agentCliMode,
        onHandle = onHandle,
        delegate = DesktopProcessService(),
    )

    override fun getProcessService(): PlatformServices.ProcessService = processService

    /** Keep BossTerm defaults for clipboard / notifications / browser. */
    override fun getClipboardService() = defaults.getClipboardService()
    override fun getNotificationService() = defaults.getNotificationService()
    override fun getBrowserService() = defaults.getBrowserService()
    override fun getFileSystemService() = defaults.getFileSystemService()
    override fun getPlatformInfo() = defaults.getPlatformInfo()
}

private class AndyBossTermProcessService(
    private val argvProvider: () -> List<String>,
    private val cwdProvider: () -> String?,
    private val envOverridesProvider: () -> Map<String, String>,
    private val scrollbackTee: ScrollbackAnsiTee,
    private val agentCliMode: Boolean,
    private val onHandle: (PlatformServices.ProcessService.ProcessHandle) -> Unit,
    private val delegate: PlatformServices.ProcessService,
) : PlatformServices.ProcessService {
    override suspend fun spawnProcess(
        config: PlatformServices.ProcessService.ProcessConfig,
    ): PlatformServices.ProcessService.ProcessHandle? {
        val argv = argvProvider()
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val environment = HashMap(config.environment).apply {
            putAll(envOverridesProvider())
            scrubInheritedTerminalEnvironment(this)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                put("LC_CTYPE", "UTF-8")
            }
        }
        val real = PlatformServices.ProcessService.ProcessConfig(
            command = argv.first(),
            arguments = argv.drop(1),
            environment = environment,
            workingDirectory = resolveTerminalWorkingDirectory(
                cwdProvider() ?: config.workingDirectory,
            ),
        )
        val handle = delegate.spawnProcess(real) ?: return null
        val wrapped = TeeingProcessHandle(handle, scrollbackTee, agentCliMode)
        onHandle(wrapped)
        return wrapped
    }
}

/**
 * Process handle that tees every stdout chunk into [tee] (raw) and optionally
 * strips agent-CLI show-cursor CSI sequences before returning text to BossTerm.
 */
private class TeeingProcessHandle(
    private val delegate: PlatformServices.ProcessService.ProcessHandle,
    private val tee: ScrollbackAnsiTee,
    private val agentCliMode: Boolean,
) : PlatformServices.ProcessService.ProcessHandle by delegate {
    override suspend fun read(): String? {
        val chunk = delegate.read() ?: return null
        if (chunk.isEmpty()) return chunk
        val bytes = chunk.toByteArray(StandardCharsets.UTF_8)
        tee.append(bytes, 0, bytes.size)
        if (!agentCliMode) return chunk
        val (sanitized, offset, length) = sanitizeAgentCliPtyChunk(bytes, 0, bytes.size)
        if (offset == 0 && length == bytes.size && sanitized === bytes) return chunk
        return String(sanitized, offset, length, StandardCharsets.UTF_8)
    }
}

/**
 * Read-only process handle that feeds [payload] once, then stays alive until [kill]
 * so BossTerm can render scrollback replay without a real shell.
 */
internal class ReplayProcessService(
    private val payload: String,
) : PlatformServices.ProcessService {
    override suspend fun spawnProcess(
        config: PlatformServices.ProcessService.ProcessConfig,
    ): PlatformServices.ProcessService.ProcessHandle = ReplayProcessHandle(payload)
}

private class ReplayProcessHandle(
    payload: String,
) : PlatformServices.ProcessService.ProcessHandle {
    private val remaining = AtomicReference(payload)
    @Volatile private var alive = true

    override suspend fun write(data: String) = Unit
    override suspend fun writeBytes(data: ByteArray) = Unit

    override suspend fun read(): String? {
        if (!alive) return null
        val next = remaining.getAndSet(null)
        if (next != null) return next
        // Park until killed so the emulator session stays Connected.
        while (alive) {
            kotlinx.coroutines.delay(250)
        }
        return null
    }

    override fun isAlive(): Boolean = alive

    override suspend fun kill() {
        alive = false
    }

    override suspend fun waitFor(): Int {
        while (alive) kotlinx.coroutines.delay(100)
        return 0
    }

    override suspend fun resize(columns: Int, rows: Int) = Unit
    override fun getExitCode(): Int? = if (alive) null else 0
    override fun getPid(): Long? = null
    override fun getWorkingDirectory(): String? = null
}

internal class ReplayPlatformServices(
    payload: String,
) : PlatformServices by getPlatformServices() {
    private val processService = ReplayProcessService(payload)
    override fun getProcessService(): PlatformServices.ProcessService = processService
}
