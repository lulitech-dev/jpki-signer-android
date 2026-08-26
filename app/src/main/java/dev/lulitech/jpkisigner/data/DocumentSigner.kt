package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.CardSignatureProvider
import dev.lulitech.jpkisigner.jpki.CardException
import dev.lulitech.jpkisigner.jpki.CardProblem
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.jpki.PinProblem
import dev.lulitech.jpkisigner.pdf.ChangesNotPermittedException
import dev.lulitech.jpkisigner.pdf.PdfRejection
import dev.lulitech.jpkisigner.pdf.PdfSigner
import dev.lulitech.jpkisigner.pdf.PdfValidator
import dev.lulitech.jpkisigner.pdf.SignParams
import java.io.File
import java.io.IOException

/**
 * Why a signing run did not finish, as data for the UI to translate.
 *
 * Every outcome the user can act on is named. [Unexpected] is the only case
 * carrying English text, and it carries it because there is nothing better to
 * say -- an unforeseen failure must still reach the screen rather than vanish.
 */
sealed interface SignFailure {

    /** Deleted from under the signing sheet. */
    data object DocumentMissing : SignFailure

    /**
     * The document itself cannot be signed as it now stands.
     *
     * Import refuses these, so reaching here means the file changed after it
     * arrived. Established before the card is touched, so it never costs an
     * attempt -- which is the whole point of rehearsing the document first.
     */
    data class DocumentUnusable(val reason: PdfRejection) : SignFailure

    /** Caught before any APDU was sent, so no attempt was spent. */
    data class MalformedPin(val problem: PinProblem) : SignFailure

    /**
     * Refused up front: too close to a lockout to risk a typo.
     *
     * Not final. Only a *successful* VERIFY resets the card's counter, so an app
     * that refuses at the floor and offers no way past it can never reset the
     * counter either -- the card would stay unusable here forever, and the user
     * would have to go and find other software. [canOverride] says whether there
     * is still an attempt to spend deliberately.
     */
    data class TooFewAttempts(val remaining: Int) : SignFailure {
        val canOverride: Boolean get() = remaining >= 1
    }

    data class Card(val problem: CardProblem) : SignFailure

    /** The document is certified against further change. */
    data object ChangesNotPermitted : SignFailure

    data class Unexpected(val message: String) : SignFailure

    /**
     * Attempts the card has left, where this failure actually establishes it.
     *
     * Null everywhere else on purpose: the lockout warning must never be shown
     * with a guessed number, least of all after a failure of unknown outcome.
     */
    val remainingAttempts: Int?
        get() = when (this) {
            is TooFewAttempts -> remaining
            is Card -> (problem as? CardProblem.WrongPin)?.remainingAttempts
            else -> null
        }
}

/**
 * Applies one signature to a stored document, inside a single card session.
 *
 * Ordering matters for safety and for the revision stack:
 *  - everything about the document that can fail is settled before the card is
 *    touched at all, so no document ever costs a PIN attempt to refuse;
 *  - the retry counter is read first, for free, and the run is abandoned rather
 *    than risking the last attempt;
 *  - exactly one VERIFY is sent, and never retried;
 *  - the signed PDF is built beside the head file and only swapped in on
 *    success, so a failure leaves the document byte-identical.
 *
 * Nothing about the signature is recorded locally: revision boundaries are read
 * back out of the PDF when needed.
 */
class DocumentSigner(private val store: DocumentStore) {

    /**
     * @param reason what to tell the user, as data. [Exception.message] is the
     *   developer-facing rendering and is not fit for the screen.
     */
    class Failure(val reason: SignFailure) : Exception(reason.toString())

    /** Refuse rather than spend the final attempt on a mistyped PIN. */
    private val minimumAttemptsToProceed = 2

    /**
     * @param acceptLastAttempt lower the floor to a single attempt, because the
     *   user has been shown what is at stake and asked for it anyway. The default
     *   floor is the safe one; this is the deliberate way past it, and the card
     *   still has the final say -- a counter already at zero is refused either
     *   way.
     */
    fun sign(
        session: JpkiSession,
        documentId: String,
        pin: CharArray,
        params: SignParams,
        key: JpkiKey = JpkiKey.DIGITAL_SIGNATURE,
        acceptLastAttempt: Boolean = false,
    ) {
        val document = store.get(documentId) ?: throw Failure(SignFailure.DocumentMissing)

        key.validatePin(pin)?.let { throw Failure(SignFailure.MalformedPin(it)) }
        rehearse(document)

        try {
            session.selectApplication()
            val remaining = session.remainingAttempts(key)
            val floor = if (acceptLastAttempt) 1 else minimumAttemptsToProceed
            if (remaining < floor) {
                throw Failure(SignFailure.TooFewAttempts(remaining))
            }

            session.verifyPin(key, pin)
            writeSignature(document, session, key, params)
        } catch (e: CardException) {
            // The card layer has no locale; its problem is translated upstream.
            throw Failure(SignFailure.Card(e.problem))
        } catch (e: ChangesNotPermittedException) {
            // Import already refuses these, so reaching here means the document
            // was certified after it was imported.
            throw Failure(SignFailure.ChangesNotPermitted)
        }
    }

    /**
     * Everything about the document that can fail, settled before the first APDU.
     *
     * The write used to happen only after the VERIFY, so a document that could
     * not be parsed, that was certified after it was imported, or that simply
     * could not have an output file created beside it cost the user an attempt to
     * discover -- on a key that takes a trip to a municipal window to unblock.
     * DESIGN.md §6 asks for everything checkable offline to be checked offline,
     * and every one of these is.
     *
     * The cost is parsing the document twice on the way to a signature. That is
     * work already being done with the card in the field, and it happens before
     * any command is sent, so a card lifted during it fails with nothing spent.
     */
    private fun rehearse(document: StoredDocument) {
        // Cheap, so it goes first -- and it doubles as the sweep for a staging
        // file left by a run that was killed. The library selects on the `.pdf`
        // extension, so nothing else would ever see one.
        val staging = stagingFor(document)
        try {
            staging.outputStream().close()
        } catch (e: IOException) {
            throw Failure(SignFailure.Unexpected(describe(e)))
        } finally {
            staging.delete()
        }

        when (val rejection = PdfValidator.validate(document.head)) {
            null -> Unit
            // One condition, one message: PdfSigner throws this too, as the last
            // line of defence once the document is open for signing.
            PdfRejection.CERTIFIED_NO_CHANGES -> throw Failure(SignFailure.ChangesNotPermitted)
            else -> throw Failure(SignFailure.DocumentUnusable(rejection))
        }
    }

    /**
     * Where the signed document is assembled before it replaces the head file.
     *
     * A fixed name, not one derived from the document's own. A stored name is
     * allowed to reach NAME_MAX exactly, and a suffix on top of that cannot be
     * created at all -- which, discovered after the VERIFY, cost an attempt on a
     * document that could never have been signed. The directory holds one
     * document, so a fixed name is unambiguous, and it is not a `.pdf`, so the
     * library cannot see it.
     */
    private fun stagingFor(document: StoredDocument): File =
        File(document.head.parentFile, STAGING)

    private fun writeSignature(
        document: StoredDocument,
        session: JpkiSession,
        key: JpkiKey,
        params: SignParams,
    ) {
        val head = document.head
        val prefixLength = head.length()
        val staging = stagingFor(document)

        try {
            PdfSigner(CardSignatureProvider(session, key)).sign(head, staging, params)
            check(staging.length() > prefixLength) { "signed output is not larger than the input" }

            // One rename, and no fallback. rename(2) replaces the destination
            // atomically within a directory, so either the signed file becomes
            // the document or the document is untouched. Deleting the head first
            // and renaming afterwards -- the previous shape of this -- could
            // delete the original and then fail the rename, losing it outright.
            check(staging.renameTo(head)) {
                "could not replace the document with its signed version"
            }
        } finally {
            staging.delete()
        }
    }

    private companion object {
        const val STAGING = "signing.part"
    }
}

/**
 * An unforeseen failure as the one line we can put on screen, with filesystem
 * paths taken out.
 *
 * `FileNotFoundException` and the rest of `java.io` put the whole path in their
 * message, and that path names the user's own document -- so it would land on
 * screen, and in any screenshot attached to a bug report, in an app that
 * otherwise never says where anything is stored. Why it failed is the part worth
 * showing; where is not ours to disclose.
 *
 * The class name is the fallback rather than the first choice: `message` is
 * usually the more useful half, and an `OutOfMemoryError` has none at all.
 */
internal fun describe(e: Throwable): String {
    val message = e.message?.let { ABSOLUTE_PATH.replace(it, "") }?.trim()
    return if (message.isNullOrBlank()) e::class.java.simpleName else message
}

/** An absolute path, as `java.io` messages embed one ahead of the reason. */
private val ABSOLUTE_PATH = Regex("""/\S+""")
