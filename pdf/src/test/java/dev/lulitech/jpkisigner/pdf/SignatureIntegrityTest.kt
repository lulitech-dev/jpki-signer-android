package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "We checked and it does not match" and "we could not check it" are different
 * claims about someone's document, and only the first is a finding about the
 * document.
 *
 * They used to be the same boolean, so anything the verifier could not run --
 * most obviously a signature that is not RSA, since [SignatureInspector] builds
 * an RSA-only verifier -- came out as "this file may be damaged" over an intact
 * PDF. A non-RSA signature cannot be fixtured here (the whole
 * [SignatureProvider] contract, and [CmsBuilder]'s algorithm identifier, are
 * RSA-shaped), so the classification rule is pinned with the other way into the
 * same branch: a CMS that will not parse at all.
 */
class SignatureIntegrityTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an untouched signature verifies`() {
        val signature = SignatureInspector.inspect(sign(REASON)).single()

        assertEquals(SignatureIntegrity.OK, signature.integrity)
        assertNull("nothing was thrown, so there is nothing to report", signature.verificationError)
    }

    /**
     * Altering bytes the ByteRange covers is the one failure that *is* evidence
     * about the document. BouncyCastle raises it as its own exception type, which
     * is what lets it be told apart from every other throw.
     */
    @Test
    fun `altering the covered bytes reports a mismatch`() {
        val signed = sign(REASON)
        // In the signature dictionary, which the ByteRange covers, and the same
        // length -- so every offset in the file still points where it did and the
        // document parses exactly as before. The only thing that changed is a
        // byte the digest was taken over.
        val tampered = patch(signed, "tampered.pdf", REASON, REASON.dropLast(1) + "B")

        val signature = SignatureInspector.inspect(tampered).single()
        assertEquals(SignatureIntegrity.MISMATCH, signature.integrity)
        assertNotNull(signature.verificationError)
    }

    /**
     * A CMS this build cannot read says nothing whatsoever about the bytes it was
     * supposed to cover, so it must not borrow the mismatch verdict.
     */
    @Test
    fun `an unreadable cms reports no verdict rather than a mismatch`() {
        val signed = sign(REASON)
        val bytes = signed.readBytes()

        // ByteRange[1] is the offset of the '<' opening /Contents; the DER starts
        // one byte after it. Overwriting hex digits in place keeps the file's
        // length, and therefore its ByteRange and every offset in it, intact --
        // only the signature container becomes unparseable.
        val contentsAt = byteRangeOf(signed)[1].toInt() + 1
        java.util.Arrays.fill(bytes, contentsAt, contentsAt + 64, '0'.code.toByte())
        val broken = temp.newFile("broken.pdf").apply { writeBytes(bytes) }

        val signature = SignatureInspector.inspect(broken).single()
        assertEquals(SignatureIntegrity.UNCHECKED, signature.integrity)
        assertNotNull(
            "an unchecked signature must carry the reason it could not be checked",
            signature.verificationError,
        )
    }

    /** Everything else about the signature is still readable without a verdict. */
    @Test
    fun `an unchecked signature still reports its dictionary`() {
        val signed = sign(REASON)
        val bytes = signed.readBytes()
        val contentsAt = byteRangeOf(signed)[1].toInt() + 1
        java.util.Arrays.fill(bytes, contentsAt, contentsAt + 64, '0'.code.toByte())
        val broken = temp.newFile("broken2.pdf").apply { writeBytes(bytes) }

        val signature = SignatureInspector.inspect(broken).single()
        assertEquals(REASON, signature.reason)
        assertNotNull(signature.signedAt)
        // Read out of the CMS, which is the part that would not parse.
        assertNull(signature.signerCommonName)
    }

    private fun sign(reason: String): File {
        val original = temp.newFile("original-$reason.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(original)
        }
        val signed = temp.newFile("signed-$reason.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(original, signed, SignParams(reason = reason))
        return signed
    }

    private fun byteRangeOf(file: File): IntArray =
        PDDocument.load(file).use { it.signatureDictionaries.single().byteRange }

    /** Replaces [from] with [to], which must be the same length, once. */
    private fun patch(source: File, name: String, from: String, to: String): File {
        require(from.length == to.length) { "replacement must not change the file's length" }
        val bytes = source.readBytes()
        val needle = from.toByteArray(Charsets.ISO_8859_1)
        val at = (0..bytes.size - needle.size).first { i ->
            needle.indices.all { bytes[i + it] == needle[it] }
        }
        to.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, at)
        return temp.newFile(name).apply { writeBytes(bytes) }
    }

    private companion object {
        /** ASCII, so PDFBox writes it as a literal string we can find and patch. */
        const val REASON = "AAAAAAAA"
    }
}
