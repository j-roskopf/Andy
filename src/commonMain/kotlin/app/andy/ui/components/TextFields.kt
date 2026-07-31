package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Compact field (~32dp single-line). Multiline fields use softer corner radii;
 * use [FieldChromeStyle.Borderless] when the parent container supplies the chrome.
 */
internal enum class FieldChromeStyle {
    Standard,
    Borderless,
}

@Composable
internal fun TextField(
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
    shape: androidx.compose.ui.graphics.Shape? = null,
    chromeStyle: FieldChromeStyle = FieldChromeStyle.Standard,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val effectiveShape = shape ?: AndyShape.Interactive

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            if (chromeStyle == FieldChromeStyle.Standard) {
                Modifier.andyFieldChrome(
                    enabled = enabled,
                    focused = focused,
                    singleLine = singleLine,
                    shape = effectiveShape,
                )
            } else {
                Modifier
            },
        ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = resolveFieldTextStyle(textStyle, enabled),
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(Rust),
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
internal fun TextField(
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
    shape: androidx.compose.ui.graphics.Shape? = null,
    chromeStyle: FieldChromeStyle = FieldChromeStyle.Standard,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val effectiveShape = shape ?: AndyShape.Interactive

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(
            if (chromeStyle == FieldChromeStyle.Standard) {
                Modifier.andyFieldChrome(
                    enabled = enabled,
                    focused = focused,
                    singleLine = singleLine,
                    shape = effectiveShape,
                )
            } else {
                Modifier
            },
        ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = resolveFieldTextStyle(textStyle, enabled),
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(Rust),
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
private fun defaultFieldTextStyle(): TextStyle = LocalTextStyle.current.copy(
    fontFamily = DisplayFont,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
    color = TextPrimary,
)

@Composable
private fun resolveFieldTextStyle(textStyle: TextStyle, enabled: Boolean): TextStyle {
    val baseColor = when {
        !enabled -> AndyColors.TextDisabled
        textStyle.color == Color.Unspecified -> TextPrimary
        else -> textStyle.color
    }
    return textStyle.copy(
        fontFamily = textStyle.fontFamily ?: DisplayFont,
        fontSize = if (textStyle.fontSize == TextUnit.Unspecified) 13.sp else textStyle.fontSize,
        lineHeight = if (textStyle.lineHeight == TextUnit.Unspecified) 16.sp else textStyle.lineHeight,
        color = baseColor,
    )
}

private fun Modifier.andyFieldChrome(
    enabled: Boolean,
    focused: Boolean,
    singleLine: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
): Modifier {
    val borderColor = when {
        !enabled -> Color.Transparent
        focused -> AndyColors.OrangeBorder
        else -> Color.Transparent
    }
    val container = when {
        !enabled -> AndyColors.PaneBg.copy(alpha = 0.55f)
        focused -> AndyColors.SurfaceRaised
        else -> AndyColors.SurfaceHover
    }
    return this
        .then(
            if (singleLine) {
                Modifier.defaultMinSize(minHeight = AndyLayout.FieldHeight)
            } else {
                Modifier
            },
        )
        .background(container, shape)
        .then(
            if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape)
            else Modifier,
        )
}

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
                horizontal = AndySpace.Space4,
                vertical = if (singleLine) 6.dp else AndySpace.Space3,
            ),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (empty && placeholder != null) {
            Box(Modifier.graphicsLayer(alpha = 0.55f)) {
                placeholder()
            }
        }
        innerTextField()
    }
}

@Composable
internal fun fieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = AndyColors.SurfaceRaised,
    unfocusedContainerColor = AndyColors.PaneBg,
    disabledContainerColor = AndyColors.PaneBg.copy(alpha = 0.55f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = Rust,
    focusedPlaceholderColor = AndyColors.TextTertiary,
    unfocusedPlaceholderColor = AndyColors.TextTertiary,
)

@Composable
internal fun FormRow(label: String, field: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Text(
            label,
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.width(96.dp),
        )
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
    minHeight: Dp = AndyLayout.FieldHeight,
    placeholder: String? = null,
    testTag: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            color = AndyColors.TextTertiary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
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
                        color = AndyColors.TextTertiary,
                        fontFamily = DisplayFont,
                        fontSize = 13.sp,
                    )
                }
            },
        )
    }
}

/** Dense mono field for paths, filters, and command-like input. */
@Composable
internal fun CodeFieldTextStyle(): TextStyle = LocalTextStyle.current.copy(
    fontFamily = MonoFont,
    fontSize = 12.sp,
    lineHeight = 14.sp,
    color = TextPrimary,
)
