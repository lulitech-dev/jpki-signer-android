package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes a signed sample to `pdf/build/sample/` so it can be checked with tools
 * outside this codebase — `pdfsig`, `openssl cms -verify`, Adobe Reader, and at
 * M2 the 法務省 PDF署名プラグイン.
 *
 * Our own verifier agreeing with our own signer proves less than it appears to;
 * this exists so an independent implementation gets a vote.
 */
class SampleOutputTest {

    @Test
    fun `write a signed sample for external verification`() {
        val dir = File("build/sample").apply { mkdirs() }
        val original = File(dir, "original.pdf")
        val signed = File(dir, "signed.pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(original)
        }

        PdfSigner(SoftwareSignatureProvider()).sign(
            input = original,
            output = signed,
            params = SignParams(reason = "動作確認", location = "東京"),
        )

        assertTrue(signed.length() > original.length())
        println("### sample written to ${signed.absolutePath}")
    }
}
