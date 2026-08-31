package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import java.io.File

/**
 * End of the revision [signature] created: `ByteRange[2] + ByteRange[3]`.
 *
 * Strictly increasing in time, so it doubles as the ordering key for "oldest
 * first". [Long.MAX_VALUE] for a malformed ByteRange, which sorts that signature
 * last and makes any length derived from it fail validation.
 *
 * Shared with [SignatureInspector] deliberately. PDFBox returns signatures in
 * AcroForm field order, which is normally chronological but is not guaranteed to
 * be, so both sides sort -- and they must sort by the *same* key, or a row would
 * be offered the truncation length belonging to its neighbour.
 */
internal fun revisionEndOf(signature: PDSignature): Long {
    val range = signature.byteRange
    return if (range.size >= 4) range[2].toLong() + range[3] else Long.MAX_VALUE
}

/**
 * Revision boundaries of a signed PDF, derived from the file itself.
 *
 * A PDF signature covers `[0, a) + [b, b + c)` of its ByteRange, and `b + c` is
 * the end of the revision that signature created -- so a signature's own revision
 * end is exact, and comes from the file rather than from local bookkeeping, which
 * works identically for signatures this app made and signatures that arrived with
 * an imported file.
 *
 * What the *previous* signature's revision end gives is only a lower bound. A
 * document can gain unsigned incremental updates between two signatures -- a form
 * filled in, a page annotated -- and the later signature signed them, so the
 * length before it is not the length after the one before it. The boundary in
 * between is recovered from the file's own `%%EOF` markers; see [boundaryBefore].
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
        val bytes = file.readBytes()
        val revisionEnds = revisionEnds(bytes) ?: return null
        return truncationLength(bytes, revisionEnds, index)
    }

    /**
     * [truncationLengthFor] for every signature at once, oldest first.
     *
     * Reads the file once for the whole list, and parses it once to collect the
     * revision ends. Asking per signature re-read a document that can be tens of
     * megabytes for each one.
     *
     * The parsing is *not* down to once: [validate] still loads a prefix per
     * candidate, so a document with n signatures costs n prefix copies and n
     * parses, plus one per `%%EOF` candidate considered for each of them. That is
     * the price of proving each boundary rather than trusting it, and it is paid
     * once when a document is opened.
     *
     * @return an entry per signature, null where that signature is not removable;
     *   empty when the file cannot be read at all.
     */
    fun truncationLengths(file: File): List<Long?> = truncationLengths(file.readBytes())

    /**
     * [truncationLengths] over bytes already in hand.
     *
     * Exists so a caller that also needs [SignatureInspector.inspect] can read the
     * file once and pass the same array to both, rather than each of them pulling
     * a separate full copy of a user's document into memory.
     */
    fun truncationLengths(bytes: ByteArray): List<Long?> {
        val revisionEnds = revisionEnds(bytes) ?: return emptyList()
        return revisionEnds.indices.map { truncationLength(bytes, revisionEnds, it) }
    }

    private fun truncationLength(
        bytes: ByteArray,
        revisionEnds: List<Long>,
        index: Int,
    ): Long? {
        if (index !in revisionEnds.indices) return null

        // Everything below the signature being removed is kept, so the previous
        // signature's revision end is the floor -- 0 for the first signature,
        // which has nothing earlier to read a length from. A malformed ByteRange
        // puts it outside the file, and nothing can be recovered from that.
        val floor = if (index > 0) revisionEnds[index - 1] else 0L
        if (floor < 0 || floor > bytes.size) return null

        return boundaryBefore(bytes, revisionEnds[index], floor, expectedSignatures = index)
    }

    /** End offset of each signed revision, oldest first. */
    private fun revisionEnds(bytes: ByteArray): List<Long>? = runCatching {
        PDDocument.load(bytes).use { document ->
            document.signatureDictionaries.map { revisionEndOf(it) }.sorted()
        }
    }.getOrNull()

    /**
     * Length the document had when the signature whose revision ends at
     * [revisionEnd] was applied, i.e. the end of the newest revision below it.
     *
     * [floor] is the previous signature's revision end, or 0 for the first
     * signature. It is where the search starts, and it is the answer of last
     * resort: with no revision in between, the length before this signature is
     * simply the length after the one before it.
     *
     * A boundary in between is not recoverable from any ByteRange, so the `%%EOF`
     * markers are the candidates, and they are tried **nearest [revisionEnd]
     * first**, i.e. largest offset down. A file that arrived with unsigned
     * incremental updates on it -- a form that was filled in, a page a viewer
     * annotated -- has one marker per revision, and *every* one of them parses as
     * a valid PDF carrying [expectedSignatures] signatures. So validation cannot
     * tell them apart, and taking the first match truncated back past the revision
     * that was actually signed: removing the user's own unsigned work along with
     * the signature, silently. The revision that was signed is the last one below
     * [revisionEnd].
     *
     * This holds at every index, not only the first. A signature added to a
     * document that had been annotated since the previous one *signed those
     * annotations*, so [floor] on its own names a revision predating what this
     * signature covered -- and truncating there passes every check, because the
     * result is a readable PDF with exactly the right number of signatures left.
     *
     * A bare string search can also match inside a stream -- an uncompressed one,
     * or an embedded PDF attachment, which carries a `%%EOF` of its own. Parsing
     * the result is not enough to rule that out on its own: PDFBox reconstructs
     * the cross-reference table of a file it cannot read normally, so a prefix cut
     * in the middle of the body can still come back as a readable document with
     * pages -- and it would be tried *before* the real boundary, being the larger
     * offset. So a candidate must also look like the end of a revision rather than
     * like five bytes of payload; see [closesARevision].
     *
     * @return null when neither a candidate nor [floor] survives [validate], which
     *   callers report as "not removable" -- the direction to fail in. Nothing
     *   here can turn a bad boundary into a good one, only decline to guess.
     */
    private fun boundaryBefore(
        bytes: ByteArray,
        revisionEnd: Long,
        floor: Long,
        expectedSignatures: Int,
    ): Long? {
        val marker = EOF_MARKER.toByteArray(Charsets.ISO_8859_1)
        val candidates = mutableListOf<Long>()
        // From the floor, so every marker found lies above it by construction.
        var from = floor.toInt()
        while (true) {
            val at = indexOf(bytes, marker, from)
            if (at < 0) break
            val end = at + marker.size
            if (end >= revisionEnd) break
            from = end
            if (!closesARevision(bytes, at)) continue
            // Consume the end-of-line that follows the marker, exactly. Trying a
            // range of lengths around a marker and taking the first that parses
            // is wrong for the same reason as above: a length one byte short
            // still parses as a valid PDF with the right signature count, but is
            // not byte-identical to any revision.
            candidates += (end + eolLengthAt(bytes, end)).toLong()
        }
        return candidates
            .asReversed()
            .firstOrNull { validate(bytes, it, expectedSignatures) }
            // The first signature has no floor to fall back on: 0 is not a
            // document, so it is reported as not removable instead.
            ?: floor.takeIf { it > 0 && validate(bytes, it, expectedSignatures) }
    }

    /**
     * Whether the `%%EOF` at [markerAt] is the one that closes a revision *of this
     * file*.
     *
     * Every revision of a conforming PDF ends with `startxref`, the byte offset of
     * its cross-reference section, then the marker. Two things are checked, and
     * the second is the one that does the work:
     *
     *  1. the trailer is there at all, which rejects the marker appearing as plain
     *     payload -- in an uncompressed stream, a metadata string, a comment;
     *  2. the offset it names points at a cross-reference section **in this file's
     *     coordinates**. That is what rejects an embedded PDF attachment, whose
     *     own complete trailer is carried verbatim inside the outer document and
     *     satisfies (1) perfectly. Its offset is relative to the inner file, so
     *     read against the outer one it lands on unrelated bytes.
     *
     * Parsing alone cannot make this call: PDFBox reconstructs the cross-reference
     * table of a file it cannot read normally, so a prefix cut in the middle of
     * the body can still come back as a readable document with pages.
     *
     * A marker this rejects is simply not offered, and the first signature is then
     * reported as not removable -- which is the direction to fail in. Nothing here
     * can turn a bad boundary into a good one, only decline to guess.
     */
    private fun closesARevision(bytes: ByteArray, markerAt: Int): Boolean {
        val keyword = STARTXREF.toByteArray(Charsets.ISO_8859_1)
        val at = lastIndexOf(bytes, keyword, maxOf(0, markerAt - STARTXREF_WINDOW), markerAt)
        if (at < 0) return false

        // The offset is the last token before the marker.
        var end = markerAt
        while (end > at && isPdfWhitespace(bytes[end - 1])) end--
        var start = end
        while (start > at && isDigit(bytes[start - 1])) start--
        if (start == end || end - start > MAX_OFFSET_DIGITS) return false
        // Nothing but whitespace may separate the keyword from it.
        for (i in at + keyword.size until start) {
            if (!isPdfWhitespace(bytes[i])) return false
        }

        var offset = 0L
        for (i in start until end) offset = offset * 10 + ((bytes[i].toInt() and 0xFF) - ZERO)
        // Bounded by the keyword's own position, not by the file's length. A
        // revision writes its cross-reference section *before* the `startxref`
        // naming it, so an offset at or past the keyword cannot be this
        // revision's -- and that is exactly the shape the check exists to
        // reject. An embedded PDF attachment's offset is relative to the inner
        // file, so measured against the outer one it can land anywhere later,
        // the attachment's own body included, and pass. Candidates are tried
        // largest offset first, so such a false boundary would be preferred over
        // the real one below it, leaving [validate] as the only guard left.
        return startsCrossReferenceSection(bytes, offset, below = at)
    }

    /**
     * Whether [offset] addresses the start of a cross-reference section: the
     * `xref` keyword of a table, or the `N M obj` header of the object holding a
     * cross-reference stream.
     *
     * @param below one past the last byte [offset] may address -- the position of
     *   the `startxref` that named it, since a revision's cross-reference section
     *   always precedes the keyword pointing at it.
     */
    private fun startsCrossReferenceSection(bytes: ByteArray, offset: Long, below: Int): Boolean {
        if (offset <= 0 || offset >= below) return false
        var i = offset.toInt()
        if (matches(bytes, i, XREF)) return true

        // "N M obj".
        val objectNumber = digitsAt(bytes, i)
        if (objectNumber == 0) return false
        i += objectNumber
        val firstGap = whitespaceAt(bytes, i)
        if (firstGap == 0) return false
        i += firstGap
        val generation = digitsAt(bytes, i)
        if (generation == 0) return false
        i += generation
        val secondGap = whitespaceAt(bytes, i)
        if (secondGap == 0) return false
        return matches(bytes, i + secondGap, OBJ)
    }

    private fun matches(bytes: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at < 0 || at + needle.size > bytes.size) return false
        for (j in needle.indices) {
            if (bytes[at + j] != needle[j]) return false
        }
        return true
    }

    private fun digitsAt(bytes: ByteArray, at: Int): Int {
        var i = at
        while (i < bytes.size && isDigit(bytes[i])) i++
        return i - at
    }

    private fun whitespaceAt(bytes: ByteArray, at: Int): Int {
        var i = at
        while (i < bytes.size && isPdfWhitespace(bytes[i])) i++
        return i - at
    }

    private fun isDigit(byte: Byte): Boolean = (byte.toInt() and 0xFF) in ZERO..NINE

    /** NUL, tab, line feed, form feed, carriage return, space. */
    private fun isPdfWhitespace(byte: Byte): Boolean =
        when (byte.toInt() and 0xFF) {
            0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20 -> true
            else -> false
        }

    /**
     * Checks that truncating to [length] yields a readable PDF carrying exactly
     * [expectedSignatures] signatures.
     *
     * This is the guard against a signature whose ByteRange does not reach its
     * revision end, which some other tool might produce; truncating there would
     * leave a corrupt file. The bounds check also keeps [length] inside `Int`
     * range, which is what makes the prefix copy below safe.
     */
    private fun validate(bytes: ByteArray, length: Long, expectedSignatures: Int): Boolean {
        if (length <= 0 || length > bytes.size) return false
        val prefix = bytes.copyOf(length.toInt())
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

    /** Last occurrence of [needle] starting at or after [from] and ending by [until]. */
    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        outer@ for (i in (until - needle.size) downTo from) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private const val EOF_MARKER = "%%EOF"
    private const val STARTXREF = "startxref"
    private val XREF = "xref".toByteArray(Charsets.ISO_8859_1)
    private val OBJ = "obj".toByteArray(Charsets.ISO_8859_1)

    /** More than any real byte offset needs, and short of overflowing a Long. */
    private const val MAX_OFFSET_DIGITS = 18

    /**
     * How far back of a `%%EOF` the `startxref` keyword may sit: the keyword, an
     * end-of-line, the offset, and another end-of-line. Generous enough for a
     * writer that pads the line, short enough that an unrelated `startxref`
     * elsewhere in the file cannot be mistaken for this revision's.
     */
    private const val STARTXREF_WINDOW = 64

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
    private const val ZERO = 0x30
    private const val NINE = 0x39
}
