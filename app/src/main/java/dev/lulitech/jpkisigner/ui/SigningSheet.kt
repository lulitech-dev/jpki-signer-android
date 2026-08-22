package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.lulitech.jpkisigner.R

/** Where the signing flow currently is. */
sealed interface SigningState {
    /** Collecting reason, location and PIN. */
    data object Form : SigningState

    /** Reader mode is on; waiting for the card. */
    data object WaitingForCard : SigningState

    /** The card is in the field and the run is under way. */
    data object Working : SigningState

    data object Succeeded : SigningState

    data class Failed(val message: String, val remainingAttempts: Int?) : SigningState
}

data class SigningForm(
    val reason: String = "",
    val location: String = "",
    val pin: String = "",
)

@Composable
fun SigningSheet(
    state: SigningState,
    form: SigningForm,
    pinError: String?,
    onFormChange: (SigningForm) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sign_title), style = MaterialTheme.typography.titleLarge)

        when (state) {
            SigningState.Form -> {
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = { onFormChange(form.copy(reason = it)) },
                    label = { Text(stringResource(R.string.sign_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.location,
                    onValueChange = { onFormChange(form.copy(location = it)) },
                    label = { Text(stringResource(R.string.sign_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.pin,
                    // Force uppercase in the field itself. The card expects
                    // uppercase ASCII, and it cannot tell a mistyped PIN from a
                    // wrongly encoded one -- both cost an attempt.
                    onValueChange = { onFormChange(form.copy(pin = it.uppercase())) },
                    label = { Text(stringResource(R.string.sign_pin)) },
                    singleLine = true,
                    isError = pinError != null,
                    supportingText = {
                        Text(pinError ?: stringResource(R.string.sign_pin_uppercase))
                    },
                    // Masking is only visual. Without KeyboardType.Password the IME
                    // still treats this as ordinary text: it offers suggestions,
                    // autocorrects, and can add the PIN to personalised typing
                    // history. The password type sets
                    // TYPE_TEXT_VARIATION_PASSWORD, which suppresses all three.
                    //
                    // A password keyboard may ignore the capitalisation hint, so
                    // uppercasing in onValueChange above is what actually
                    // guarantees the card receives uppercase.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onStart,
                    enabled = pinError == null && form.pin.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sign_start)) }
            }

            SigningState.WaitingForCard -> {
                Text(stringResource(R.string.sign_hold_card))
                CircularProgressIndicator()
            }

            SigningState.Working -> {
                Text(stringResource(R.string.sign_in_progress))
                CircularProgressIndicator()
            }

            SigningState.Succeeded -> Text(stringResource(R.string.sign_success))

            is SigningState.Failed -> {
                Text(
                    stringResource(R.string.sign_failed, state.message),
                    color = MaterialTheme.colorScheme.error,
                )
                state.remainingAttempts?.let {
                    Text(
                        stringResource(R.string.sign_attempts_warning, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            // "Cancel" reads as undoing the signature once it has already
            // succeeded. Once there is nothing left to cancel, the button just
            // closes the sheet.
            Text(
                stringResource(
                    when (state) {
                        SigningState.Succeeded, is SigningState.Failed -> R.string.close
                        else -> R.string.cancel
                    },
                ),
            )
        }
    }
}
