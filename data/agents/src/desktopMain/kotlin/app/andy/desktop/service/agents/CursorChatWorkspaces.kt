package app.andy.desktop.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

internal data class CursorChatWorkspace(
    val chatId: String,
    val cwd: String,
    val title: String?,
    val hasConversation: Boolean,
    val historyTurns: Int,
    val storeBytes: Long,
    val updatedAtMs: Long,
)

/**
 * Cursor CLI stores chats under `~/.cursor/chats/<md5(cwd)>/<chatId>/`.
 * `--resume` only sees a thread when the process cwd (or `--workspace`) hashes
 * to that same bucket — importing from a different Andy project looks like a
 * brand-new empty chat with the same id.
 */
internal object CursorChatWorkspaces {
    private val json = Json { ignoreUnknownKeys = true }

    fun cursorHome(home: File = File(System.getProperty("user.home"))): File = File(home, ".cursor")

    fun find(
        chatId: String,
        home: File = File(System.getProperty("user.home")),
    ): CursorChatWorkspace? {
        val id = chatId.trim().takeIf { it.isNotEmpty() } ?: return null
        val chatsRoot = File(cursorHome(home), "chats")
        if (!chatsRoot.isDirectory) return null
        var best: CursorChatWorkspace? = null
        chatsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { bucket ->
                val dir = File(bucket, id)
                if (!dir.isDirectory) return@forEach
                val parsed = parseChat(id, dir) ?: return@forEach
                if (best == null || parsed.betterThan(best)) best = parsed
            }
        return best
    }

    private fun CursorChatWorkspace.betterThan(other: CursorChatWorkspace): Boolean {
        if (historyTurns != other.historyTurns) return historyTurns > other.historyTurns
        if (storeBytes != other.storeBytes) return storeBytes > other.storeBytes
        if (hasConversation != other.hasConversation) return hasConversation
        return updatedAtMs >= other.updatedAtMs
    }

    private fun parseChat(chatId: String, dir: File): CursorChatWorkspace? {
        val meta = File(dir, "meta.json")
        if (!meta.isFile) return null
        val root = runCatching {
            json.parseToJsonElement(meta.readText()).jsonObject
        }.getOrNull() ?: return null
        val cwd = root["cwd"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val historyFile = File(dir, "prompt_history.json")
        val historyTurns = if (historyFile.isFile) {
            runCatching {
                json.parseToJsonElement(historyFile.readText()).jsonArray.size
            }.getOrDefault(0)
        } else {
            0
        }
        val storeBytes = File(dir, "store.db").takeIf { it.isFile }?.length() ?: 0L
        return CursorChatWorkspace(
            chatId = chatId,
            cwd = cwd,
            title = root["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            hasConversation = root["hasConversation"]?.jsonPrimitive?.booleanOrNull == true,
            historyTurns = historyTurns,
            storeBytes = storeBytes,
            updatedAtMs = root["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: meta.lastModified(),
        )
    }
}
