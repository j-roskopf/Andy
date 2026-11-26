package app.andy.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.ui.components.TextField
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

private const val OTHER_OPTION = "__andy_other__"

/** A provider-neutral decision checkpoint: supplied options followed by optional freeform. */
@Composable
fun AgentUserInputCard(
    request: AgentUserInputRequest,
    onSubmit: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionPrompt = request.origin == AgentUserInputOrigin.AcpPermission
    val selections = remember(request.id) { mutableStateMapOf<String, String>() }
    val multiSelections = remember(request.id) { mutableStateMapOf<String, Set<String>>() }
    val freeformAnswers = remember(request.id) { mutableStateMapOf<String, String>() }

    fun isOtherSelected(question: AgentUserInputQuestion): Boolean =
        if (question.multiSelect) {
            OTHER_OPTION in multiSelections[question.id].orEmpty()
        } else {
            selections[question.id] == OTHER_OPTION
        }

    val canSubmit = request.questions.all { question ->
        when {
            permissionPrompt -> {
                val selection = selections[question.id]
                selection != null && selection != OTHER_OPTION
            }

            question.multiSelect -> {
                val chosen = multiSelections[question.id].orEmpty()
                chosen.isNotEmpty() &&
                    (!isOtherSelected(question) || !freeformAnswers[question.id].isNullOrBlank())
            }

            else -> {
                val selection = selections[question.id]
                when {
                    selection == null -> false
                    selection == OTHER_OPTION -> !freeformAnswers[question.id].isNullOrBlank()
                    else -> true
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (permissionPrompt) "Permission required" else "Decision needed",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            if (!permissionPrompt) {
                Text(
                    if (request.questions.any { it.multiSelect }) {
                        "Choose one or more options or enter your own answer."
                    } else {
                        "Choose an option or enter your own answer."
                    },
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
        }
        request.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.header.takeIf { it.isNotBlank() }?.let { header ->
                    Text(header, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
                Text(
                    question.question,
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                if (permissionPrompt) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        question.options.forEach { option ->
                            val deny = option.label.lowercase().let {
                                it.contains("deny") || it.contains("reject")
                            }
                            Text(
                                option.label,
                                color = if (deny) Red else Cyan,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onSubmit(mapOf(question.id to option.label)) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                } else {
                    question.options.forEach { option ->
                        if (question.multiSelect) {
                            val chosen = multiSelections[question.id].orEmpty()
                            ChoiceRow(
                                label = option.label,
                                description = option.description,
                                selected = option.label in chosen,
                                multi = true,
                                onSelect = {
                                    val next = chosen.toMutableSet()
                                    if (!next.add(option.label)) next.remove(option.label)
                                    multiSelections[question.id] = next
                                },
                            )
                        } else {
                            ChoiceRow(
                                label = option.label,
                                description = option.description,
                                selected = selections[question.id] == option.label,
                                onSelect = { selections[question.id] = option.label },
                            )
                        }
                    }
                }
                if (!permissionPrompt && question.allowFreeform) {
                    ChoiceRow(
                        label = "Other",
                        description = "Enter a different answer.",
                        selected = isOtherSelected(question),
                        multi = question.multiSelect,
                        onSelect = {
                            if (question.multiSelect) {
                                val next = multiSelections[question.id].orEmpty().toMutableSet()
                                if (!next.add(OTHER_OPTION)) next.remove(OTHER_OPTION)
                                multiSelections[question.id] = next
                            } else {
                                selections[question.id] = OTHER_OPTION
                            }
                        },
                    )
                }
                if (!permissionPrompt && question.allowFreeform && isOtherSelected(question)) {
                    TextField(
                        value = freeformAnswers[question.id].orEmpty(),
                        onValueChange = { freeformAnswers[question.id] = it },
                        placeholder = { Text("Your answer…", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (!permissionPrompt) {
            Text(
                "Continue",
                color = if (canSubmit) Cyan else TextSecondary.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = canSubmit) {
                        onSubmit(
                            request.questions.associate { question ->
                                question.id to if (question.multiSelect) {
                                    val chosen = multiSelections[question.id].orEmpty()
                                    buildList {
                                        chosen.filter { it != OTHER_OPTION }.forEach(::add)
                                        if (OTHER_OPTION in chosen) {
                                            freeformAnswers[question.id]?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let(::add)
                                        }
                                    }.joinToString("\n")
                                } else {
                                    when (val selection = selections[question.id]) {
                                        OTHER_OPTION -> freeformAnswers[question.id].orEmpty().trim()
                                        else -> selection.orEmpty()
                                    }
                                }
                            },
                        )
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    multi: Boolean = false,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            when {
                multi && selected -> "☑"
                multi -> "☐"
                selected -> "●"
                else -> "○"
            },
            color = if (selected) Cyan else TextSecondary.copy(alpha = 0.65f),
            fontFamily = MonoFont,
            fontSize = 12.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextPrimary, fontSize = 12.sp)
            description.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
