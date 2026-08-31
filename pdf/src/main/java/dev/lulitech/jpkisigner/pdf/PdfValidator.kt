package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/**
 * Why a file cannot be signed by this app.
 *
 * Checked at import rather than at signing time: rejecting a file the moment it
 * arrives is far clearer than accepting it and failing later, possibly after the
 * user has already entered a PIN.
 */
enum class PdfRejection {
    /** Not a PDF at all, or damaged beyond parsing. */
    UNREADABLE,

    /**
     * Encrypted. Signing would have to rewrite or re-encrypt the content, which
     * breaks the append-only property the whole revision model depends on.
     */
    ENCRYPTED,

    /** Parses, but contains no pages. */
    NO_PAGES,

    /**
     * Carries a certification signature that permits no further change, so any
     * signature added to it would break that certification.
     *
     * [PdfSigner] refuses these too, as the last line of defence, but by then a
     * PIN has been verified and an attempt spent. Catching it here is the whole
     * point of validating at import.
     */
    CERTIFIED_NO_CHANGES,
}

object PdfValidator {

    /** @return the reason to reject [file], or null when it is signable. */
    fun validate(file: File): PdfRejection? {
        if (!file.isFile || file.length() == 0L) return PdfRejection.UNREADABLE
        return try {
            // Inside the try: reading the tail is I/O and can fail on its own, and
            // an IOException escaping a function whose contract is "the reason to
            // reject" would crash the import instead of rejecting the file.
            //
            // PDFBox will happily reconstruct the cross-reference table of a
            // truncated file and report it as healthy, so a half-copied download
            // would pass. Require the end-of-file marker as well: signing input
            // that had to be repaired risks an output whose original bytes were
            // rewritten, which is exactly what the revision stack must never see.
            if (!endsWithEofMarker(file)) return PdfRejection.UNREADABLE
            PDDocument.load(file).use { document ->
                when {
                    document.isEncrypted -> PdfRejection.ENCRYPTED
                    document.numberOfPages == 0 -> PdfRejection.NO_PAGES
                    DocMdp.existingPermission(document) == DocMdp.NO_CHANGES_PERMITTED ->
                        PdfRejection.CERTIFIED_NO_CHANGES
                    else -> null
                }
            }
        } catch (e: InvalidPasswordException) {
            // Password-protected. Caught by type rather than by matching
            // "InvalidPassword" against the class's simple name, which is a
            // string comparison against something no compiler checks -- it
            // survives neither a rename upstream nor an obfuscated build, and
            // fails by quietly reclassifying every encrypted PDF as damaged.
            PdfRejection.ENCRYPTED
        } catch (e: Exception) {
            // Damaged, or not a PDF. Not signable either way.
            PdfRejection.UNREADABLE
        }
    }

    /**
     * Looks for `%%EOF` near the end of the file. A tail window rather than an
     * exact suffix, because trailing whitespace and the odd stray byte after the
     * marker are common and harmless.
     */
    private fun endsWithEofMarker(file: File): Boolean {
        val window = minOf(file.length(), EOF_WINDOW_BYTES).toInt()
        val tail = ByteArray(window)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - window)
            raf.readFully(tail)
        }
        return String(tail, Charsets.ISO_8859_1).contains("%%EOF")
    }

    private const val EOF_WINDOW_BYTES = 2048L
}
