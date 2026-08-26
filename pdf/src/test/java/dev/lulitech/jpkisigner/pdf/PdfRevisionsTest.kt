package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfRevisionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blank(name: String): File {
        val file = temp.newFile(name)
        PDDocument().use { it.addPage(PDPage()); it.save(file) }
        return file
    }

    /** Three signatures, then every boundary must be recoverable from the file alone. */
    @Test
    fun `derives every revision boundary without local records`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val original = blank("a.pdf")
        val originalLength = original.length()

        val once = temp.newFile("1.pdf"); signer.sign(original, once)
        val twice = temp.newFile("2.pdf"); signer.sign(once, twice)
        val thrice = temp.newFile("3.pdf"); signer.sign(twice, thrice)

        assertEquals(originalLength, PdfRevisions.truncationLengthFor(thrice, 0))
        assertEquals(once.length(), PdfRevisions.truncationLengthFor(thrice, 1))
        assertEquals(twice.length(), PdfRevisions.truncationLengthFor(thrice, 2))
        assertNull("no fourth signature", PdfRevisions.truncationLengthFor(thrice, 3))
    }

    /**
     * The case that motivated this: a file imported with signatures already on it,
     * which the app never recorded, must still be fully reducible.
     */
    @Test
    fun `an imported signed pdf can be reduced to the unsigned original`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val original = blank("a.pdf")
        val originalBytes = original.readBytes()

        var current = original
        repeat(5) { i ->
            val next = temp.newFile("s$i.pdf")
            signer.sign(current, next)
            current = next
        }
        assertEquals(5, SignatureInspector.inspect(current).size)

        // Nothing local was recorded; everything comes from the file.
        val target = PdfRevisions.truncationLengthFor(current, 0)!!
        val reduced = temp.newFile("reduced.pdf")
        reduced.writeBytes(current.readBytes().copyOfRange(0, target.toInt()))

        assertArrayEquals("must be the unsigned original, byte for byte", originalBytes, reduced.readBytes())
        assertTrue(SignatureInspector.inspect(reduced).isEmpty())
    }

    @Test
    fun `truncating to a middle boundary keeps the earlier signatures valid`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val original = blank("a.pdf")
        val once = temp.newFile("1.pdf"); signer.sign(original, once)
        val onceBytes = once.readBytes()
        val twice = temp.newFile("2.pdf"); signer.sign(once, twice)
        val thrice = temp.newFile("3.pdf"); signer.sign(twice, thrice)

        val target = PdfRevisions.truncationLengthFor(thrice, 1)!!
        val reduced = temp.newFile("reduced.pdf")
        reduced.writeBytes(thrice.readBytes().copyOfRange(0, target.toInt()))

        assertArrayEquals(onceBytes, reduced.readBytes())
        val remaining = SignatureInspector.inspect(reduced)
        assertEquals(1, remaining.size)
        assertEquals(
            "the surviving signature must still verify",
            SignatureIntegrity.OK,
            remaining.single().integrity,
        )
        assertTrue(remaining.single().coversWholeDocument)
    }

    /**
     * The case this got wrong. A PDF that arrived with unsigned incremental
     * updates on it -- a filled form, a page annotated by a viewer -- has an
     * `%%EOF` per revision, and every one of them parses as a valid
     * zero-signature PDF. Removing the first signature must land on the revision
     * that was signed, not on the earliest one: the difference is the user's own
     * unsigned work, deleted silently along with the signature.
     */
    @Test
    fun `removing the first signature keeps earlier unsigned revisions`() {
        val revision1 = blank("r1.pdf")
        val revision1Bytes = revision1.readBytes()

        // A second, unsigned revision as an incremental update.
        //
        // Written to a fresh file, not appended to a copy of revision 1:
        // `saveIncremental` emits the whole document *and* the increment, so
        // appending it produced revision 1 followed by an entire second PDF whose
        // offsets were all relative to its own start. Nothing writes a file like
        // that, and it is not a two-revision document -- it is one document with
        // another one stuck to the front.
        val revision2 = temp.newFile("r2.pdf")
        PDDocument.load(revision1).use { document ->
            document.addPage(PDPage())
            java.io.FileOutputStream(revision2).use { document.saveIncremental(it) }
        }
        val revision2Bytes = revision2.readBytes()
        assertArrayEquals(
            "the fixture must be a real incremental update, appending only",
            revision1Bytes,
            revision2Bytes.copyOfRange(0, revision1Bytes.size),
        )
        assertTrue(
            "the fixture must actually have several EOF markers",
            revision2Bytes.size > revision1Bytes.size,
        )

        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(revision2, signed)

        val target = PdfRevisions.truncationLengthFor(signed, 0)
        assertEquals(
            "must truncate to the revision that was signed, not the earliest one",
            revision2.length(),
            target,
        )

        val reduced = temp.newFile("reduced.pdf")
        reduced.writeBytes(signed.readBytes().copyOfRange(0, target!!.toInt()))
        assertArrayEquals(
            "the unsigned revision must survive verbatim",
            revision2Bytes,
            reduced.readBytes(),
        )
        assertTrue(SignatureInspector.inspect(reduced).isEmpty())
    }

    /**
     * The same trap one signature further along.
     *
     * A signature's own revision end is exact, but the *previous* signature's is
     * only a lower bound: a document can gain unsigned incremental updates between
     * two signatures, and the later one signed them. Truncating to the earlier
     * signature's revision end therefore removes the user's own unsigned work
     * along with the signature -- and it passes every check, because the result is
     * a readable PDF carrying exactly the number of signatures expected of it.
     */
    @Test
    fun `removing a later signature keeps an unsigned revision made before it`() {
        val signer = PdfSigner(SoftwareSignatureProvider())

        val base = blank("base.pdf")
        val signedOnce = temp.newFile("s1.pdf")
        signer.sign(base, signedOnce)
        val signedOnceBytes = signedOnce.readBytes()

        // An unsigned incremental update on top of the first signature: a form
        // filled in, a page annotated, between the two signatures.
        val annotated = temp.newFile("annotated.pdf")
        PDDocument.load(signedOnce).use { document ->
            document.addPage(PDPage())
            java.io.FileOutputStream(annotated).use { document.saveIncremental(it) }
        }
        val annotatedBytes = annotated.readBytes()
        assertArrayEquals(
            "the fixture must be a real incremental update, appending only",
            signedOnceBytes,
            annotatedBytes.copyOfRange(0, signedOnceBytes.size),
        )
        assertTrue(
            "the fixture must actually add a revision",
            annotatedBytes.size > signedOnceBytes.size,
        )

        val signedTwice = temp.newFile("s2.pdf")
        signer.sign(annotated, signedTwice)
        assertEquals(2, SignatureInspector.inspect(signedTwice).size)

        val target = PdfRevisions.truncationLengthFor(signedTwice, 1)
        assertEquals(
            "must truncate to the revision the second signature signed, not to the first",
            annotated.length(),
            target,
        )

        val reduced = temp.newFile("reduced.pdf")
        reduced.writeBytes(signedTwice.readBytes().copyOfRange(0, target!!.toInt()))
        assertArrayEquals(
            "the unsigned revision must survive verbatim",
            annotatedBytes,
            reduced.readBytes(),
        )
        assertEquals(1, SignatureInspector.inspect(reduced).size)
    }

    /**
     * A boundary is not just "a `%%EOF` that happens to parse".
     *
     * The marker can appear as payload -- an uncompressed stream, a metadata
     * string -- and PDFBox will reconstruct the cross-reference table of a prefix
     * cut anywhere, so parsing the result cannot rule that out. What settles it is
     * the trailer: `startxref` naming an offset that really addresses a
     * cross-reference section *in this file*. With that offset made meaningless,
     * the same file must stop offering the boundary rather than truncate to it.
     *
     * Failing closed is the point. Nothing here can turn a bad boundary into a
     * good one, only decline to guess.
     */
    @Test
    fun `a marker whose startxref addresses nothing is not a boundary`() {
        val original = blank("original.pdf")
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(original, signed)

        assertEquals(
            "control: intact, the original revision is the boundary",
            original.length(),
            PdfRevisions.truncationLengthFor(signed, 0),
        )

        // Zero the offset in the original revision's trailer, in place, so the
        // file's length and every other byte are untouched. The signed increment
        // reaches the same cross-reference section through its own /Prev, so the
        // document still opens -- only the claim "a revision ends here" is gone.
        val bytes = signed.readBytes()
        val trailer = "startxref".toByteArray(Charsets.ISO_8859_1)
        var at = indexOfBytes(bytes, trailer, 0) + trailer.size
        while (bytes[at].toInt().toChar().isWhitespace()) at++
        while (bytes[at].toInt().toChar().isDigit()) {
            bytes[at] = '0'.code.toByte()
            at++
        }
        val defaced = temp.newFile("defaced.pdf").apply { writeBytes(bytes) }

        assertEquals(
            "the document must still be readable, or this proves nothing",
            1,
            SignatureInspector.inspect(defaced).size,
        )
        assertNull(
            "with no trailer to trust, the signature is not removable",
            PdfRevisions.truncationLengthFor(defaced, 0),
        )
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int =
        (from..haystack.size - needle.size).first { i ->
            needle.indices.all { haystack[i + it] == needle[it] }
        }

    @Test
    fun `an unsigned pdf offers nothing to truncate`() {
        assertNull(PdfRevisions.truncationLengthFor(blank("a.pdf"), 0))
    }

    /**
     * The batch call exists to avoid re-reading the file once per signature, so it
     * has to agree with the single-index call exactly -- otherwise opening a
     * document would offer different boundaries than asking for one.
     */
    @Test
    fun `the batch call agrees with the per-index call`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val original = blank("a.pdf")
        val once = temp.newFile("1.pdf"); signer.sign(original, once)
        val twice = temp.newFile("2.pdf"); signer.sign(once, twice)

        val batch = PdfRevisions.truncationLengths(twice)
        assertEquals(2, batch.size)
        assertEquals(PdfRevisions.truncationLengthFor(twice, 0), batch[0])
        assertEquals(PdfRevisions.truncationLengthFor(twice, 1), batch[1])
    }

    @Test
    fun `an unsigned pdf has no boundaries to list`() {
        assertEquals(emptyList<Long?>(), PdfRevisions.truncationLengths(blank("a.pdf")))
    }

    /**
     * Index i of the signature list and index i of the boundary list must be the
     * same signature. Both sort by revision end rather than trusting the order
     * PDFBox happens to return, so this pins the two together.
     */
    @Test
    fun `inspection order matches boundary order`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val original = blank("a.pdf")
        val once = temp.newFile("1.pdf"); signer.sign(original, once)
        val twice = temp.newFile("2.pdf"); signer.sign(once, twice)
        val thrice = temp.newFile("3.pdf"); signer.sign(twice, thrice)

        // Oldest first: each signature covers strictly fewer bytes than the next.
        val signatures = SignatureInspector.inspect(thrice)
        assertEquals(3, signatures.size)
        // Only the newest reaches end-of-file, which is what identifies it as last.
        assertTrue(signatures.last().coversWholeDocument)
        assertTrue(signatures.dropLast(1).none { it.coversWholeDocument })

        val boundaries = PdfRevisions.truncationLengths(thrice)
        assertEquals(listOf(original.length(), once.length(), twice.length()), boundaries)
    }
}

/**
 * Pins down what `coversWholeDocument` actually reports, since the UI's meaning
 * depends on it: for a clean append-only chain only the newest signature reaches
 * end-of-file, so it is a document-level fact and not a per-signature one.
 */
class CoversWholeDocumentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blank(name: String): File {
        val file = temp.newFile(name)
        PDDocument().use { it.addPage(PDPage()); it.save(file) }
        return file
    }

    @Test
    fun `only the newest signature reaches end of file`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        var current = blank("a.pdf")
        repeat(3) { i ->
            val next = temp.newFile("s$i.pdf")
            signer.sign(current, next)
            current = next
        }

        val flags = SignatureInspector.inspect(current).map { it.coversWholeDocument }
        assertEquals(listOf(false, false, true), flags)
        // ...and every signature is intact, which is the per-signature fact.
        assertTrue(SignatureInspector.inspect(current).all { it.integrity == SignatureIntegrity.OK })
    }

    /** Trailing bytes no signature covers: the one case worth warning about. */
    @Test
    fun `appending unsigned bytes leaves the newest signature short of the end`() {
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(blank("a.pdf"), signed)
        assertTrue(SignatureInspector.inspect(signed).single().coversWholeDocument)

        signed.appendBytes("unsigned trailing bytes".toByteArray())

        val after = SignatureInspector.inspect(signed).single()
        assertEquals(
            "the signature itself is still intact",
            SignatureIntegrity.OK,
            after.integrity,
        )
        assertTrue("but it no longer covers the whole file", !after.coversWholeDocument)
    }

    /**
     * The library list shows a count and nothing else, so it must not have to pay
     * for verification -- one RSA operation per signature per document, on every
     * refresh -- to render it.
     */
    @Test
    fun `counting signatures agrees with inspecting them`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        var current = blank("a.pdf")
        assertEquals(0, SignatureInspector.count(current))

        repeat(3) { i ->
            val next = temp.newFile("c$i.pdf")
            signer.sign(current, next)
            current = next
            assertEquals(i + 1, SignatureInspector.count(current))
            assertEquals(SignatureInspector.inspect(current).size, SignatureInspector.count(current))
        }
    }

    /** A file that will not parse has no count to show, and must not throw for one. */
    @Test
    fun `counting a damaged file reports none rather than throwing`() {
        val junk = temp.newFile("junk.pdf")
        junk.writeBytes("not a pdf at all".toByteArray())

        assertEquals(0, SignatureInspector.count(junk))
    }
}
