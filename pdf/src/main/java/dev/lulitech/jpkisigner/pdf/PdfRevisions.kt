package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Revision boundaries of a signed PDF, derived from the file itself.
 *
 * A PDF signature covers `[0, a) + [b, b + c)` of its ByteRange, and `b + c` is
 * the end of the revision that signature created. So the length the file had
 * before signature *i* was applied is simply the length after signature *i - 1* --
 * no local bookkeeping required, and it works identically for signatures this app
 * made and signatures that arrived with an imported file.
 *
 * Removing a signature therefore means truncating to a revision boundary, which
 * yields the byte-identical earlier revision. Truncation can only ever *remove*
 * signatures; it cannot fabricate one.
 */
object PdfRevisions {

    /**
     * Length to truncate [file] to in order to remove the signature at [index]
     * (0-based, oldest first) and every signature after it.
     *
     * @return null when it cannot be done safely -- see [validate]. Callers must
     *   treat null as "not removable" rather than guessing.
     */
    fun truncationLengthFor(file: File, index: Int): Long? {
        val revisionEnds = revisionEnds(file) ?: return null
        if (index !in revisionEnds.indices) return null

        val candidate = if (index > 0) {
            revisionEnds[index - 1]
        } else {
            // Nothing earlier to read a length from: the target is the document
            // as it was before it was ever signed.
            originalLength(file, revisionEnds.first()) ?: return null
        }
        return candidate.takeIf { validate(file, it, expectedSignatures = index) }
    }

    /** End offset of each signed revision, oldest first. */
    private fun revisionEnds(file: File): List<Long>? = runCatching {
        PDDocument.load(file).use { document ->
            document.signatureDictionaries.map { signature ->
                val range = signature.byteRange
                require(range.size >= 4) { "malformed ByteRange" }
                range[2].toLong() + range[3]
            }
        }
    }.getOrNull()

    /**
     * Length of the document before any signature.
     *
     * Not recoverable from a ByteRange, so the `%%EOF` markers ahead of the first
     * signed revision are tried as candidates, nearest the start first. A bare
     * string search could match inside a stream, which is why every candidate is
     * validated by actually parsing the result.
     */
    private fun originalLength(file: File, firstRevisionEnd: Long): Long? {
        val bytes = file.readBytes()
        val marker = EOF_MARKER.toByteArray(Charsets.ISO_8859_1)
        var from = 0
        while (true) {
            val at = indexOf(bytes, marker, from)
            if (at < 0) return null
            val end = at + marker.size
            if (end >= firstRevisionEnd) return null
            // Consume the end-of-line that follows the marker, exactly. Trying a
            // range of candidates and taking the first that parses is wrong: a
            // length one byte short still parses as a valid zero-signature PDF,
            // but is not byte-identical to the original revision.
            val candidate = (end + eolLengthAt(bytes, end)).toLong()
            if (validate(file, candidate, expectedSignatures = 0)) return candidate
            from = end
        }
    }

    /**
     * Checks that truncating to [length] yields a readable PDF carrying exactly
     * [expectedSignatures] signatures.
     *
     * This is the guard against a signature whose ByteRange does not reach its
     * revision end, which some other tool might produce; truncating there would
     * leave a corrupt file.
     */
    private fun validate(file: File, length: Long, expectedSignatures: Int): Boolean {
        if (length <= 0 || length > file.length()) return false
        val prefix = ByteArray(length.toInt())
        java.io.RandomAccessFile(file, "r").use { raf -> raf.readFully(prefix) }
        return runCatching {
            PDDocument.load(prefix).use { document ->
                document.numberOfPages > 0 &&
                    document.signatureDictionaries.size == expectedSignatures
            }
        }.getOrDefault(false)
    }

    /** Length of the EOL sequence at [at]: CRLF, a bare CR or LF, or nothing. */
    private fun eolLengthAt(bytes: ByteArray, at: Int): Int = when {
        at + 1 < bytes.size && bytes[at] == CR && bytes[at + 1] == LF -> 2
        at < bytes.size && (bytes[at] == LF || bytes[at] == CR) -> 1
        else -> 0
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private const val EOF_MARKER = "%%EOF"
    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
}
