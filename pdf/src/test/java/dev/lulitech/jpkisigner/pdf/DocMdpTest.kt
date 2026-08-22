package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * We produce approval signatures, never certification signatures.
 *
 * The reference implementation reads DocMDP to decide whether signing is allowed
 * and never writes one -- its `setMDPPermission` is called from nowhere in the jar
 * or in the desktop app. Certifying a document is a stronger claim than adding a
 * signature to it, and not one this app is entitled to make.
 */
class DocMdpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blank(name: String): File {
        val file = temp.newFile(name)
        PDDocument().use { it.addPage(PDPage()); it.save(file) }
        return file
    }

    /** Fabricates a certification signature, which production code never writes. */
    private fun certifiedPdf(name: String, permission: Int): File {
        val file = temp.newFile(name)
        PDDocument().use { document ->
            document.addPage(PDPage())
            val transformParams = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("TransformParams"))
                setItem(COSName.V, COSName.getPDFName("1.2"))
                setInt(COSName.P, permission)
            }
            val reference = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("SigRef"))
                setItem(COSName.getPDFName("TransformMethod"), COSName.DOCMDP)
                setItem(COSName.getPDFName("TransformParams"), transformParams)
            }
            val sigDict = COSDictionary().apply {
                setItem(COSName.REFERENCE, COSArray().apply { add(reference) })
            }
            document.documentCatalog.cosObject.setItem(
                COSName.PERMS,
                COSDictionary().apply { setItem(COSName.DOCMDP, sigDict) },
            )
            document.save(file)
        }
        return file
    }

    @Test
    fun `signing does not certify the document`() {
        val signed = temp.newFile("signed.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(blank("a.pdf"), signed)

        PDDocument.load(signed).use { document ->
            assertEquals("no certification signature may be written", 0, DocMdp.existingPermission(document))
            assertNull(
                "/Perms must be absent",
                document.documentCatalog.cosObject.getDictionaryObject(COSName.PERMS),
            )
        }
        // And the signature itself is still valid.
        assertTrue(SignatureInspector.inspect(signed).single().integrityOk)
    }

    /** A document certified against all change must not be signed. */
    @Test
    fun `refuses a document that permits no changes`() {
        val certified = certifiedPdf("locked.pdf", permission = DocMdp.NO_CHANGES_PERMITTED)
        assertEquals(1, PDDocument.load(certified).use { DocMdp.existingPermission(it) })

        assertThrows(ChangesNotPermittedException::class.java) {
            PdfSigner(SoftwareSignatureProvider()).sign(certified, temp.newFile("out.pdf"))
        }
    }

    /** Permission 2 allows further signing, so it must be accepted. */
    @Test
    fun `accepts a document that permits form filling and signing`() {
        val certified = certifiedPdf("open.pdf", permission = 2)
        val signed = temp.newFile("out.pdf")
        PdfSigner(SoftwareSignatureProvider()).sign(certified, signed)
        assertTrue(SignatureInspector.inspect(signed).last().integrityOk)
    }

    @Test
    fun `multiple signatures remain approval signatures and all verify`() {
        val signer = PdfSigner(SoftwareSignatureProvider())
        val once = temp.newFile("1.pdf"); signer.sign(blank("a.pdf"), once)
        val twice = temp.newFile("2.pdf"); signer.sign(once, twice)

        val signatures = SignatureInspector.inspect(twice)
        assertEquals(2, signatures.size)
        assertTrue(signatures.all { it.integrityOk })
        PDDocument.load(twice).use { assertEquals(0, DocMdp.existingPermission(it)) }
    }
}
