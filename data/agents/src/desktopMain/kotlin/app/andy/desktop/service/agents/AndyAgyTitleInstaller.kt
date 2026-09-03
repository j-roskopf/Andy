package app.andy.desktop.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

private val agySettingsJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Antigravity (`agy`) title + settings install.
 *
 * agy has no ACP; it does expose structured `agent_state` / `tool_confirmation_pending`
 * via the title/statusLine command JSON. Andy installs a title script that maps those
 * fields into `.andy/<taskId>/status.json` and OSC-scrapeable `andy:*` markers.
 */
object AndyAgyTitleInstaller {
    const val SCRIPT_NAME = "andy-agy-title.sh"
    const val MARKER = "andy-agy-title"
    const val STABLE_TITLE_COMMAND = "\"\$HOME/.andy/bin/$SCRIPT_NAME\""

    /** Packaged script body — keep in sync with [scripts/andy-agy-title.sh]. */
    val scriptContent: String =
        """
        #!/bin/sh
        # Andy-managed Antigravity title — do not edit.
        # Installed to ~/.andy/bin/andy-agy-title.sh by the Andy desktop app, andyd, or install-andy.sh.
        #
        # Wired as ~/.gemini/antigravity-cli/settings.json → title.command.
        # agy pipes the same JSON payload used by statusLine (agent_state, tool_confirmation_pending, …)
        # on stdin; we write .andy/<taskId>/status.json and print a window title with andy:* markers
        # that AgentStatusTracker scrapes via OSC / pane title.
        payload=${'$'}(cat 2>/dev/null || true)

        state=${'$'}(printf '%s' "${'$'}payload" | grep -o '"agent_state"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"${'$'}/\1/')
        pending=0
        if printf '%s' "${'$'}payload" | grep -Eq '"tool_confirmation_pending"[[:space:]]*:[[:space:]]*true'; then
          pending=1
        fi

        if [ "${'$'}pending" -eq 1 ]; then
          status=blocked
          marker=andy:blocked
        else
          case "${'$'}state" in
          idle)
            status=done
            marker=andy:idle
            ;;
          thinking|working|tool_use)
            status=working
            marker=andy:working
            ;;
          *)
            status=""
            marker=""
            ;;
          esac
        fi

        HOOK="${'$'}{HOME}/.andy/bin/andy-status-hook.sh"
        if [ -x "${'$'}HOOK" ] && [ -n "${'$'}status" ]; then
          printf '' | "${'$'}HOOK" "${'$'}status" >/dev/null 2>&1 || true
        fi

        if [ -n "${'$'}marker" ]; then
          printf 'agy %s\n' "${'$'}marker"
        else
          printf 'agy\n'
        fi
        """.trimIndent() + "\n"

    fun scriptFile(home: File = File(System.getProperty("user.home"))): File =
        File(home, ".andy/bin/$SCRIPT_NAME")

    fun settingsFile(home: File = File(System.getProperty("user.home"))): File =
        File(home, ".gemini/antigravity-cli/settings.json")

    fun ensureInstalled(home: File = File(System.getProperty("user.home"))): File {
        val dest = scriptFile(home)
        dest.parentFile?.mkdirs()
        val existing = dest.takeIf { it.isFile }?.readText()
        if (existing != scriptContent) {
            dest.writeText(scriptContent)
        }
        dest.setExecutable(true, false)
        return dest
    }

    /**
     * Point agy's title.command at Andy's script when unset or already Andy-managed.
     * Never replaces a user's custom title command.
     */
    fun installTitleSettings(home: File = File(System.getProperty("user.home"))): Boolean {
        ensureInstalled(home)
        val settings = settingsFile(home)
        settings.parentFile?.mkdirs()
        val existingRoot = when (val read = readAgySettings(settings)) {
            AgySettingsRead.Missing -> null
            AgySettingsRead.Invalid -> return false
            is AgySettingsRead.Ok -> read.root
        }
        val root = existingRoot?.entries?.associateTo(mutableMapOf()) { it.key to it.value }
            ?: mutableMapOf()
        val titleObj = root["title"] as? JsonObject
        val existingCommand = (titleObj?.get("command") as? JsonPrimitive)?.content.orEmpty()
        if (existingCommand.isNotBlank() && MARKER !in existingCommand) {
            return false
        }
        root["title"] = JsonObject(
            mapOf(
                "command" to JsonPrimitive(STABLE_TITLE_COMMAND),
                "enabled" to JsonPrimitive(true),
            ),
        )
        val encoded = agySettingsJson.encodeToString(JsonObject.serializer(), JsonObject(root)) + "\n"
        if (settings.takeIf { it.isFile }?.readText() == encoded) return true
        settings.writeText(encoded)
        return true
    }
}

private sealed interface AgySettingsRead {
    data object Missing : AgySettingsRead
    data object Invalid : AgySettingsRead
    data class Ok(val root: JsonObject) : AgySettingsRead
}

private fun readAgySettings(file: File): AgySettingsRead {
    if (!file.isFile) return AgySettingsRead.Missing
    return runCatching { agySettingsJson.parseToJsonElement(file.readText()).jsonObject }
        .fold(
            onSuccess = { AgySettingsRead.Ok(it) },
            onFailure = { AgySettingsRead.Invalid },
        )
}
