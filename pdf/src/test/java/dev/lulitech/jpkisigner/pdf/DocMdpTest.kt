package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocMdpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blankPdf(name: String): File {
        val file = temp.newFile(name)
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(file)
        }
        return file
    }

    @Test
    fun `first signature certifies the document`() {
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(blankPdf("a.pdf"), signed)

        PDDocument.load(signed).use { document ->
            assertEquals(
                "first signature should set DocMDP permission 2",
                DocMdp.DEFAULT_PERMISSION,
                DocMdp.existingPermission(document),
            )

            val perms = document.documentCatalog.cosObject
                .getDictionaryObject(COSName.PERMS) as COSDictionary
            val docMdp = perms.getDictionaryObject(COSName.DOCMDP) as COSDictionary
            val references = docMdp.getDictionaryObject(COSName.REFERENCE) as COSArray
            val reference = references.getObject(0) as COSDictionary

            assertEquals(COSName.DOCMDP, reference.getDictionaryObject(COSName.getPDFName("TransformMethod")))
            assertEquals(
                COSName.getPDFName("SHA1"),
                reference.getDictionaryObject(COSName.DIGEST_METHOD),
            )
            val params = reference.getDictionaryObject(COSName.getPDFName("TransformParams")) as COSDictionary
            assertEquals(COSName.getPDFName("1.2"), params.getDictionaryObject(COSName.V))
            assertEquals(2, params.getInt(COSName.P))
        }
    }

    /** There can be at most one certification signature per document. */
    @Test
    fun `second signature does not add another docmdp`() {
        val provider = SoftwareSignatureProvider()
        val signer = PdfSigner(provider)

        val once = temp.newFile("once.pdf")
        signer.sign(blankPdf("a.pdf"), once)
        val twice = temp.newFile("twice.pdf")
        signer.sign(once, twice)

        PDDocument.load(twice).use { document ->
            assertEquals(2, document.signatureDictionaries.size)
            // Still exactly one DocMDP, still pointing at the first signature.
            val perms = document.documentCatalog.cosObject
                .getDictionaryObject(COSName.PERMS) as COSDictionary
            assertNotNull(perms.getDictionaryObject(COSName.DOCMDP))
            assertEquals(DocMdp.DEFAULT_PERMISSION, DocMdp.existingPermission(document))
        }

        // And both signatures must still verify.
        val signatures = SignatureInspector.inspect(twice)
        assertEquals(2, signatures.size)
        assertTrue("all signatures must verify", signatures.all { it.integrityOk })
    }

    /** DocMDP must not break the byte-prefix property the revision stack needs. */
    @Test
    fun `docmdp is written incrementally`() {
        val original = blankPdf("a.pdf")
        val originalBytes = original.readBytes()
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(original, signed)

        val signedBytes = signed.readBytes()
        org.junit.Assert.assertArrayEquals(
            "original bytes must survive verbatim even with DocMDP added",
            originalBytes,
            signedBytes.copyOfRange(0, originalBytes.size),
        )
    }
}
