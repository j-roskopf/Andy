package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

/**
 * Compact field (~32dp single-line). Multiline fields use the same chrome;
 * use [FieldChromeStyle.Borderless] when the parent container supplies the border.
 */
enum class FieldChromeStyle {
    Standard,
    Borderless,
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = defaultFieldTextStyle(),
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = fieldColors(),
    shape: Shape? = null,
    chromeStyle: FieldChromeStyle = FieldChromeStyle.Standard,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val effectiveShape = shape ?: AndyShape.Interactive
    val tokens = andyTokens()
    val borderColor = when {
        !enabled -> AndyColors.BorderEmphasized.copy(alpha = 0.5f)
        isError -> tokens.error
        focused -> tokens.accent
        else -> AndyColors.BorderEmphasized
    }
    val container = AndyColors.SurfaceRaised
    val insetRingColor = when {
        !enabled || isError -> null
        focused -> tokens.accentMuted
        hovered && !focused -> AndyColors.BorderEmphasized.copy(alpha = 0.30f)
        else -> null
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            if (chromeStyle == FieldChromeStyle.Standard) {
                Modifier.andyFieldChrome(
                    singleLine = singleLine,
                    shape = effectiveShape,
                    borderColor = borderColor,
                    container = container,
                    insetRingColor = insetRingColor,
                )
            } else {
                Modifier
            },
        ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = resolveFieldTextStyle(textStyle, enabled, isError),
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else tokens.accent),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            FieldDecoration(
                empty = value.isEmpty(),
                singleLine = singleLine,
                placeholder = placeholder,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = defaultFieldTextStyle(),
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = fieldColors(),
    shape: Shape? = null,
    chromeStyle: FieldChromeStyle = FieldChromeStyle.Standard,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val effectiveShape = shape ?: AndyShape.Interactive
    val tokens = andyTokens()
    val borderColor = when {
        !enabled -> AndyColors.BorderEmphasized.copy(alpha = 0.5f)
        isError -> tokens.error
        focused -> tokens.accent
        else -> AndyColors.BorderEmphasized
    }
    val container = AndyColors.SurfaceRaised
    val insetRingColor = when {
        !enabled || isError -> null
        focused -> tokens.accentMuted
        hovered && !focused -> AndyColors.BorderEmphasized.copy(alpha = 0.30f)
        else -> null
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            if (chromeStyle == FieldChromeStyle.Standard) {
                Modifier.andyFieldChrome(
                    singleLine = singleLine,
                    shape = effectiveShape,
                    borderColor = borderColor,
                    container = container,
                    insetRingColor = insetRingColor,
                )
            } else {
                Modifier
            },
        ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = resolveFieldTextStyle(textStyle, enabled, isError),
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else tokens.accent),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            FieldDecoration(
                empty = value.text.isEmpty(),
                singleLine = singleLine,
                placeholder = placeholder,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
private fun defaultFieldTextStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontFamily = DisplayFont,
    color = TextPrimary,
)

@Composable
private fun resolveFieldTextStyle(textStyle: TextStyle, enabled: Boolean, isError: Boolean): TextStyle {
    val baseColor = when {
        !enabled -> AndyColors.TextDisabled
        isError -> MaterialTheme.colorScheme.error
        textStyle.color == Color.Unspecified -> TextPrimary
        else -> textStyle.color
    }
    return textStyle.copy(
        fontFamily = textStyle.fontFamily ?: DisplayFont,
        fontSize = if (textStyle.fontSize == TextUnit.Unspecified) 14.sp else textStyle.fontSize,
        lineHeight = if (textStyle.lineHeight == TextUnit.Unspecified) 20.sp else textStyle.lineHeight,
        color = baseColor,
    )
}

private fun Modifier.andyFieldChrome(
    singleLine: Boolean,
    shape: Shape,
    borderColor: Color,
    container: Color,
    insetRingColor: Color?,
): Modifier = this
    .then(
        if (singleLine) {
            Modifier.defaultMinSize(minHeight = AndyLayout.FieldHeight)
        } else {
            Modifier
        },
    )
    .background(container, shape)
    .border(1.dp, borderColor, shape)
    .then(
        if (insetRingColor != null) {
            Modifier.drawWithContent {
                drawContent()
                val inset = 2.dp.toPx()
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = insetRingColor,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            }
        } else {
            Modifier
        },
    )

@Composable
private fun FieldDecoration(
    empty: Boolean,
    singleLine: Boolean,
    placeholder: @Composable (() -> Unit)?,
    innerTextField: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AndySpace.Space2,
                vertical = if (singleLine) AndySpace.Space1 else AndySpace.Space2,
            ),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (empty && placeholder != null) {
            placeholder()
        }
        innerTextField()
    }
}

@Composable
fun fieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = AndyColors.SurfaceRaised,
    unfocusedContainerColor = AndyColors.PaneBg,
    disabledContainerColor = AndyColors.PaneBg.copy(alpha = 0.55f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
    errorCursorColor = MaterialTheme.colorScheme.error,
    focusedPlaceholderColor = AndyColors.TextTertiary,
    unfocusedPlaceholderColor = AndyColors.TextTertiary,
    errorPlaceholderColor = AndyColors.TextTertiary,
)

/** Horizontal label + field row (settings tables). */
@Composable
fun FormRow(label: String, field: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Text(
            label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = DisplayFont,
            modifier = Modifier.width(96.dp),
        )
        field()
    }
}

/**
 * Label-above-input block (design-taste §4.6): label, field, optional helper, optional error below.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = AndyLayout.FieldHeight,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    testTag: String? = null,
) {
    val isError = errorText != null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
        Text(
            label,
            color = AndyColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = DisplayFont,
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            isError = isError,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (singleLine) Modifier.defaultMinSize(minHeight = minHeight)
                    else Modifier.heightIn(min = minHeight),
                )
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            textStyle = defaultFieldTextStyle(),
            placeholder = placeholder?.let { hint ->
                {
                    Text(
                        hint,
                        color = AndyColors.TextDisabled,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = DisplayFont,
                    )
                }
            },
        )
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = DisplayFont,
            )
        } else if (helperText != null) {
            Text(
                helperText,
                color = AndyColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = DisplayFont,
            )
        }
    }
}

/** Dense mono field for paths, filters, and command-like input. */
@Composable
fun CodeFieldTextStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(
    fontFamily = MonoFont,
    lineHeight = 14.sp,
    color = TextPrimary,
)
