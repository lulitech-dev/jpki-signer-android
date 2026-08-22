package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.CardSignatureProvider
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.pdf.PdfSigner
import dev.lulitech.jpkisigner.pdf.SignParams
import java.io.File

/**
 * Applies one signature to a stored document, inside a single card session.
 *
 * Ordering matters for safety and for the revision stack:
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

    class Failure(message: String, val remainingAttempts: Int? = null) : Exception(message)

    /** Refuse rather than spend the final attempt on a mistyped PIN. */
    private val minimumAttemptsToProceed = 2

    fun sign(
        session: JpkiSession,
        documentId: String,
        pin: CharArray,
        params: SignParams,
        key: JpkiKey = JpkiKey.DIGITAL_SIGNATURE,
    ) {
        val document = store.get(documentId) ?: throw Failure("document no longer exists")

        key.validatePin(pin)?.let { throw Failure(it) }

        session.selectApplication()
        val remaining = session.remainingAttempts(key)
        if (remaining < minimumAttemptsToProceed) {
            throw Failure(
                "only $remaining attempt(s) remaining; refusing to risk a lockout",
                remaining,
            )
        }

        session.verifyPin(key, pin)

        val head: File = document.head
        val prefixLength = head.length()
        val staging = File(head.parentFile, "${head.name}.signing")

        try {
            PdfSigner(CardSignatureProvider(session, key)).sign(head, staging, params)
            check(staging.length() > prefixLength) { "signed output is not larger than the input" }

            // Swap in only now that a complete signed file exists.
            check(staging.renameTo(head) || (head.delete() && staging.renameTo(head))) {
                "could not replace the document with its signed version"
            }
        } finally {
            staging.delete()
        }
    }
}
