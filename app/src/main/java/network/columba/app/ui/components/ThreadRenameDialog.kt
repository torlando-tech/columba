package network.columba.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import network.columba.app.R
import network.columba.app.data.util.ThreadPolicy

/**
 * Rename dialog (threads-spec §6.2): single-line, `Done` action, Save only
 * when the sanitized value changed, `Reset to automatic` only when a local
 * title exists (writes NULL — the automatic-excerpt state).
 *
 * Shared by the sheet row menu and the chat app bar so both entry points use
 * the same rules; [onResetToAutomatic] is wired by both callers.
 */
@Composable
internal fun ThreadRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onResetToAutomatic: () -> Unit,
    onDismiss: () -> Unit,
) {
    // `initialTitle` is seeded as a selection (not just a value) and the field
    // is focused on open, so typing replaces the whole name instead of
    // splicing into it at whatever offset an in-field tap landed on.
    var field by remember(initialTitle) {
        mutableStateOf(
            TextFieldValue(text = initialTitle, selection = TextRange(0, initialTitle.length)),
        )
    }
    val text = field.text
    val rules = threadRenameRules(initialTitle, text)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thread_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { new ->
                        if (renameInputAllowed(current = text, proposed = new.text)) field = new
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.thread_rename_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (rules.canSave) rules.saveValue?.let(onConfirm) },
                    ),
                )
                if (rules.canReset) {
                    TextButton(onClick = onResetToAutomatic) {
                        Text(stringResource(R.string.thread_rename_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { rules.saveValue?.let(onConfirm) },
                enabled = rules.canSave,
            ) {
                Text(stringResource(R.string.thread_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.thread_action_cancel))
            }
        },
    )
}

/**
 * Save/reset decision for the rename dialog (threads-spec §6.2): a blank or
 * unchanged value cannot be saved; reset is offered only when a local title
 * is stored (the automatic-excerpt state has nothing to reset).
 */
internal data class ThreadRenameRules(
    val saveValue: String?,
    val canSave: Boolean,
    val canReset: Boolean,
)

/**
 * Whether the rename field accepts an edit of [current] into [proposed].
 *
 * The stored title is capped, but the field is seeded with the thread's
 * automatic title — an excerpt that can be longer than the cap. A field the
 * user cannot shrink would make Rename a dead end, so deletions are always
 * accepted; growth stops at the cap.
 */
internal fun renameInputAllowed(current: String, proposed: String): Boolean =
    proposed.length <= ThreadPolicy.MAX_THREAD_TITLE_LENGTH || proposed.length < current.length

internal fun threadRenameRules(
    initialTitle: String,
    input: String,
): ThreadRenameRules {
    val sanitized = ThreadPolicy.sanitizeThreadTitle(input)
    val stored = initialTitle.ifBlank { null }
    return ThreadRenameRules(
        saveValue = sanitized,
        canSave = sanitized != null && sanitized != stored,
        canReset = stored != null,
    )
}
