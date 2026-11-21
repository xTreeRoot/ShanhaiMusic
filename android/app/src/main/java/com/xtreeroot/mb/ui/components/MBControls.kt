package com.xtreeroot.mb.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal val LocalMBPrimaryAction = staticCompositionLocalOf { false }

@Composable
internal fun MBTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                          contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                          content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val primary = LocalMBPrimaryAction.current
    TextButton(onClick, modifier.heightIn(min = 44.dp), enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (primary) colors.primary else colors.onSurface,
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f)),
        contentPadding = contentPadding, content = content)
}

@Composable
internal fun MBIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                          colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), content: @Composable () -> Unit) {
    IconButton(onClick, modifier, enabled, colors = colors, shape = CircleShape, content = content)
}

@Composable
internal fun MBTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
                         enabled: Boolean = true, label: @Composable (() -> Unit)? = null,
                         placeholder: @Composable (() -> Unit)? = null, leadingIcon: @Composable (() -> Unit)? = null,
                         trailingIcon: @Composable (() -> Unit)? = null, supportingText: @Composable (() -> Unit)? = null,
                         isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
                         keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
                         singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
                         shape: Shape = RoundedCornerShape(18.dp), colors: TextFieldColors = TextFieldDefaults.colors(
                             focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                             unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                             focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                             disabledIndicatorColor = Color.Transparent, errorIndicatorColor = MaterialTheme.colorScheme.error)) {
    TextField(value, onValueChange, modifier, enabled = enabled, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape, colors = colors)
}

@Composable
internal fun MBSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Switch(checked, onCheckedChange, modifier, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary,
        uncheckedThumbColor = colors.surface, uncheckedTrackColor = colors.surfaceContainerHighest,
        uncheckedBorderColor = Color.Transparent))
}

@Composable
internal fun MBCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Checkbox(checked, onCheckedChange, modifier, enabled = enabled,
        colors = CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.outline))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                      valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
                      interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
                      thumb: @Composable (SliderState) -> Unit = { SliderDefaults.Thumb(interactionSource) },
                      track: @Composable (SliderState) -> Unit = { SliderDefaults.Track(it,
                          colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest)) }) {
    Slider(value, onValueChange, modifier, enabled, onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps,
        interactionSource = interactionSource, thumb = thumb, track = track,
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest))
}

@Composable
internal fun MBFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
                          modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(selected, onClick, label, modifier.heightIn(min = 44.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        border = null, colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = MaterialTheme.colorScheme.onSurface, selectedLabelColor = MaterialTheme.colorScheme.surface))
}
