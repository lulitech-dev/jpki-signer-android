package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import dev.lulitech.jpkisigner.R
import dev.lulitech.jpkisigner.data.ExportNameProblem
import dev.lulitech.jpkisigner.data.FileNames

/**
 * Asks what the shared file should be called.
 *
 * The name is decoupled from storage: `FileProvider.getUriForFile` takes a
 * display name that `query()` returns in preference to the file's own name, so
 * whatever is typed here is what the recipient receives, and nothing on disk is
 * renamed.
 *
 * Opens with `<name>-signed.pdf` and only the `signed` token selected, so the
 * common edit is one keystroke rather than clearing the whole field.
 */
@Composable
fun ExportNameDialog(
    storedName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val proposal = remember(storedName) { FileNames.proposeExportName(storedName) }
    var value by remember(storedName) {
        mutableStateOf(
            TextFieldValue(
                text = proposal.text,
                selection = TextRange(proposal.selectionStart, proposal.selectionEnd),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }

    // Focus so the selection is visible and the keyboard replaces it directly.
    //
    // Guarded: requestFocus throws when no focus target is attached yet, and
    // whether the dialog's subcomposition has got that far by the time this runs
    // is not ours to decide. Where the cursor lands is not worth a crash over.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val problem = FileNames.validateExportName(value.text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.export_name_label)) },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(messageFor(it)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.text.trim()) },
                enabled = problem == null,
            ) { Text(stringResource(R.string.document_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun messageFor(problem: ExportNameProblem): String = stringResource(
    when (problem) {
        ExportNameProblem.BLANK -> R.string.export_error_blank
        ExportNameProblem.SEPARATORS -> R.string.export_error_separators
        ExportNameProblem.TOO_LONG -> R.string.export_error_too_long
        ExportNameProblem.NOT_PDF -> R.string.export_error_not_pdf
    },
)
