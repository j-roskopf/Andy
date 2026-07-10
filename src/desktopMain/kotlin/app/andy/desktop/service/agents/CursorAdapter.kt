package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli
import app.andy.model.promptWithImageHints

class CursorAdapter : AgentCliAdapter {
    override val kind = AgentKind.Cursor
    override val supportsHeadlessResume = true
    override val supportsStreamJson = true

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        add("-p")
        add("--output-format"); add("stream-json")
        task.modelForCli()?.let { add("--model"); add(it) }
        if (task.autonomy == AgentAutonomy.Full) add("--force")
        add(task.promptForCli())
    }

    override fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>? {
        val chatId = task.vendorSessionId ?: return null
        return buildList {
            add(binary)
            add("-p")
            add("--output-format"); add("stream-json")
            add("--resume"); add(chatId)
            task.modelForCli()?.let { add("--model"); add(it) }
            if (task.autonomy == AgentAutonomy.Full) add("--force")
            add(promptWithImageHints(followUp, imagePaths))
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val chatId = task.vendorSessionId
        return if (chatId != null) "${shellQuote(binary)} --resume ${shellQuote(chatId)}" else shellQuote(binary)
    }

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> {
        val obj = parseJsonObject(line) ?: return rawIfNotBlank(line, nowMillis)
        // cursor-agent's stream-json mimics Claude Code's schema.
        return parseClaudeStyleObject(obj, nowMillis) ?: rawIfNotBlank(line, nowMillis)
    }
}
