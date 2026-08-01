package app.andy.desktop.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Resolves the Hermes inference provider Andy should pass on launch.
 *
 * Hermes' `auto` provider often fails when Andy supplies `--model` but no API keys
 * are present in ~/.hermes/.env — even when OAuth providers such as Nous Portal
 * are logged in via ~/.hermes/auth.json.
 */
internal object HermesProviderIds {
    private val configuredProvider = Regex("""(?m)^\s*provider:\s*(\S+)\s*$""")

    fun resolveForLaunch(home: File = File(System.getProperty("user.home"))): String? {
        val configured = readConfiguredProvider(home)?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
        return configured ?: readAuthenticatedProvider(home)
    }

    internal fun readConfiguredProvider(home: File): String? = runCatching {
        val config = File(home, ".hermes/config.yaml")
        if (!config.isFile) return null
        configuredProvider.find(config.readText())?.groupValues?.get(1)
    }.getOrNull()

    internal fun readAuthenticatedProvider(home: File): String? = runCatching {
        val auth = File(home, ".hermes/auth.json")
        if (!auth.isFile) return null
        val providers = Json.parseToJsonElement(auth.readText()).jsonObject["providers"]?.jsonObject ?: return null
        providers.entries.firstOrNull { (_, value) ->
            value.jsonObject["access_token"]?.jsonPrimitive?.content?.isNotBlank() == true
        }?.key
    }.getOrNull()
}
