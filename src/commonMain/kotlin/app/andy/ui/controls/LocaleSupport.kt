package app.andy.ui.controls

import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.service.DeviceService

data class LocaleChangeResult(
    val result: CommandResult,
    val method: LocaleMethod,
)

enum class LocaleMethod(val label: String) {
    CmdLocale("cmd locale set-locales"),
    SetpropRestart("setprop + am restart"),
    AppLocale("cmd locale set-app-locales"),
}

val PSEUDO_LOCALES: List<Pair<String, String>> = listOf(
    "en-XA" to "Accented (en-XA)",
    "ar-XB" to "Bidi (ar-XB)",
)

/**
 * Prefer API 33+ `cmd locale set-locales`, then setprop+restart, then per-app locales.
 */
suspend fun DeviceService.setDeviceLocale(
    serial: String,
    tag: String,
    apps: AppService? = null,
    allowFrameworkRestart: Boolean = false,
): LocaleChangeResult {
    val normalized = tag.trim()
    require(normalized.isNotEmpty()) { "locale tag required" }

    val cmdLocales = shell(serial, listOf("cmd", "locale", "set-locales", normalized))
    if (cmdLocales.isSuccess && !looksLikeUnknownCommand(cmdLocales)) {
        return LocaleChangeResult(
            CommandResult.success("Locale $normalized via ${LocaleMethod.CmdLocale.label}"),
            LocaleMethod.CmdLocale,
        )
    }

    if (allowFrameworkRestart) {
        val setprop = shell(serial, listOf("setprop", "persist.sys.locale", normalized))
        if (setprop.isSuccess) {
            shell(serial, listOf("am", "restart"))
            return LocaleChangeResult(
                CommandResult.success(
                    "Locale $normalized via ${LocaleMethod.SetpropRestart.label} (framework restarting)",
                ),
                LocaleMethod.SetpropRestart,
            )
        }
    }

    val pkg = apps?.focusedPackage(serial)
    if (pkg != null) {
        val appLocale = shell(
            serial,
            listOf("cmd", "locale", "set-app-locales", pkg, "--locales", normalized),
        )
        if (appLocale.isSuccess && !looksLikeUnknownCommand(appLocale)) {
            return LocaleChangeResult(
                CommandResult.success(
                    "App locale $normalized for $pkg via ${LocaleMethod.AppLocale.label}",
                ),
                LocaleMethod.AppLocale,
            )
        }
    }

    return LocaleChangeResult(
        CommandResult.failure(
            cmdLocales.stderr.ifBlank { cmdLocales.stdout }
                .ifBlank { "Failed to set locale $normalized" },
        ),
        LocaleMethod.CmdLocale,
    )
}

suspend fun DeviceService.setAppLocale(serial: String, packageName: String, tag: String): CommandResult {
    val result = shell(
        serial,
        listOf("cmd", "locale", "set-app-locales", packageName, "--locales", tag.trim()),
    )
    return if (result.isSuccess && !looksLikeUnknownCommand(result)) {
        CommandResult.success("App locale ${tag.trim()} for $packageName")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "set-app-locales failed" })
    }
}

suspend fun DeviceService.currentDeviceLocale(serial: String): String? {
    val fromCmd = shell(serial, listOf("cmd", "locale", "get-locales"))
    if (fromCmd.isSuccess) {
        val parsed = parseLocalesOutput(fromCmd.stdout)
        if (parsed != null) return parsed
    }
    val fromProp = shell(serial, listOf("getprop", "persist.sys.locale"))
    return fromProp.stdout.trim().takeIf { it.isNotBlank() && it != "null" }
}

fun parseLocalesOutput(output: String): String? {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    // Prefer a BCP-47-looking token
    for (line in lines) {
        if (line.startsWith("Locales", ignoreCase = true)) {
            val after = line.substringAfter(':').trim().trim('[', ']')
            if (after.isNotBlank()) return after.split(',', ' ').firstOrNull { it.isNotBlank() }
        }
        if (Regex("""^[a-z]{2,3}([-_][A-Za-z0-9]+)*$""").matches(line)) return line
    }
    return lines.firstOrNull()
}

private fun looksLikeUnknownCommand(result: CommandResult): Boolean {
    val text = "${result.stdout}\n${result.stderr}".lowercase()
    return text.contains("unknown command") ||
        text.contains("not found") ||
        text.contains("no such") ||
        text.contains("usage:")
}
