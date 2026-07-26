package app.andy.desktop.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Dedicated cwd for Agents-tab tasks launched without a project directory.
 *
 * Claude Code treats `$HOME` as an untrusted workspace and shows blocking
 * trust/bypass dialogs; Cursor/Codex are happier with a real directory too.
 * Artifacts already intended to live under `~/.andy-tasks` via
 * [AgentWorkflowArtifacts.dirFor].
 */
object AgentScratchWorkspace {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun path(home: File = File(System.getProperty("user.home"))): File =
        File(home, ".andy-tasks")

    /** Creates the scratch root (and a tiny README so the folder is obviously Andy-owned). */
    fun ensure(home: File = File(System.getProperty("user.home"))): File {
        val candidates = listOf(
            path(home),
            File(home, ".andy-tasks-scratch"),
            File(System.getProperty("java.io.tmpdir"), "andy-scratch"),
        )
        for (candidate in candidates) {
            writableDirectory(candidate)?.let { return seedReadme(it) }
        }
        error("unable to create Andy scratch workspace under ${home.absolutePath}")
    }

    fun resolveCwd(explicit: String?, home: File = File(System.getProperty("user.home"))): String {
        val trimmed = explicit?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            val dir = File(trimmed).absoluteFile.normalize()
            if (dir.isDirectory) return dir.absolutePath
        }
        return ensure(home).absolutePath
    }

    private fun writableDirectory(candidate: File): File? {
        if (candidate.exists() && !candidate.isDirectory) return null
        candidate.mkdirs()
        return candidate.takeIf { it.isDirectory }
    }

    private fun seedReadme(root: File): File {
        val readme = File(root, "README.txt")
        if (!readme.isFile) {
            readme.writeText(
                "Andy agent scratch workspace.\n" +
                    "Tasks started from the Agents tab without a project run here.\n",
            )
        }
        return root
    }

    fun isScratch(cwd: String?, home: File = File(System.getProperty("user.home"))): Boolean {
        val candidate = cwd?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.normalize() } ?: return true
        val scratch = path(home).absoluteFile.normalize()
        return candidate == scratch || candidate.path.startsWith(scratch.path + File.separator)
    }

    /**
     * Marks [workspace] trusted in `~/.claude.json` so interactive Claude skips the
     * "Do you trust this folder?" dialog. Never called for `$HOME` itself.
     */
    fun ensureClaudeTrust(workspace: File, home: File = File(System.getProperty("user.home"))) {
        val root = workspace.absoluteFile.normalize()
        val homeDir = home.absoluteFile.normalize()
        if (root == homeDir) return

        val config = File(homeDir, ".claude.json")
        val pathKey = root.absolutePath
        runCatching {
            val rootObj = if (config.isFile) {
                json.parseToJsonElement(config.readText()).jsonObject.toMutableMap()
            } else {
                mutableMapOf()
            }
            val projects = (rootObj["projects"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val existing = (projects[pathKey] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val alreadyTrusted = (existing["hasTrustDialogAccepted"] as? JsonPrimitive)?.let { prim ->
                prim.booleanOrNull == true || prim.contentOrNull.equals("true", ignoreCase = true)
            } == true
            if (alreadyTrusted) return

            existing["hasTrustDialogAccepted"] = JsonPrimitive(true)
            if (!existing.containsKey("allowedTools")) {
                existing["allowedTools"] = JsonArray(emptyList())
            }
            projects[pathKey] = JsonObject(existing)
            rootObj["projects"] = JsonObject(projects)
            config.parentFile?.mkdirs()
            val encoded = json.encodeToString(JsonObject.serializer(), JsonObject(rootObj)) + "\n"
            val tmp = File(config.parentFile, "${config.name}.tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(config)) {
                tmp.copyTo(config, overwrite = true)
                tmp.delete()
            }
        }
    }
}
