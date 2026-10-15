package io.github.sreepv43.streamhub.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.AppColors

/**
 * A text field for the remote: it looks like a field and takes focus like a button, so moving past it
 * doesn't pop the on-screen keyboard up. Pressing OK opens a small editor with the keyboard.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    /** Type in the field itself (no editor window); [onDone] runs when the keyboard's Done / Enter is pressed. */
    inline: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val displayFocus = remember { FocusRequester() }
    val inlineFocus = remember { FocusRequester() }
    val wasEditing = remember { BooleanArray(1) }
    if (inline && editing) {
        Column(modifier) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = placeholder?.let { hint -> @Composable { Text(hint) } },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                isError = isError,
                colors = flatTextFieldColors(),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    onDone?.invoke()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inlineFocus)
                    // Back leaves typing mode (the first Back may only close the keyboard).
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                            editing = false
                            true
                        } else false
                    },
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { inlineFocus.requestFocus() }
        }
        wasEditing[0] = true
        return
    }
    if (inline && wasEditing[0]) {
        // Back from typing: the highlight returns to the box.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { displayFocus.requestFocus() }
        }
        wasEditing[0] = false
    }
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .focusRequester(displayFocus)
                .tvFocus(shape)
                .clip(shape)
                .panel(shape)
                .border(1.5.dp, if (isError) MaterialTheme.colorScheme.error else AppColors.panelRaised, shape)
                .clickable { editing = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.textDim)
            val shown = if (password) "•".repeat(value.length) else value
            Text(
                shown.ifEmpty { placeholder ?: "Press OK to type" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (shown.isEmpty()) AppColors.textDim else AppColors.text,
                maxLines = if (singleLine) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else AppColors.textDim,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
    if (editing && !inline) {
        TextEditorDialog(
            label = label,
            initial = value,
            placeholder = placeholder,
            keyboardType = keyboardType,
            password = password,
            singleLine = singleLine,
            onDone = { text ->
                editing = false
                if (text != null) onValueChange(text)
            },
        )
    }
}

@Composable
private fun TextEditorDialog(
    label: String,
    initial: String,
    placeholder: String?,
    keyboardType: KeyboardType,
    password: Boolean,
    singleLine: Boolean,
    /** The typed text, or null when cancelled. */
    onDone: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val field = remember { FocusRequester() }
    AlertDialog(
        containerColor = AppColors.panel,
        shape = DialogShape,
        onDismissRequest = { onDone(null) },
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder?.let { hint -> @Composable { Text(hint) } },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                colors = flatTextFieldColors(),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone(text.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        },
        confirmButton = { FlatButton(text = "OK", prominent = true, onClick = { onDone(text.text) }) },
        dismissButton = { FlatButton(text = "Cancel", onClick = { onDone(null) }) },
    )
    LaunchedEffect(Unit) {
        // The dialog is its own window; give it a frame to be laid out before asking for the keyboard.
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
}
