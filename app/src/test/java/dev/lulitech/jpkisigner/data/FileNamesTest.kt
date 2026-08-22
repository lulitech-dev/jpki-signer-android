package dev.lulitech.jpkisigner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    // --- the proposed export name --------------------------------------------

    @Test
    fun `proposes a signed suffix with only the token selected`() {
        val proposal = FileNames.proposeExportName("契約書.pdf")

        assertEquals("契約書-signed.pdf", proposal.text)
        assertEquals(
            "the selection must cover exactly the token",
            "signed",
            proposal.text.substring(proposal.selectionStart, proposal.selectionEnd),
        )
    }

    /** A second signature must not produce `-signed-signed`. */
    @Test
    fun `an already-signed name keeps one suffix and selects it`() {
        val proposal = FileNames.proposeExportName("契約書-signed.pdf")

        assertEquals("契約書-signed.pdf", proposal.text)
        assertEquals(
            "signed",
            proposal.text.substring(proposal.selectionStart, proposal.selectionEnd),
        )
    }

    @Test
    fun `a name without an extension still gets one`() {
        assertEquals("contract-signed.pdf", FileNames.proposeExportName("contract").text)
    }

    @Test
    fun `the extension is matched case-insensitively`() {
        assertEquals("report-signed.pdf", FileNames.proposeExportName("report.PDF").text)
    }

    // --- validating what the user typed --------------------------------------

    @Test
    fun `accepts an ordinary name`() {
        assertNull(FileNames.validateExportName("契約書-signed.pdf"))
    }

    @Test
    fun `rejects blank, separators, missing extension and over-long names`() {
        assertEquals(ExportNameProblem.BLANK, FileNames.validateExportName("   "))
        assertEquals(ExportNameProblem.SEPARATORS, FileNames.validateExportName("a/b.pdf"))
        assertEquals(ExportNameProblem.NOT_PDF, FileNames.validateExportName("contract.txt"))
        assertEquals(
            ExportNameProblem.TOO_LONG,
            FileNames.validateExportName("契".repeat(90) + ".pdf"),
        )
    }

    /** 255 bytes is the limit, so this must be accepted rather than rejected. */
    @Test
    fun `accepts a name at exactly the byte limit`() {
        val name = "a".repeat(FileNames.MAX_NAME_BYTES - FileNames.EXTENSION.length) +
            FileNames.EXTENSION
        assertEquals(255, name.toByteArray(Charsets.UTF_8).size)
        assertNull(FileNames.validateExportName(name))
    }

    // --- sanitising an incoming name -----------------------------------------

    @Test
    fun `sanitising caps length in bytes and keeps the extension`() {
        val safe = FileNames.safe("契".repeat(200) + ".pdf")
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun `sanitising removes separators`() {
        assertEquals("a_b.pdf", FileNames.safe("a/b.pdf"))
    }
}
