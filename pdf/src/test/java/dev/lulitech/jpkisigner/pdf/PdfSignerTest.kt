package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M0 gate: the PDF/CMS layer signs and verifies with no card and no device.
 */
class PdfSignerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `signs a pdf and the signature verifies`() {
        val original = blankPdf("original.pdf")
        val signed = temp.newFile("signed.pdf")

        PdfSigner(SoftwareSignatureProvider()).sign(
            input = original,
            output = signed,
            params = SignParams(reason = "テスト署名", location = "東京"),
        )

        val signatures = SignatureInspector.inspect(signed)
        assertEquals("expected exactly one signature", 1, signatures.size)

        val sig = signatures.single()
        assertTrue(
            "CMS signature must verify against its ByteRange (error=${sig.verificationError})",
            sig.integrity == SignatureIntegrity.OK,
        )
        assertTrue("nothing should follow the only signature", sig.coversWholeDocument)
        assertEquals("テスト署名", sig.reason)
        assertEquals("東京", sig.location)
        assertEquals("署名 太郎", sig.signerCommonName)
        assertNotNull(sig.signedAt)
    }

    /**
     * The revision model in DESIGN.md §3.3 derives every boundary from the PDF
     * itself, and "delete signature n" is a truncate to one of them. That is only
     * sound if an incremental update leaves the original bytes untouched. This
     * asserts the property directly rather than trusting the format.
     */
    @Test
    fun `signing appends and never rewrites the original bytes`() {
        val original = blankPdf("original.pdf")
        val originalBytes = original.readBytes()
        val signed = temp.newFile("signed.pdf")

        PdfSigner(SoftwareSignatureProvider()).sign(original, signed)

        val signedBytes = signed.readBytes()
        assertTrue(
            "signed output must be longer than the original",
            signedBytes.size > originalBytes.size,
        )
        assertArrayEquals(
            "the original bytes must survive verbatim as a prefix",
            originalBytes,
            signedBytes.copyOfRange(0, originalBytes.size),
        )
    }

    /**
     * Two signatures, then truncate back to the earlier revision's length. The
     * result must be byte-identical to the once-signed revision and must still
     * verify — that is the whole basis of the cascade delete in DESIGN.md §3.4.
     */
    @Test
    fun `truncating to a recorded prefix restores an earlier valid revision`() {
        val provider = SoftwareSignatureProvider()
        val signer = PdfSigner(provider)

        val original = blankPdf("original.pdf")
        val once = temp.newFile("once.pdf")
        signer.sign(original, once)

        val prefixLength = once.length()
        val onceBytes = once.readBytes()

        val twice = temp.newFile("twice.pdf")
        signer.sign(once, twice, SignParams(reason = "second"))
        assertEquals("expected two signatures", 2, SignatureInspector.inspect(twice).size)

        // "Delete signature #2" == truncate to the length the file had before it.
        val truncated = temp.newFile("truncated.pdf")
        truncated.writeBytes(twice.readBytes().copyOfRange(0, prefixLength.toInt()))

        assertArrayEquals(
            "truncation must reproduce the earlier revision exactly",
            onceBytes,
            truncated.readBytes(),
        )
        val restored = SignatureInspector.inspect(truncated)
        assertEquals(1, restored.size)
        assertEquals(
            "the restored revision must still verify",
            SignatureIntegrity.OK,
            restored.single().integrity,
        )
        assertTrue(restored.single().coversWholeDocument)
    }

    private fun blankPdf(name: String): File {
        val file = temp.newFile(name)
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(file)
        }
        return file
    }
}
