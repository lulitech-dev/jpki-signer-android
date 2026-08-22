package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `accepts an ordinary pdf`() {
        val file = temp.newFile("ok.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(file) }
        assertNull(PdfValidator.validate(file))
    }

    @Test
    fun `rejects a file that is not a pdf`() {
        val file = temp.newFile("not.pdf")
        file.writeText("this is plainly not a PDF")
        assertEquals(PdfRejection.UNREADABLE, PdfValidator.validate(file))
    }

    @Test
    fun `rejects an empty file`() {
        assertEquals(PdfRejection.UNREADABLE, PdfValidator.validate(temp.newFile("empty.pdf")))
    }

    @Test
    fun `rejects a missing file`() {
        assertEquals(
            PdfRejection.UNREADABLE,
            PdfValidator.validate(java.io.File(temp.root, "absent.pdf")),
        )
    }

    /** Truncated mid-file, the shape a failed download or copy leaves behind. */
    @Test
    fun `rejects a truncated pdf`() {
        val whole = temp.newFile("whole.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(whole) }
        val truncated = temp.newFile("truncated.pdf")
        truncated.writeBytes(whole.readBytes().copyOfRange(0, whole.length().toInt() / 2))
        assertEquals(PdfRejection.UNREADABLE, PdfValidator.validate(truncated))
    }

    /**
     * Encryption is rejected because signing it would mean rewriting content,
     * which breaks the append-only property the revision stack relies on.
     */
    @Test
    fun `rejects an encrypted pdf`() {
        val file = temp.newFile("encrypted.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission()))
            document.save(file)
        }
        assertEquals(PdfRejection.ENCRYPTED, PdfValidator.validate(file))
    }
}
