package dev.lulitech.jpkisigner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.FileNotFoundException

/**
 * The one failure the app has nothing translated to say about still reaches the
 * screen, so what it says there matters.
 *
 * `java.io` puts the whole path in front of the reason, and that path names the
 * user's own document -- in an app that otherwise never discloses where anything
 * is stored, and whose failures are the ones most likely to be screenshotted into
 * a bug report.
 */
class DescribeFailureTest {

    @Test
    fun `a filesystem path is not part of what the user is shown`() {
        val e = FileNotFoundException(
            "/data/user/0/dev.lulitech.jpkisigner/files/documents/17-ab/contract.pdf" +
                " (File name too long)",
        )
        val described = describe(e)

        assertFalse("no path may survive: $described", described.contains("/"))
        assertFalse("least of all the document's name", described.contains("contract"))
        assertEquals("(File name too long)", described)
    }

    @Test
    fun `an ordinary message is left alone`() {
        assertEquals(
            "signed output is not larger than the input",
            describe(IllegalStateException("signed output is not larger than the input")),
        )
    }

    /** An OutOfMemoryError carries no message at all, and is the likeliest one. */
    @Test
    fun `a throwable with nothing to say is named by its class`() {
        assertEquals("OutOfMemoryError", describe(OutOfMemoryError()))
    }

    /**
     * A PDF name object is not a path.
     *
     * PDFBox names the object it choked on, and those names start with a slash.
     * Stripping every slash-prefixed token took them out too, so the one part of
     * the message that said anything was the part that got deleted.
     */
    @Test
    fun `a pdf name object is not mistaken for a path`() {
        assertEquals(
            "expected /Type /Page but found /Font",
            describe(IllegalStateException("expected /Type /Page but found /Font")),
        )
    }

    /** A message that was nothing but a path must not come back blank. */
    @Test
    fun `a message that is only a path falls back to the class name`() {
        assertEquals(
            "FileNotFoundException",
            describe(FileNotFoundException("/files/documents/17-ab/contract.pdf")),
        )
    }
}
