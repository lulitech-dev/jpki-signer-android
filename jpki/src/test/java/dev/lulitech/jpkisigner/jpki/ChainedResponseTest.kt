package dev.lulitech.jpkisigner.jpki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two status words that mean "ask again" rather than "failed".
 *
 * Both used to be reported as a failed command. For COMPUTE DIGITAL SIGNATURE
 * that lands *after* the VERIFY -- an attempt already spent, on a key that only a
 * municipal window can unblock -- which is precisely the outcome the signing flow
 * exists to prevent.
 */
class ChainedResponseTest {

    private fun sw(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    @Test
    fun `61 xx names the bytes still waiting, 6C xx names the Le`() {
        assertEquals(5, StatusWord(0x6105).bytesAvailable)
        assertEquals("a count of zero means 256", 256, StatusWord(0x6100).bytesAvailable)
        assertEquals(0x20, StatusWord(0x6C20).expectedLength)
        assertEquals(256, StatusWord(0x6C00).expectedLength)
    }

    @Test
    fun `neither is mistaken for the other, nor for a retry counter`() {
        assertEquals(null, StatusWord(0x6105).expectedLength)
        assertEquals(null, StatusWord(0x6C20).bytesAvailable)
        assertEquals(null, StatusWord(0x63C3).bytesAvailable)
        assertEquals(null, StatusWord(0x63C3).expectedLength)
        assertEquals(null, StatusWord(StatusWord.SUCCESS).bytesAvailable)
    }

    /**
     * A card that holds its answer back and waits to be asked. The signature is
     * split so the reassembly, not just the round trip, is what is checked.
     */
    private class ChainingCard(private val answer: ByteArray, private val firstChunk: Int) :
        ApduTransceiver {
        val instructions = mutableListOf<Int>()

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            instructions += ins
            return when (ins) {
                0xA4 -> sw(StatusWord.SUCCESS)
                0x2A -> answer.copyOfRange(0, firstChunk) + sw(0x6100 or (answer.size - firstChunk))
                0xC0 -> answer.copyOfRange(firstChunk, answer.size) + sw(StatusWord.SUCCESS)
                else -> sw(StatusWord.FILE_NOT_FOUND)
            }
        }

        private fun sw(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    @Test
    fun `a signature split across a GET RESPONSE is reassembled`() {
        val signature = ByteArray(256) { (it % 251).toByte() }
        val card = ChainingCard(signature, firstChunk = 200)

        val got = JpkiSession(card).signDigestInfo(JpkiKey.DIGITAL_SIGNATURE, ByteArray(51))

        assertArrayEquals(signature, got)
        assertTrue("a GET RESPONSE must have been sent", card.instructions.contains(0xC0))
    }

    /** The card rejects our Le and names the right one; the same command works with it. */
    @Test
    fun `6C xx makes the command reissue with the length the card asked for`() {
        val file = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03)
        val sent = mutableListOf<ByteArray>()
        val card = object : ApduTransceiver {
            var refused = false
            override fun transceive(command: ByteArray): ByteArray {
                sent += command.copyOf()
                return when (command[1].toInt() and 0xFF) {
                    0xA4 -> sw(StatusWord.SUCCESS)
                    0xB0 -> if (!refused) {
                        refused = true
                        sw(0x6C00 or file.size)
                    } else {
                        file + sw(StatusWord.SUCCESS)
                    }
                    else -> sw(StatusWord.FILE_NOT_FOUND)
                }
            }
        }

        assertArrayEquals(file, JpkiSession(card).readCertificate(JpkiKey.AUTHENTICATION))

        val reads = sent.filter { (it[1].toInt() and 0xFF) == 0xB0 }
        assertEquals("exactly one reissue, not a loop", 2, reads.size)
        assertEquals(
            "the reissue carries the Le the card named",
            file.size.toByte(),
            reads[1][reads[1].lastIndex],
        )
    }

    /** The loop is driven by a status word the card chooses, so it must be bounded. */
    @Test
    fun `a card that never stops asking is cut off rather than spun against forever`() {
        var rounds = 0
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                when (command[1].toInt() and 0xFF) {
                    0xA4 -> sw(StatusWord.SUCCESS)
                    else -> {
                        rounds++
                        byteArrayOf(0x00) + sw(0x6101)
                    }
                }
        }

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).signDigestInfo(JpkiKey.DIGITAL_SIGNATURE, ByteArray(51))
        }
        assertTrue(thrown.problem is CardProblem.Unreadable)
        assertTrue("the loop must be bounded, not merely slow: $rounds", rounds < 20)
    }

    /**
     * A missing CA-certificate EF answers 6A82 -- and so does a card that refuses
     * the application. Matching on the status word alone read the second as the
     * first, and dropped the CA certificate out of the signature instead of
     * refusing the card.
     */
    @Test
    fun `a card that refuses the application is not mistaken for a missing ca certificate`() {
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                byteArrayOf(0x6A, 0x82.toByte())
        }

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).readCaCertificate(JpkiKey.DIGITAL_SIGNATURE)
        }
        assertEquals(CardProblem.NotJpkiCard, thrown.problem)
    }

    /** A card that answers but is not this one: the user can act on that. */
    @Test
    fun `a card that refuses the JPKI application is named as the wrong card`() {
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                byteArrayOf(0x6A, 0x82.toByte())
        }

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).remainingAttempts(JpkiKey.DIGITAL_SIGNATURE)
        }
        assertEquals(CardProblem.NotJpkiCard, thrown.problem)
    }
}
