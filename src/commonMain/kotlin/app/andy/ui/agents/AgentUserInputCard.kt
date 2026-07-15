package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentUserInputRequest
import app.andy.ui.components.Button
import app.andy.ui.components.TextField
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

private const val OTHER_OPTION = "__andy_other__"

/** A provider-neutral decision checkpoint: supplied options followed by freeform Other. */
@Composable
internal fun AgentUserInputCard(
    request: AgentUserInputRequest,
    onSubmit: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selections = remember(request.id) { mutableStateMapOf<String, String>() }
    val freeformAnswers = remember(request.id) { mutableStateMapOf<String, String>() }
    val canSubmit = request.questions.all { question ->
        val selection = selections[question.id]
        selection != null && (selection != OTHER_OPTION || !freeformAnswers[question.id].isNullOrBlank())
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850.copy(alpha = 0.9f), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Rust.copy(alpha = 0.6f), RoundedCornerShape(AndyRadius.R4))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("DECISION NEEDED", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text("Choose an option or enter your own answer.", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        }
        request.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                question.header.takeIf { it.isNotBlank() }?.let { header ->
                    Text(header.uppercase(), color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Text(question.question, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                question.options.forEach { option ->
                    ChoiceRow(
                        label = option.label,
                        description = option.description,
                        selected = selections[question.id] == option.label,
                        onSelect = { selections[question.id] = option.label },
                    )
                }
                ChoiceRow(
                    label = "Other",
                    description = "Enter a different answer.",
                    selected = selections[question.id] == OTHER_OPTION,
                    onSelect = { selections[question.id] = OTHER_OPTION },
                )
                if (selections[question.id] == OTHER_OPTION) {
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
        Button(
            onClick = {
                onSubmit(request.questions.associate { question ->
                    question.id to when (val selection = selections[question.id]) {
                        OTHER_OPTION -> freeformAnswers[question.id].orEmpty().trim()
                        else -> selection.orEmpty()
                    }
                })
            },
            enabled = canSubmit,
            colors = primaryButtonColors(),
        ) { Text("Continue", fontSize = 11.sp) }
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
            .background(if (selected) Cyan.copy(alpha = 0.12f) else AndyColors.Neutral900.copy(alpha = 0.64f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, if (selected) Cyan.copy(alpha = 0.75f) else Border, RoundedCornerShape(AndyRadius.R2))
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(if (selected) "●" else "○", color = if (selected) Cyan else TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            description.takeIf { it.isNotBlank() }?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp) }
        }
    }
}
