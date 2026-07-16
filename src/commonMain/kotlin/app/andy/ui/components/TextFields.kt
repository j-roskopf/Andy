package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontFamily = MonoFont, color = TextPrimary),
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = fieldColors(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val enabledAlpha = if (enabled) 1f else 0.48f
    val effectiveTextStyle = textStyle.copy(fontFamily = MonoFont, color = if (textStyle.color == Color.Unspecified) TextPrimary else textStyle.color)
    val fieldShape = shape
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.62f * enabledAlpha), fieldShape)
            .border(1.dp, AndyColors.Neutral100.copy(alpha = 0.18f * enabledAlpha), fieldShape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = effectiveTextStyle,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(Rust),
        decorationBox = { innerTextField ->
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Box(Modifier.graphicsLayer(alpha = 0.62f)) {
                        placeholder()
                    }
                }
                innerTextField()
            }
        },
    )
}

/** Variant for callers that need to preserve or set the caret position explicitly. */
@Composable
internal fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontFamily = MonoFont, color = TextPrimary),
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = fieldColors(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val enabledAlpha = if (enabled) 1f else 0.48f
    val effectiveTextStyle = textStyle.copy(fontFamily = MonoFont, color = if (textStyle.color == Color.Unspecified) TextPrimary else textStyle.color)
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.62f * enabledAlpha), shape)
            .border(1.dp, AndyColors.Neutral100.copy(alpha = 0.18f * enabledAlpha), shape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = effectiveTextStyle,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(Rust),
        decorationBox = { innerTextField ->
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.text.isEmpty() && placeholder != null) {
                    Box(Modifier.graphicsLayer(alpha = 0.62f)) { placeholder() }
                }
                innerTextField()
            }
        },
    )
}

@Composable
internal fun fieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = AndyColors.Neutral900.copy(alpha = 0.72f),
    unfocusedContainerColor = AndyColors.Neutral850,
    disabledContainerColor = AndyColors.Neutral800,
    focusedIndicatorColor = AndyColors.OrangeBorder,
    unfocusedIndicatorColor = Border,
    disabledIndicatorColor = Border.copy(alpha = 0.45f),
    cursorColor = Rust,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
)

@Composable
internal fun FormRow(label: String, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
        field()
    }
}

@Composable
internal fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 54.dp,
    placeholder: String? = null,
    testTag: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
            colors = fieldColors(),
            placeholder = placeholder?.let { hint ->
                { Text(hint.lowercase(), color = TextSecondary, fontFamily = MonoFont) }
            },
        )
    }
}
