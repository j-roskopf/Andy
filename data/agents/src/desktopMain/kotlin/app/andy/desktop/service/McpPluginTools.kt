package app.andy.desktop.service

import app.andy.model.PluginInvocationContext
import app.andy.model.PluginPanePlacement
import app.andy.service.PluginService
import app.andy.service.UnavailablePluginService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val PluginJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun Server.registerPluginTools(plugins: PluginService) {
    fun register(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        handler: suspend (Map<String, JsonElement>) -> CallToolResult,
    ) {
        addTool(
            name,
            description,
            ToolSchema(
                properties = buildJsonObject { properties.forEach { (k, v) -> put(k, v) } },
                required = required.takeIf { it.isNotEmpty() },
            ),
        ) { request ->
            try {
                handler(request.arguments ?: emptyMap())
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message ?: e.toString()}")),
                    isError = true,
                )
            }
        }
    }

    fun str(args: Map<String, JsonElement>, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    fun bool(args: Map<String, JsonElement>, key: String): Boolean =
        args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: (args[key]?.jsonPrimitive?.contentOrNull == "true")

    fun textResult(value: String) =
        CallToolResult(content = listOf(TextContent(text = value)))

    fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    register(
        name = "plugin.list",
        description = "List installed Andy plugins",
    ) {
        plugins.refresh()
        textResult(PluginJson.encodeToString(plugins.plugins.value.map { info ->
            mapOf(
                "id" to info.pluginId,
                "name" to info.name,
                "version" to info.version,
                "enabled" to info.enabled.toString(),
                "source" to info.source.kind.name.lowercase(),
                "path" to info.pluginRoot,
                "warnings" to info.warnings.joinToString("; "),
            )
        }))
    }

    register(
        name = "plugin.link",
        description = "Link a local plugin directory containing andy-plugin.toml",
        properties = mapOf(
            "path" to stringProp("Plugin directory or manifest path"),
            "enabled" to boolProp("Enable after link (default true)"),
        ),
        required = listOf("path"),
    ) { args ->
        val path = str(args, "path") ?: error("path is required")
        val enabled = args["enabled"]?.let { bool(args, "enabled") } ?: true
        val info = plugins.link(path, enabled)
        textResult(PluginJson.encodeToString(mapOf(
            "id" to info.pluginId,
            "name" to info.name,
            "warnings" to info.warnings.joinToString("; "),
        )))
    }

    register(
        name = "plugin.unlink",
        description = "Unlink a local plugin without deleting files",
        properties = mapOf("id" to stringProp("Plugin id")),
        required = listOf("id"),
    ) { args ->
        plugins.unlink(str(args, "id") ?: error("id is required"))
        textResult("unlinked")
    }

    register(
        name = "plugin.install",
        description = "Install a plugin from GitHub owner/repo[/subdir]",
        properties = mapOf(
            "spec" to stringProp("owner/repo[/subdir]"),
            "ref" to stringProp("Optional git ref"),
            "yes" to boolProp("Confirm noninteractive install"),
        ),
        required = listOf("spec"),
    ) { args ->
        val info = plugins.installGithub(
            spec = str(args, "spec") ?: error("spec is required"),
            ref = str(args, "ref"),
            yes = args["yes"]?.let { bool(args, "yes") } ?: false,
        )
        textResult(PluginJson.encodeToString(mapOf("id" to info.pluginId, "name" to info.name)))
    }

    register(
        name = "plugin.uninstall",
        description = "Uninstall a plugin by id or GitHub spec",
        properties = mapOf("id" to stringProp("Plugin id or owner/repo[/subdir]")),
        required = listOf("id"),
    ) { args ->
        plugins.uninstall(str(args, "id") ?: error("id is required"))
        textResult("uninstalled")
    }

    register(
        name = "plugin.enable",
        description = "Enable a plugin",
        properties = mapOf("id" to stringProp("Plugin id")),
        required = listOf("id"),
    ) { args ->
        plugins.setEnabled(str(args, "id") ?: error("id is required"), true)
        textResult("enabled")
    }

    register(
        name = "plugin.disable",
        description = "Disable a plugin",
        properties = mapOf("id" to stringProp("Plugin id")),
        required = listOf("id"),
    ) { args ->
        plugins.setEnabled(str(args, "id") ?: error("id is required"), false)
        textResult("disabled")
    }

    register(
        name = "plugin.config_dir",
        description = "Print a plugin config directory",
        properties = mapOf("id" to stringProp("Plugin id")),
        required = listOf("id"),
    ) { args ->
        textResult(plugins.configDir(str(args, "id") ?: error("id is required")))
    }

    register(
        name = "plugin.action.list",
        description = "List plugin actions",
        properties = mapOf("pluginId" to stringProp("Optional plugin id filter")),
    ) { args ->
        val rows = plugins.listActions(str(args, "pluginId")).map { (info, action) ->
            mapOf(
                "id" to "${info.pluginId}.${action.id}",
                "pluginId" to info.pluginId,
                "actionId" to action.id,
                "title" to action.title,
                "description" to (action.description ?: ""),
            )
        }
        textResult(PluginJson.encodeToString(rows))
    }

    register(
        name = "plugin.action.invoke",
        description = "Invoke a plugin action",
        properties = mapOf(
            "actionId" to stringProp("Action id or plugin.id.action"),
            "pluginId" to stringProp("Optional plugin id"),
            "workspaceId" to stringProp("Optional project id"),
            "paneId" to stringProp("Optional chat/task id"),
        ),
        required = listOf("actionId"),
    ) { args ->
        val log = plugins.invokeAction(
            actionId = str(args, "actionId") ?: error("actionId is required"),
            pluginId = str(args, "pluginId"),
            context = PluginInvocationContext(
                workspaceId = str(args, "workspaceId"),
                focusedPaneId = str(args, "paneId"),
                invocationSource = "action",
            ),
        )
        textResult(PluginJson.encodeToString(mapOf(
            "id" to log.id,
            "pluginId" to log.pluginId,
            "exitCode" to (log.exitCode?.toString() ?: ""),
            "stdout" to log.stdout,
            "stderr" to log.stderr,
        )))
    }

    register(
        name = "plugin.log.list",
        description = "List recent plugin command logs",
        properties = mapOf(
            "pluginId" to stringProp("Optional plugin id filter"),
            "limit" to stringProp("Max entries (default 20)"),
        ),
    ) { args ->
        val limit = str(args, "limit")?.toIntOrNull() ?: 20
        val pluginId = str(args, "pluginId")
        val rows = plugins.commandLogs.value
            .filter { pluginId == null || it.pluginId == pluginId }
            .take(limit)
        textResult(PluginJson.encodeToString(rows.map { log ->
            mapOf(
                "id" to log.id,
                "pluginId" to log.pluginId,
                "kind" to log.kind,
                "exitCode" to (log.exitCode?.toString() ?: ""),
                "command" to log.command.joinToString(" "),
            )
        }))
    }

    register(
        name = "plugin.pane.open",
        description = "Open a plugin pane in a Terminal dock",
        properties = mapOf(
            "pluginId" to stringProp("Plugin id"),
            "entrypoint" to stringProp("Pane entrypoint id"),
            "placement" to stringProp("split|tab|overlay|zoomed"),
        ),
        required = listOf("pluginId", "entrypoint"),
    ) { args ->
        val placement = str(args, "placement")?.let {
            PluginPanePlacement.entries.firstOrNull { p -> p.name.equals(it, ignoreCase = true) }
                ?: error("invalid placement: $it")
        }
        val session = plugins.openPane(
            pluginId = str(args, "pluginId") ?: error("pluginId is required"),
            entrypoint = str(args, "entrypoint") ?: error("entrypoint is required"),
            placement = placement,
        )
        textResult(PluginJson.encodeToString(mapOf(
            "paneId" to session.paneId,
            "runId" to session.runId,
            "title" to session.title,
            "placement" to session.placement.name.lowercase(),
        )))
    }

    register(
        name = "plugin.pane.focus",
        description = "Focus an open plugin pane",
        properties = mapOf("paneId" to stringProp("Plugin pane id")),
        required = listOf("paneId"),
    ) { args ->
        val session = plugins.focusPane(str(args, "paneId") ?: error("paneId is required"))
            ?: error("pane not found")
        textResult(session.paneId)
    }

    register(
        name = "plugin.pane.close",
        description = "Close an open plugin pane",
        properties = mapOf("paneId" to stringProp("Plugin pane id")),
        required = listOf("paneId"),
    ) { args ->
        plugins.closePane(str(args, "paneId") ?: error("paneId is required"))
        textResult("closed")
    }
}

/** No-op binder helper so callers can pass UnavailablePluginService safely. */
internal fun pluginToolsOrUnavailable(plugins: PluginService?): PluginService =
    plugins ?: UnavailablePluginService
