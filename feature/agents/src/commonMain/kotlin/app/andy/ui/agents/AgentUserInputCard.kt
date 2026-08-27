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
import app.andy.model.AgentUserInputRequest
import app.andy.ui.components.TextField
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

private const val OTHER_OPTION = "__andy_other__"

/** A provider-neutral decision checkpoint: supplied options followed by freeform Other. */
@Composable
fun AgentUserInputCard(
    request: AgentUserInputRequest,
    onSubmit: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionPrompt = request.origin == AgentUserInputOrigin.AcpPermission
    val selections = remember(request.id) { mutableStateMapOf<String, String>() }
    val freeformAnswers = remember(request.id) { mutableStateMapOf<String, String>() }
    val canSubmit = request.questions.all { question ->
        val selection = selections[question.id]
        when {
            selection == null -> false
            permissionPrompt -> selection != OTHER_OPTION
            selection == OTHER_OPTION -> !freeformAnswers[question.id].isNullOrBlank()
            else -> true
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
                    "Choose an option or enter your own answer.",
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
                        ChoiceRow(
                            label = option.label,
                            description = option.description,
                            selected = selections[question.id] == option.label,
                            onSelect = { selections[question.id] = option.label },
                        )
                    }
                }
                if (!permissionPrompt) {
                    ChoiceRow(
                        label = "Other",
                        description = "Enter a different answer.",
                        selected = selections[question.id] == OTHER_OPTION,
                        onSelect = { selections[question.id] = OTHER_OPTION },
                    )
                }
                if (!permissionPrompt && selections[question.id] == OTHER_OPTION) {
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
                        onSubmit(request.questions.associate { question ->
                            question.id to when (val selection = selections[question.id]) {
                                OTHER_OPTION -> freeformAnswers[question.id].orEmpty().trim()
                                else -> selection.orEmpty()
                            }
                        })
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
            if (selected) "●" else "○",
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
