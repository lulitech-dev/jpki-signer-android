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
        assertTrue("the surviving signature must still verify", remaining.single().integrityOk)
        assertTrue(remaining.single().coversWholeDocument)
    }

    @Test
    fun `an unsigned pdf offers nothing to truncate`() {
        assertNull(PdfRevisions.truncationLengthFor(blank("a.pdf"), 0))
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
        assertTrue(SignatureInspector.inspect(current).all { it.integrityOk })
    }

    /** Trailing bytes no signature covers: the one case worth warning about. */
    @Test
    fun `appending unsigned bytes leaves the newest signature short of the end`() {
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(blank("a.pdf"), signed)
        assertTrue(SignatureInspector.inspect(signed).single().coversWholeDocument)

        signed.appendBytes("unsigned trailing bytes".toByteArray())

        val after = SignatureInspector.inspect(signed).single()
        assertTrue("the signature itself is still intact", after.integrityOk)
        assertTrue("but it no longer covers the whole file", !after.coversWholeDocument)
    }
}
