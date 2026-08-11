@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package app.andy.desktop.service.agents.acp

import app.andy.desktop.service.agents.normalizedAgentCommandName
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.model.ToolCallStatus
import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.AgentUserInputOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Converts ACP's normalized stream into Andy's structured transcript model. */
object AcpEventMapper {
    private val toolPayloadJson = Json { encodeDefaults = true; isLenient = true }
    fun map(
        update: SessionUpdate,
        atMillis: Long = System.currentTimeMillis(),
        knownSkillNames: Set<String> = emptySet(),
        allowedSkillNames: Set<String> = emptySet(),
        /** Resolves live buffered output for an embedded ACP terminal reference, if Andy is hosting it. */
        terminalOutput: (String) -> String? = { null },
    ): AgentEvent? = when (update) {
        is SessionUpdate.AgentMessageChunk -> textEvent(update.content, atMillis, thinking = false)
        is SessionUpdate.AgentThoughtChunk -> textEvent(update.content, atMillis, thinking = true)
        // Andy already records the prompt before sending it. Providers may echo the
        // user turn (and a resumed provider session may echo older turns), so never
        // project that echo into Andy's transcript.
        is SessionUpdate.UserMessageChunk -> null
        is SessionUpdate.ToolCall -> mapToolCall(
            id = update.toolCallId.toString(),
            title = update.title,
            kind = update.kind ?: ToolKind.OTHER,
            status = update.status ?: ToolCallStatus.PENDING,
            content = update.content.orEmpty(),
            locations = update.locations.orEmpty().map { it.path },
            rawInput = update.rawInput.payloadText(),
            rawOutput = update.rawOutput.payloadText(),
            atMillis = atMillis,
            terminalOutput = terminalOutput,
        )
        is SessionUpdate.ToolCallUpdate -> mapToolCall(
            id = update.toolCallId.toString(),
            title = update.title.orEmpty(),
            kind = update.kind ?: ToolKind.OTHER,
            status = update.status ?: ToolCallStatus.PENDING,
            content = update.content.orEmpty(),
            locations = update.locations.orEmpty().map { it.path },
            rawInput = update.rawInput.payloadText(),
            rawOutput = update.rawOutput.payloadText(),
            atMillis = atMillis,
            terminalOutput = terminalOutput,
        )
        is SessionUpdate.PlanUpdate -> AgentEvent.PlanUpdate(
            atMillis,
            update.entries.map { AgentPlanEntry(it.content, it.status.name.lowercase()) },
        )
        is SessionUpdate.PlanRemoved -> AgentEvent.PlanUpdate(atMillis, emptyList())
        is SessionUpdate.CurrentModeUpdate -> AgentEvent.ModeChanged(atMillis, update.currentModeId.toString())
        is SessionUpdate.AvailableCommandsUpdate -> AgentEvent.AvailableCommands(
            atMillis,
            mapSlashCommands(update),
        )
        is SessionUpdate.UsageUpdate -> AgentEvent.ContextUsage(atMillis, update.used, update.size)
        is SessionUpdate.SessionInfoUpdate -> AgentEvent.Raw(atMillis, "session: ${update.title}")
        is SessionUpdate.ConfigOptionUpdate -> AgentEvent.Raw(atMillis, "config options updated: ${update.configOptions}")
        is SessionUpdate.PlanUpdateV2 -> planUpdateFromVariant(update.plan, atMillis)
        is SessionUpdate.UnknownSessionUpdate -> AgentEvent.Raw(atMillis, update.toString())
    }

    fun mapSlashCommands(update: SessionUpdate.AvailableCommandsUpdate): List<AgentSlashCommand> =
        update.availableCommands.map { command ->
            AgentSlashCommand(
                name = command.name,
                description = command.description,
                inputHint = command.input?.toString(),
            )
        }

    /**
     * ACP command discovery is provider-scoped, but some providers also surface every skill they
     * can see on the host. Keep unknown provider built-ins and only remove a command when Andy can
     * identify it as an installed skill belonging to another provider's roots.
     */
    internal fun filterProviderCommands(
        commands: List<AgentSlashCommand>,
        knownSkillNames: Set<String>,
        allowedSkillNames: Set<String>,
    ): List<AgentSlashCommand> = commands.filter { command ->
        val name = command.name.normalizedAgentCommandName()
        name !in knownSkillNames || name in allowedSkillNames
    }

    internal fun planUpdateFromVariant(plan: PlanVariant, atMillis: Long): AgentEvent.PlanUpdate = when (plan) {
        is PlanVariant.Items -> AgentEvent.PlanUpdate(
            atMillis = atMillis,
            entries = plan.entries.map { entry -> entry.toAgentPlanEntry() },
        )
        is PlanVariant.Markdown -> AgentEvent.PlanUpdate(
            atMillis = atMillis,
            entries = emptyList(),
            markdown = plan.content,
        )
        is PlanVariant.File -> AgentEvent.PlanUpdate(
            atMillis = atMillis,
            entries = listOf(AgentPlanEntry(plan.uri, "file")),
        )
    }

    private fun PlanEntry.toAgentPlanEntry(): AgentPlanEntry = AgentPlanEntry(
        content = content,
        status = status.name.lowercase(),
    )

    /** Replace a mutable ACP tool row by id while preserving unrelated transcript events. */
    fun reduce(existing: List<AgentEvent>, incoming: AgentEvent): List<AgentEvent> {
        val incomingTool = incoming as? AgentEvent.ToolCall ?: return existing + incoming
        val id = incomingTool.toolCallId ?: return existing + incoming
        val index = existing.indexOfLast { (it as? AgentEvent.ToolCall)?.toolCallId == id }
        return if (index < 0) {
            existing + incoming
        } else {
            val previous = existing[index] as AgentEvent.ToolCall
            existing.toMutableList().also {
                it[index] = AcpToolCallPresentation.mergeToolCalls(previous, incomingTool)
            }
        }
    }

    private fun textEvent(content: ContentBlock, atMillis: Long, thinking: Boolean): AgentEvent? {
        val text = (content as? ContentBlock.Text)?.text ?: return AgentEvent.Raw(atMillis, content.renderText())
        if (text.isEmpty()) return null
        return if (thinking) {
            AgentEvent.Thinking(atMillis, text, isStreamDelta = true)
        } else {
            AgentEvent.AssistantText(atMillis, text, isStreamDelta = true)
        }
    }

    private fun mapToolCall(
        id: String,
        title: String,
        kind: ToolKind,
        status: ToolCallStatus,
        content: List<ToolCallContent>,
        locations: List<String>,
        rawInput: String?,
        rawOutput: String?,
        atMillis: Long,
        terminalOutput: (String) -> String?,
    ): AgentEvent.ToolCall {
        val details = content.joinToString("\n") { it.render(terminalOutput) }
            .ifBlank { listOfNotNull(rawInput, rawOutput).joinToString("\n") }
        val presented = AcpToolCallPresentation.present(title, rawInput, rawOutput, details)
        val summary = AcpToolCallPresentation.enrichSummary(
            summary = presented.summary,
            kind = kind.toAgentKind(),
            locations = locations,
        )
        return AgentEvent.ToolCall(
            atMillis = atMillis,
            toolName = presented.toolName,
            summary = summary,
            detail = presented.detail,
            toolCallId = id,
            kind = kind.toAgentKind(),
            state = status.toAgentState(),
            locations = locations,
            images = content.extractImages(),
        )
    }

    private fun ToolKind.toAgentKind(): AgentToolKind = when (this) {
        ToolKind.READ -> AgentToolKind.Read
        ToolKind.EDIT -> AgentToolKind.Edit
        ToolKind.DELETE -> AgentToolKind.Delete
        ToolKind.MOVE -> AgentToolKind.Move
        ToolKind.SEARCH -> AgentToolKind.Search
        ToolKind.EXECUTE -> AgentToolKind.Execute
        ToolKind.THINK -> AgentToolKind.Think
        ToolKind.FETCH -> AgentToolKind.Fetch
        ToolKind.SWITCH_MODE -> AgentToolKind.Other
        ToolKind.OTHER -> AgentToolKind.Other
    }

    private fun ToolCallStatus.toAgentState(): AgentToolState = when (this) {
        ToolCallStatus.PENDING -> AgentToolState.Pending
        ToolCallStatus.IN_PROGRESS -> AgentToolState.InProgress
        ToolCallStatus.COMPLETED -> AgentToolState.Completed
        ToolCallStatus.FAILED -> AgentToolState.Failed
    }

    private fun ToolCallContent.render(terminalOutput: (String) -> String?): String = when (this) {
        is ToolCallContent.Content -> content.renderText()
        is ToolCallContent.Diff -> buildString {
            append(path)
            if (!oldText.isNullOrBlank() || !newText.isNullOrBlank()) {
                append("\n--- old\n")
                append(oldText.orEmpty())
                append("\n+++ new\n")
                append(newText.orEmpty())
            }
        }
        is ToolCallContent.Terminal -> {
            val output = terminalOutput(terminalId)
            if (output.isNullOrEmpty()) "terminal $terminalId" else "terminal $terminalId\n$output"
        }
    }

    /** Human-readable stand-in for a content block. Never dumps binary payloads (e.g. image base64) as text. */
    private fun ContentBlock.renderText(): String = when (this) {
        is ContentBlock.Text -> text
        is ContentBlock.Image -> "[image]"
        is ContentBlock.Audio -> "[audio]"
        is ContentBlock.Resource -> "[resource]"
        is ContentBlock.ResourceLink -> "[resource: ${title ?: name}]"
    }

    /** Pulls inline image data out of tool content so the UI can render it instead of the "[image]" placeholder. */
    private fun List<ToolCallContent>.extractImages(): List<AgentToolImage> = mapNotNull { item ->
        val block = (item as? ToolCallContent.Content)?.content as? ContentBlock.Image ?: return@mapNotNull null
        if (block.data.isBlank()) return@mapNotNull null
        val mimeType = block.mimeType.ifBlank { "image/png" }
        AgentToolImage(dataUri = "data:$mimeType;base64,${block.data}")
    }

    private fun JsonElement?.payloadText(): String? = this?.let { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            else -> toolPayloadJson.encodeToString(JsonElement.serializer(), element)
        }
    }
}
