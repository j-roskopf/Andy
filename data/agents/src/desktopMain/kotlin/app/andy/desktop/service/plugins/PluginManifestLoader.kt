package app.andy.desktop.service.plugins

import app.andy.model.PluginActionContext
import app.andy.model.PluginHookEvents
import app.andy.model.PluginManifest
import app.andy.model.PluginManifestAction
import app.andy.model.PluginManifestBuild
import app.andy.model.PluginManifestEvent
import app.andy.model.PluginManifestPane
import app.andy.model.PluginManifestStartup
import app.andy.model.PluginPanePlacement
import app.andy.model.PluginPlatform
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml

class PluginManifestException(message: String) : IllegalArgumentException(message)

@Serializable
private data class RawPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    @SerialName("min_andy_version")
    val minAndyVersion: String? = null,
    val description: String? = null,
    val platforms: List<String>? = null,
    val build: List<RawBuild> = emptyList(),
    val startup: List<RawStartup> = emptyList(),
    val actions: List<RawAction> = emptyList(),
    val events: List<RawEvent> = emptyList(),
    val panes: List<RawPane> = emptyList(),
)

@Serializable
private data class RawBuild(
    val command: List<String> = emptyList(),
    val platforms: List<String>? = null,
)

@Serializable
private data class RawStartup(
    val command: List<String> = emptyList(),
    val platforms: List<String>? = null,
)

@Serializable
private data class RawAction(
    val id: String,
    val title: String,
    val description: String? = null,
    val contexts: List<String> = emptyList(),
    val command: List<String> = emptyList(),
    val platforms: List<String>? = null,
)

@Serializable
private data class RawEvent(
    val on: String,
    val command: List<String> = emptyList(),
    val platforms: List<String>? = null,
)

@Serializable
private data class RawPane(
    val id: String,
    val title: String,
    val description: String? = null,
    val placement: String = "split",
    val command: List<String> = emptyList(),
    val platforms: List<String>? = null,
)

internal object PluginManifestLoader {
    private val toml = Toml { ignoreUnknownKeys = true }

    fun load(path: File, currentAndyVersion: String): PluginManifest {
        val manifestFile = when {
            path.isDirectory -> File(path, "andy-plugin.toml")
            else -> path
        }
        if (!manifestFile.isFile) {
            throw PluginManifestException("plugin_manifest_not_found: ${manifestFile.absolutePath}")
        }
        val raw = runCatching {
            toml.decodeFromString(RawPluginManifest.serializer(), manifestFile.readText())
        }.getOrElse {
            throw PluginManifestException("plugin_manifest_parse_failed: ${it.message}")
        }
        return normalize(raw, currentAndyVersion)
    }

    private fun normalize(raw: RawPluginManifest, currentAndyVersion: String): PluginManifest {
        val id = requireId(raw.id, "invalid_plugin_id")
        val name = requireNonEmpty(raw.name, "invalid_plugin_name")
        val version = requireNonEmpty(raw.version, "invalid_plugin_version")
        val minAndy = requireNonEmpty(raw.minAndyVersion, "invalid_plugin_min_andy_version")
        if (!PluginVersion.isValid(minAndy)) {
            throw PluginManifestException("invalid_plugin_min_andy_version: expected dotted numeric version")
        }
        if (PluginVersion.compare(minAndy, currentAndyVersion) > 0) {
            throw PluginManifestException(
                "plugin_requires_newer_andy: plugin requires Andy $minAndy or newer; current is $currentAndyVersion",
            )
        }
        val description = raw.description?.trim()?.takeIf { it.isNotEmpty() }
        val platforms = raw.platforms?.map { parsePlatform(it) }
        val warnings = mutableListOf<String>()
        if (platforms == null) {
            warnings += "manifest does not declare platforms; platform support unknown"
        }

        val build = raw.build.map {
            PluginManifestBuild(command = requireCommand(it.command), platforms = it.platforms?.map(::parsePlatform))
        }
        val startup = raw.startup.map {
            PluginManifestStartup(command = requireCommand(it.command), platforms = it.platforms?.map(::parsePlatform))
        }
        val actions = raw.actions.map { action ->
            PluginManifestAction(
                id = requireLocalId(action.id, "invalid_plugin_action_id"),
                title = requireNonEmpty(action.title, "invalid_plugin_action_title"),
                description = action.description?.trim()?.takeIf { it.isNotEmpty() },
                contexts = action.contexts.mapNotNull(::parseContext),
                command = requireCommand(action.command),
                platforms = action.platforms?.map(::parsePlatform),
            )
        }
        rejectDuplicate(actions.map { it.id }, "duplicate_plugin_action_id")

        val events = raw.events.map { event ->
            PluginManifestEvent(
                on = requireNonEmpty(event.on, "invalid_plugin_event"),
                command = requireCommand(event.command),
                platforms = event.platforms?.map(::parsePlatform),
            )
        }
        events.forEach { hook ->
            if (!PluginHookEvents.isKnown(hook.on)) {
                warnings += "unknown event '${hook.on}'"
            }
        }

        val panes = raw.panes.map { pane ->
            if (pane.placement == "popup") {
                throw PluginManifestException(
                    "invalid_plugin_pane_placement: popup is not supported; use split, tab, overlay, or zoomed",
                )
            }
            val placement = when (pane.placement) {
                "overlay" -> PluginPanePlacement.Overlay
                "split" -> PluginPanePlacement.Split
                "tab" -> PluginPanePlacement.Tab
                "zoomed" -> PluginPanePlacement.Zoomed
                else -> throw PluginManifestException("invalid_plugin_pane_placement: ${pane.placement}")
            }
            PluginManifestPane(
                id = requireLocalId(pane.id, "invalid_plugin_pane_id"),
                title = requireNonEmpty(pane.title, "invalid_plugin_pane_title"),
                description = pane.description?.trim()?.takeIf { it.isNotEmpty() },
                placement = placement,
                command = requireCommand(pane.command),
                platforms = pane.platforms?.map(::parsePlatform),
            )
        }
        rejectDuplicate(panes.map { it.id }, "duplicate_plugin_pane_id")

        return PluginManifest(
            id = id,
            name = name,
            version = version,
            minAndyVersion = minAndy,
            description = description,
            platforms = platforms,
            build = build,
            startup = startup,
            actions = actions.sortedBy { it.id },
            events = events.sortedWith(compareBy({ it.on }, { it.command.joinToString("\u0000") })),
            panes = panes.sortedBy { it.id },
            warnings = warnings,
        )
    }

    private fun parsePlatform(raw: String): PluginPlatform = when (raw.trim()) {
        "linux" -> PluginPlatform.Linux
        "macos" -> PluginPlatform.Macos
        "windows" -> PluginPlatform.Windows
        else -> throw PluginManifestException("invalid_plugin_platform: $raw")
    }

    private fun parseContext(raw: String): PluginActionContext? = when (raw.trim()) {
        "global" -> PluginActionContext.Global
        "workspace" -> PluginActionContext.Workspace
        "tab" -> PluginActionContext.Tab
        "pane" -> PluginActionContext.Pane
        "selection" -> PluginActionContext.Selection
        else -> null
    }

    private fun requireCommand(command: List<String>): List<String> {
        val cleaned = command.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            throw PluginManifestException("invalid_plugin_command: command must be a non-empty argv array")
        }
        return cleaned
    }

    private fun requireId(raw: String, code: String): String {
        val value = requireNonEmpty(raw, code)
        if (!PLUGIN_ID_REGEX.matches(value) || value.length > 120) {
            throw PluginManifestException("$code: invalid plugin id")
        }
        return value
    }

    private fun requireLocalId(raw: String, code: String): String {
        val value = requireNonEmpty(raw, code)
        if (!LOCAL_ID_REGEX.matches(value) || value.length > 120) {
            throw PluginManifestException("$code: invalid id")
        }
        return value
    }

    private fun requireNonEmpty(raw: String?, code: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) throw PluginManifestException("$code: value is required")
        return value
    }

    private fun rejectDuplicate(ids: List<String>, code: String) {
        val seen = HashSet<String>()
        ids.forEach { id ->
            if (!seen.add(id)) throw PluginManifestException("$code: duplicate id '$id'")
        }
    }

    private val PLUGIN_ID_REGEX = Regex("^[A-Za-z0-9._:-]+$")
    private val LOCAL_ID_REGEX = Regex("^[A-Za-z0-9_:-]+$")
}

internal object PluginVersion {
    fun isValid(version: String): Boolean =
        version.isNotBlank() && version.split('.').all { it.any(Char::isDigit) }

    /** Negative if a < b, 0 if equal, positive if a > b. */
    fun compare(a: String, b: String): Int {
        val left = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
