package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
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
}

object PdfValidator {

    /** @return the reason to reject [file], or null when it is signable. */
    fun validate(file: File): PdfRejection? {
        if (!file.isFile || file.length() == 0L) return PdfRejection.UNREADABLE
        // PDFBox will happily reconstruct the cross-reference table of a
        // truncated file and report it as healthy, so a half-copied download
        // would pass. Require the end-of-file marker as well: signing input that
        // had to be repaired risks an output whose original bytes were rewritten,
        // which is exactly what the revision stack must never see.
        if (!endsWithEofMarker(file)) return PdfRejection.UNREADABLE
        return try {
            PDDocument.load(file).use { document ->
                when {
                    document.isEncrypted -> PdfRejection.ENCRYPTED
                    document.numberOfPages == 0 -> PdfRejection.NO_PAGES
                    else -> null
                }
            }
        } catch (e: Exception) {
            // PDFBox throws InvalidPasswordException for password-protected
            // files and IOException for damaged ones; neither is signable.
            if (e::class.java.simpleName.contains("InvalidPassword")) {
                PdfRejection.ENCRYPTED
            } else {
                PdfRejection.UNREADABLE
            }
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
