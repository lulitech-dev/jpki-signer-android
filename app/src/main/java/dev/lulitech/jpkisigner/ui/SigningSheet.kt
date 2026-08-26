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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.lulitech.jpkisigner.R
import dev.lulitech.jpkisigner.data.SignFailure
import dev.lulitech.jpkisigner.jpki.JpkiKey

/** Where the signing flow currently is. */
sealed interface SigningState {
    /** Collecting reason, location and PIN. */
    data object Form : SigningState

    /** Reader mode is on; waiting for the card. */
    data object WaitingForCard : SigningState

    /** The card is in the field and the run is under way. */
    data object Working : SigningState

    data object Succeeded : SigningState

    /**
     * @param failure carried as data, so the message is resolved against string
     *   resources here rather than being an English literal from the throw site.
     */
    data class Failed(val failure: SignFailure) : SigningState
}

data class SigningForm(
    val reason: String = "",
    val location: String = "",
    /**
     * A `String` because that is what [OutlinedTextField] takes, and it is the
     * practical ceiling here: unlike the `CharArray` the card layer wants, it
     * cannot be wiped, and Compose holds it until the form is replaced.
     *
     * The mitigations sit around it instead. The field is a password field, so the
     * IME keeps no history of it; `MainActivity` drops it from the form the moment
     * it has been snapshotted; and every `CharArray` derived from it is wiped
     * after use.
     */
    val pin: String = "",
)

/**
 * @param nfcProblem why this device cannot read a card right now, or null when it
 *   can. Reader mode is silent about being switched off, so without this the flow
 *   would go on to ask for a card that can never arrive.
 */
@Composable
fun SigningSheet(
    state: SigningState,
    form: SigningForm,
    pinError: String?,
    nfcProblem: String?,
    acceptLastAttempt: Boolean,
    onFormChange: (SigningForm) -> Unit,
    onStart: () -> Unit,
    onUseLastAttempt: () -> Unit,
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
                if (nfcProblem != null) {
                    Text(
                        nfcProblem,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Restated on the form the user came back to, not only on the
                // failure they came from: this is the run that actually spends the
                // attempt, and the PIN about to be typed is the one at stake.
                if (acceptLastAttempt) {
                    Text(
                        stringResource(R.string.sign_last_attempt_notice),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onStart,
                    enabled = nfcProblem == null && pinError == null && form.pin.isNotEmpty(),
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
                    stringResource(R.string.sign_failed, messageFor(state.failure)),
                    color = MaterialTheme.colorScheme.error,
                )
                state.failure.remainingAttempts?.let {
                    Text(
                        // The lockout threshold comes from the key, not from the
                        // sentence: a number written into a translation goes stale
                        // against the card without anything failing.
                        pluralStringResource(
                            R.plurals.sign_attempts_warning,
                            it,
                            it,
                            JpkiKey.DIGITAL_SIGNATURE.maxAttempts,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The way past our own floor. Without it, refusing at the floor is
                // permanent: only a successful VERIFY resets the card's counter,
                // and this app would never send one again -- so the card would
                // stay unusable here for good. Deliberately not the primary
                // button, and it leads back to the form rather than straight to a
                // card, so the PIN has to be retyped before the attempt is spent.
                val failure = state.failure
                if (failure is SignFailure.TooFewAttempts && failure.canOverride) {
                    TextButton(
                        onClick = onUseLastAttempt,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.sign_use_last_attempt),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // No button at all while the card operation is in flight. It cannot be
        // stopped: the VERIFY has been sent and the signature is being written, so
        // a Cancel that leaves the document signed anyway is worse than no button.
        // MainActivity holds the sheet open in this state for the same reason.
        if (state != SigningState.Working) {
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
}
