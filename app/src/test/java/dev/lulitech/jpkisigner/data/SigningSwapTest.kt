package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.jpki.ApduTransceiver
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.pdf.PdfRejection
import dev.lulitech.jpkisigner.pdf.SignParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What must survive a signing run that does not finish.
 *
 * The document is the user's only copy, so a failed run has to leave it byte for
 * byte as it was. This used to be able to lose it outright: the swap fell back to
 * deleting the head file and then renaming over it, and if that second rename
 * failed the original was already gone.
 */
class SigningSwapTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Accepts a PIN and then refuses to be read.
     *
     * Enough to get past VERIFY and into the write, which is the part under test;
     * producing a real signature would need a real key.
     */
    private class UnreadableCard : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray =
            when (command[1].toInt() and 0xFF) {
                // VERIFY with no data is the retry-counter probe: plenty left.
                0x20 -> if (command.size > 4) ok() else byteArrayOf(0x63, 0xC5.toByte())
                0xB0 -> byteArrayOf(0x6A, 0x82.toByte()) // READ BINARY: file not found
                else -> ok()
            }

        private fun ok() = byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun storeWith(
        contents: ByteArray,
        name: String = "report.pdf",
    ): Pair<DocumentStore, String> {
        val store = DocumentStore(temp.newFolder("documents"))
        val id = store.import(name, contents.inputStream())
        return store to id
    }

    @Test
    fun `a run that fails leaves the document byte-identical`() {
        // A real PDF, so the run gets past the rehearsal and fails where this is
        // about: inside the write, with the head file already opened for signing.
        val original = MINIMAL_PDF
        val (store, id) = storeWith(original)
        val head = store.get(id)!!.head

        assertThrows(Exception::class.java) {
            DocumentSigner(store).sign(
                session = JpkiSession(UnreadableCard()),
                documentId = id,
                pin = "ABC123".toCharArray(),
                params = SignParams(),
                key = JpkiKey.DIGITAL_SIGNATURE,
            )
        }

        assertEquals("the document must still be there", head, store.get(id)?.head)
        assertArrayEquals("and unchanged", original, head.readBytes())
    }

    /**
     * A run killed mid-sign leaves a `.signing` file that the library cannot see,
     * because it selects on the `.pdf` extension. Without a sweep it would sit in
     * the document's directory for good.
     */
    @Test
    fun `a stale staging file from an earlier run is reclaimed`() {
        val (store, id) = storeWith("original".toByteArray())
        val head = store.get(id)!!.head
        val stale = File(head.parentFile, "signing.part")
        stale.writeText("half a signed document, from a run that was killed")

        assertThrows(Exception::class.java) {
            DocumentSigner(store).sign(
                session = JpkiSession(UnreadableCard()),
                documentId = id,
                pin = "ABC123".toCharArray(),
                params = SignParams(),
                key = JpkiKey.DIGITAL_SIGNATURE,
            )
        }

        assertFalse("the stale staging file must not survive", stale.exists())
        assertEquals(
            "and nothing else may be left behind either",
            listOf(head.name),
            head.parentFile!!.list()!!.sorted(),
        )
    }

    /**
     * The staging file is named for the directory, not for the document, because a
     * stored name may reach NAME_MAX exactly. A suffix on top of that cannot be
     * created at all -- and being discovered only during the write, after the
     * VERIFY, it cost an attempt on a document that could never have been signed.
     */
    @Test
    fun `a document whose name fills the byte limit can still be signed`() {
        val (store, id) = storeWith(MINIMAL_PDF, name = "a".repeat(300) + ".pdf")
        val head = store.get(id)!!.head
        assertEquals(
            "precondition: the stored name is at the cap",
            FileNames.MAX_NAME_BYTES,
            head.name.toByteArray(Charsets.UTF_8).size,
        )

        // Fails at the certificate read, as UnreadableCard always does -- the
        // point is that it gets that far rather than dying on the output file.
        val failure = assertThrows(DocumentSigner.Failure::class.java) {
            DocumentSigner(store).sign(
                session = JpkiSession(UnreadableCard()),
                documentId = id,
                pin = "ABC123".toCharArray(),
                params = SignParams(),
                key = JpkiKey.DIGITAL_SIGNATURE,
            )
        }
        assertTrue(
            "the run must reach the card, not fail on the staging file: ${failure.reason}",
            failure.reason is SignFailure.Card,
        )
    }

    /**
     * DESIGN.md §6: everything checkable offline is checked offline. A document
     * that cannot be signed must be refused before the card is touched, or finding
     * out costs an attempt on a key that a municipal window has to unblock.
     */
    @Test
    fun `an unsignable document is refused before any apdu is sent`() {
        val (store, id) = storeWith("not a pdf at all".toByteArray())
        val silent = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                throw AssertionError("no APDU may be sent for a document we will refuse")
        }

        val failure = assertThrows(DocumentSigner.Failure::class.java) {
            DocumentSigner(store).sign(
                session = JpkiSession(silent),
                documentId = id,
                pin = "ABC123".toCharArray(),
                params = SignParams(),
                key = JpkiKey.DIGITAL_SIGNATURE,
            )
        }
        assertEquals(
            SignFailure.DocumentUnusable(PdfRejection.UNREADABLE),
            failure.reason,
        )
    }

    @Test
    fun `a malformed pin fails before any apdu is sent`() {
        val (store, id) = storeWith("original".toByteArray())
        val silent = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                throw AssertionError("no APDU may be sent for a malformed PIN")
        }

        val failure = assertThrows(DocumentSigner.Failure::class.java) {
            DocumentSigner(store).sign(
                session = JpkiSession(silent),
                documentId = id,
                // Lowercase: encoded faithfully by the card, rejected, and an
                // attempt spent for nothing.
                pin = "abc123".toCharArray(),
                params = SignParams(),
                key = JpkiKey.DIGITAL_SIGNATURE,
            )
        }
        assertEquals(
            SignFailure.MalformedPin(
                dev.lulitech.jpkisigner.jpki.PinProblem.NotUppercaseAlphanumeric,
            ),
            failure.reason,
        )
    }
}
