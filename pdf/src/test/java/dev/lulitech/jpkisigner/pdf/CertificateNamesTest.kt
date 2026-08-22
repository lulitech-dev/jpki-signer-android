package dev.lulitech.jpkisigner.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateNamesTest {

    /**
     * A real 署名用証明書 puts an opaque identifier in CN and the holder's name in
     * a JPKI private extension, so the extension must win. Reading CN would put a
     * meaningless string in the PDF's /Name.
     */
    @Test
    fun `prefers the jpki name extension over the subject cn`() {
        val provider = SoftwareSignatureProvider(
            commonName = "202606221259350000013119126B",
            jpkiName = "山田 花子",
        )
        assertEquals("山田 花子", CertificateNames.holderName(provider.signerCertificate()))
    }

    /** The 認証用 certificate carries no such extension, so CN is the fallback. */
    @Test
    fun `falls back to the subject cn when the extension is absent`() {
        val provider = SoftwareSignatureProvider(commonName = "署名 太郎")
        assertEquals("署名 太郎", CertificateNames.holderName(provider.signerCertificate()))
    }

    @Test
    fun `the signed pdf carries the holder name, not the identifier`() {
        val provider = SoftwareSignatureProvider(
            commonName = "202606221259350000013119126B",
            jpkiName = "山田 花子",
        )
        val temp = createTempDirectory()
        val original = java.io.File(temp, "a.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            document.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            document.save(original)
        }
        val signed = java.io.File(temp, "signed.pdf")
        PdfSigner(provider).sign(original, signed)

        val info = SignatureInspector.inspect(signed).single()
        assertEquals("山田 花子", info.name)
        assertEquals("山田 花子", info.signerCommonName)
    }

    private fun createTempDirectory(): java.io.File =
        java.nio.file.Files.createTempDirectory("certnames").toFile().apply { deleteOnExit() }
}
