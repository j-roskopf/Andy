package app.andy.desktop.service.computeruse

import app.andy.model.ComputerUseGrantProfile
import app.andy.model.ComputerUseScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Serialize grant profiles as a JSON blob for workspace.properties. */
object ComputerUseProfileStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(profiles: List<ComputerUseGrantProfile>): String =
        json.encodeToString(ListSerializer(ComputerUseGrantProfile.serializer()), profiles)

    fun decode(raw: String?): List<ComputerUseGrantProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ComputerUseGrantProfile.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun validateScope(scope: ComputerUseScope): String? {
        if (scope.wholeDesktop) return null
        if (scope.appNames.isEmpty()) return "Scope must include at least one app (or wholeDesktop)"
        val denied = scope.appNames.filter { ComputerUseScopeDenylist.isDenied(it) }
        if (denied.isNotEmpty()) {
            return ComputerUseScopeDenylist.deniedReason(denied.first())
        }
        return null
    }
}
