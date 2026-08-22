package dev.lulitech.jpkisigner.jpki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the multi-round-trip logic -- DER length parsing, chunked reads,
 * retry counters -- against a scripted card. No hardware, and no PIN attempts.
 */
class FakeCardTest {

    /** Minimal card: SELECT always succeeds, READ BINARY serves one file. */
    private class FakeCard(
        private val file: ByteArray,
        private val chunkLimit: Int = 0xFF,
        private val retryStatus: Int = 0x63C3,
    ) : ApduTransceiver {

        val commands = mutableListOf<ByteArray>()

        override fun transceive(command: ByteArray): ByteArray {
            commands += command
            val ins = command[1].toInt() and 0xFF
            return when (ins) {
                0xA4 -> sw(StatusWord.SUCCESS)
                0x20 -> if (command.size == 4) sw(retryStatus) else sw(StatusWord.SUCCESS)
                0xB0 -> {
                    val offset = ((command[2].toInt() and 0x7F) shl 8) or (command[3].toInt() and 0xFF)
                    val requested = (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
                    if (offset >= file.size) return sw(StatusWord.WRONG_LENGTH)
                    val end = minOf(offset + minOf(requested, chunkLimit), file.size)
                    file.copyOfRange(offset, end) + swBytes(StatusWord.SUCCESS)
                }
                else -> sw(StatusWord.FILE_NOT_FOUND)
            }
        }

        private fun swBytes(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        private fun sw(v: Int) = swBytes(v)
    }

    /** A DER SEQUENCE with a two-byte long-form length, like a real certificate. */
    private fun derFile(contentLength: Int): ByteArray {
        val header = byteArrayOf(
            0x30, 0x82.toByte(),
            ((contentLength shr 8) and 0xFF).toByte(), (contentLength and 0xFF).toByte(),
        )
        return header + ByteArray(contentLength) { (it % 251).toByte() }
    }

    @Test
    fun `reads a certificate spanning many chunks`() {
        val file = derFile(1500)
        val card = FakeCard(file)
        val session = JpkiSession(card)

        val read = session.readCertificate(JpkiKey.AUTHENTICATION)

        assertArrayEquals("full file must be reassembled", file, read)
        val reads = card.commands.count { (it[1].toInt() and 0xFF) == 0xB0 }
        assertEquals("1504 bytes in 255-byte chunks after an 8-byte probe", 7, reads)
    }

    /** Some cards return less than asked for; the loop must not stall or overrun. */
    @Test
    fun `handles a card that returns short reads`() {
        val file = derFile(600)
        val read = JpkiSession(FakeCard(file, chunkLimit = 64)).readCertificate(JpkiKey.AUTHENTICATION)
        assertArrayEquals(file, read)
    }

    @Test
    fun `reads a short-form der object`() {
        val file = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03)
        assertArrayEquals(file, JpkiSession(FakeCard(file)).readCertificate(JpkiKey.AUTHENTICATION))
    }

    @Test
    fun `reports remaining attempts from the card`() {
        assertEquals(3, JpkiSession(FakeCard(derFile(4), retryStatus = 0x63C3)).remainingAttempts(JpkiKey.DIGITAL_SIGNATURE))
        assertEquals(1, JpkiSession(FakeCard(derFile(4), retryStatus = 0x63C1)).remainingAttempts(JpkiKey.DIGITAL_SIGNATURE))
    }

    /** Reading the retry counter must never send PIN data. */
    @Test
    fun `retry counter query carries no data`() {
        val card = FakeCard(derFile(4))
        JpkiSession(card).remainingAttempts(JpkiKey.DIGITAL_SIGNATURE)
        val verify = card.commands.single { (it[1].toInt() and 0xFF) == 0x20 }
        assertEquals("case-1 APDU: header only, no Lc, no Le", 4, verify.size)
    }

    @Test
    fun `missing ca certificate reports as absent rather than failing`() {
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray = when (command[1].toInt() and 0xFF) {
                0xA4 -> if (command.size >= 7 && command[6] == 0x02.toByte()) {
                    byteArrayOf(0x6A, 0x82.toByte()) // FILE NOT FOUND for the CA cert EF
                } else {
                    byteArrayOf(0x90.toByte(), 0x00)
                }
                else -> byteArrayOf(0x6A, 0x82.toByte())
            }
        }
        assertNull(JpkiSession(card).readCaCertificate(JpkiKey.DIGITAL_SIGNATURE))
    }
}
