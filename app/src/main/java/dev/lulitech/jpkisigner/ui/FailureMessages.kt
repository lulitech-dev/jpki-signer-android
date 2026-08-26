package dev.lulitech.jpkisigner.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.lulitech.jpkisigner.R
import dev.lulitech.jpkisigner.data.SignFailure
import dev.lulitech.jpkisigner.jpki.CardProblem
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.PinProblem
import dev.lulitech.jpkisigner.pdf.PdfRejection

/*
 * Turns the card and signing layers' typed problems into sentences.
 *
 * `:jpki` has no resources and no locale, so it reports what went wrong as data.
 * This is the single place that data becomes text, which is what keeps every card
 * and PIN error translated instead of an English literal from a library module
 * appearing under the PIN field of a Japanese-language app.
 */

@Composable
fun messageFor(problem: PinProblem): String = when (problem) {
    is PinProblem.WrongLength ->
        if (problem.minLength == problem.maxLength) {
            stringResource(R.string.pin_error_length_exact, problem.minLength)
        } else {
            stringResource(
                R.string.pin_error_length_range,
                problem.minLength,
                problem.maxLength,
            )
        }
    PinProblem.NotDigits -> stringResource(R.string.pin_error_digits)
    PinProblem.NotUppercaseAlphanumeric -> stringResource(R.string.pin_error_uppercase)
}

@Composable
fun messageFor(problem: CardProblem): String = when (problem) {
    CardProblem.LostContact -> stringResource(R.string.card_error_lost_contact)
    CardProblem.VerifyOutcomeUnknown -> stringResource(R.string.card_error_verify_unknown)
    CardProblem.PinBlocked -> stringResource(R.string.card_error_pin_blocked)
    CardProblem.NotJpkiCard -> stringResource(R.string.card_error_not_jpki_card)
    is CardProblem.WrongPin ->
        // Only ever state a count the card actually gave us.
        problem.remainingAttempts?.let {
            pluralStringResource(R.plurals.card_error_wrong_pin, it, it)
        } ?: stringResource(R.string.card_error_wrong_pin_unknown)
    CardProblem.MalformedResponse -> stringResource(R.string.card_error_malformed_response)
    is CardProblem.CommandFailed ->
        stringResource(R.string.card_error_command_failed, problem.what)
    is CardProblem.Unreadable -> stringResource(R.string.card_error_unreadable, problem.what)
}

/**
 * Why a file cannot be signed.
 *
 * Shared with the import dialog deliberately: the reason is the same sentence
 * whether the file was refused as it arrived or refused later, and two wordings
 * for one condition is how they drift apart.
 */
@Composable
fun messageFor(rejection: PdfRejection): String = stringResource(
    when (rejection) {
        PdfRejection.ENCRYPTED -> R.string.import_rejected_encrypted
        PdfRejection.NO_PAGES -> R.string.import_rejected_no_pages
        PdfRejection.UNREADABLE -> R.string.import_rejected_unreadable
        PdfRejection.CERTIFIED_NO_CHANGES -> R.string.import_rejected_certified
    },
)

@Composable
fun messageFor(failure: SignFailure): String = when (failure) {
    SignFailure.DocumentMissing -> stringResource(R.string.sign_error_document_missing)
    is SignFailure.DocumentUnusable -> messageFor(failure.reason)
    is SignFailure.MalformedPin -> messageFor(failure.problem)
    is SignFailure.TooFewAttempts ->
        pluralStringResource(
            R.plurals.sign_error_too_few_attempts,
            failure.remaining,
            failure.remaining,
        )
    is SignFailure.Card -> messageFor(failure.problem)
    SignFailure.ChangesNotPermitted -> stringResource(R.string.sign_changes_not_permitted)
    // The one case with nothing translated to say. Showing the raw message beats
    // showing nothing when something unforeseen goes wrong.
    is SignFailure.Unexpected -> failure.message
}

/**
 * The PIN field's error text, or null while the field is still empty.
 *
 * Wipes its working copy. The array exists only for the format check, and leaving
 * one behind per keystroke would undo the point of the card layer taking a
 * `CharArray` in the first place.
 */
@Composable
fun pinErrorFor(pin: String, key: JpkiKey = JpkiKey.DIGITAL_SIGNATURE): String? {
    if (pin.isEmpty()) return null
    val chars = pin.toCharArray()
    val problem = try {
        key.validatePin(chars)
    } finally {
        chars.fill(' ')
    }
    return problem?.let { messageFor(it) }
}
