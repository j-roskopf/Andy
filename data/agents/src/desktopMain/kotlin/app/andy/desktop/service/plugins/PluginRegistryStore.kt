package app.andy.desktop.service.plugins

import app.andy.model.InstalledPluginRecord
import app.andy.model.PluginRegistryFile
import app.andy.model.PluginSourceInfo
import app.andy.model.PluginSourceKind
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json

/**
 * Plugin registry at `~/.andy/plugins.json` with a sibling lock file.
 *
 * Java [FileChannel.lock] is JVM-wide and non-reentrant across channels: two threads in the
 * same process must not acquire overlapping locks. Serialize in-process with [processLock],
 * then take the OS file lock for cross-process (GUI ↔ andyd) safety.
 */
class PluginRegistryStore(
    private val andyHome: File = File(System.getProperty("user.home"), ".andy"),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    },
) {
    val registryFile: File get() = File(andyHome, "plugins.json")
    private val lockFile: File get() = File(andyHome, ".plugins.lock")
    val managedRoot: File get() = File(andyHome, "plugins/managed")
    val configRoot: File get() = File(andyHome, "plugins/config")
    val stateRoot: File get() = File(andyHome, "plugins/state")

    private val processLock = ReentrantLock()

    fun configDir(pluginId: String): File = File(configRoot, pluginId).also { it.mkdirs() }
    fun stateDir(pluginId: String): File = File(stateRoot, pluginId).also { it.mkdirs() }
    fun managedCheckout(pluginId: String): File = File(managedRoot, pluginId)

    fun load(): PluginRegistryFile = withLock {
        readUnlocked()
    }

    fun save(registry: PluginRegistryFile) = withLock {
        writeUnlocked(registry)
    }

    fun upsert(record: InstalledPluginRecord): PluginRegistryFile = withLock {
        val current = readUnlocked()
        val next = current.copy(
            plugins = current.plugins.filterNot { it.pluginId == record.pluginId } + record,
        )
        writeUnlocked(next)
        next
    }

    fun remove(pluginId: String): PluginRegistryFile = withLock {
        val current = readUnlocked()
        val next = current.copy(plugins = current.plugins.filterNot { it.pluginId == pluginId })
        writeUnlocked(next)
        next
    }

    fun setEnabled(pluginId: String, enabled: Boolean): PluginRegistryFile = withLock {
        val current = readUnlocked()
        val existing = current.plugins.firstOrNull { it.pluginId == pluginId }
            ?: error("Unknown plugin id: $pluginId")
        val next = current.copy(
            plugins = current.plugins.filterNot { it.pluginId == pluginId } + existing.copy(enabled = enabled),
        )
        writeUnlocked(next)
        next
    }

    fun find(pluginId: String): InstalledPluginRecord? =
        load().plugins.firstOrNull { it.pluginId == pluginId }

    fun findByGithubSpec(spec: String): InstalledPluginRecord? =
        load().plugins.firstOrNull {
            it.source.kind == PluginSourceKind.Github && it.source.githubSpec == spec
        }

    private fun readUnlocked(): PluginRegistryFile {
        if (!registryFile.isFile) return PluginRegistryFile()
        val text = registryFile.readText()
        if (text.isBlank()) return PluginRegistryFile()
        return runCatching { json.decodeFromString(PluginRegistryFile.serializer(), text) }
            .getOrElse { PluginRegistryFile() }
    }

    private fun writeUnlocked(registry: PluginRegistryFile) {
        andyHome.mkdirs()
        registryFile.writeText(json.encodeToString(PluginRegistryFile.serializer(), registry))
    }

    private fun <T> withLock(block: () -> T): T = processLock.withLock {
        andyHome.mkdirs()
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }
}

internal fun PluginSourceInfo.isLocal(): Boolean = kind == PluginSourceKind.Local
