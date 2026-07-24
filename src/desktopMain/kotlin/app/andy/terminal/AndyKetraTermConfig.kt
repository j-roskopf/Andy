package app.andy.terminal

import io.github.ketraterm.workspace.config.TerminalConfig
import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import java.nio.file.Files
import java.nio.file.Path

/** Isolated KetraTerm config/history root under Andy's home directory. */
object AndyKetraTermPaths {
    fun root(): Path = Path.of(System.getProperty("user.home"), ".andy", "ketraterm")

    fun configFile(): Path = root().resolve("config.toml")

    fun commandHistoryFile(): Path = root().resolve("command-history-v1.tsv")
}

/**
 * Pins `-Dketraterm.config.path` to `~/.andy/ketraterm/config.toml` and force-enables
 * desktop notifications + persistent command history for Andy embeds.
 */
object AndyKetraTermConfig {
    private val lock = Any()

    fun ensureInitialized() {
        synchronized(lock) {
            val configPath = AndyKetraTermPaths.configFile()
            // Keep the pin pointed at Andy's isolated path even if something else overwrote it.
            System.setProperty("ketraterm.config.path", configPath.toAbsolutePath().toString())
            Files.createDirectories(configPath.parent)
            val manager = TerminalWorkspaceConfigManager(configPath)
            val loaded = runCatching { manager.load() }.getOrElse { TerminalConfig() }
            // Always upsert — Andy embeds have no Settings toggles for these host extras.
            val forced = loaded.copy(
                desktopNotificationsEnabled = true,
                persistentCommandHistoryEnabled = true,
            )
            runCatching { manager.save(forced) }
        }
    }

    /** Test-only hook kept for call-site clarity when re-pinning under a temporary `user.home`. */
    internal fun resetForTests() = Unit
}
