package dev.lulitech.jpkisigner.jpki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * A reply with no room for a status word.
     *
     * Nothing in a well-behaved exchange produces one; a reader stack or a card
     * that stops mid-answer does. It used to raise `IllegalArgumentException`,
     * which every send site's IOException handling let straight past, so it
     * arrived on screen as untranslated English through the unforeseen-failure
     * path -- in the one module whose whole error design exists to prevent that.
     */
    private class TruncatingCard(private val reply: ByteArray) : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray = reply
    }

    @Test
    fun `a reply with no status word is a card problem, not an argument error`() {
        val problem = assertThrows(CardException::class.java) {
            JpkiSession(TruncatingCard(ByteArray(1))).selectApplication()
        }.problem

        assertEquals(CardProblem.MalformedResponse, problem)
    }

    @Test
    fun `an empty reply is a card problem too`() {
        val problem = assertThrows(CardException::class.java) {
            JpkiSession(TruncatingCard(ByteArray(0))).selectApplication()
        }.problem

        assertEquals(CardProblem.MalformedResponse, problem)
    }

    /**
     * After a VERIFY the honest answer is narrower still. Something answered, so
     * the card may well have processed the command and moved its counter, but
     * with no status word there is no telling a success from a wrong PIN. That is
     * the same position a dropped link leaves us in, and it must produce the same
     * message -- the one that tells the user to tap again and re-read the counter
     * -- rather than one that says nothing about the attempt.
     */
    @Test
    fun `a verify with no status word back leaves the outcome unknown`() {
        val session = JpkiSession(
            object : ApduTransceiver {
                override fun transceive(command: ByteArray): ByteArray =
                    // Only the VERIFY carrying data is truncated; everything
                    // leading up to it answers normally.
                    if ((command[1].toInt() and 0xFF) == 0x20 && command.size > 4) {
                        ByteArray(1)
                    } else {
                        byteArrayOf(0x90.toByte(), 0x00)
                    }
            },
        )

        val problem = assertThrows(CardException::class.java) {
            session.verifyPin(JpkiKey.DIGITAL_SIGNATURE, "ABC123".toCharArray())
        }.problem

        assertEquals(CardProblem.VerifyOutcomeUnknown, problem)
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

    /**
     * A blocked PIN is its own answer, not a count of zero.
     *
     * `63 C0` also parses as "zero attempts left", and the caller then refused on
     * its own floor and told the user "signing was stopped so a mistyped PIN
     * cannot use them up" -- about attempts that were already gone, and without
     * naming the municipal window that is the only way back. `69 83` says the
     * same thing outright and used to fall through to a generic "the card did not
     * accept a command".
     */
    @Test
    fun `a blocked pin is named as blocked when the counter is read`() {
        for (status in listOf(0x63C0, 0x6983)) {
            val thrown = assertThrows(CardException::class.java) {
                JpkiSession(FakeCard(derFile(4), retryStatus = status))
                    .remainingAttempts(JpkiKey.DIGITAL_SIGNATURE)
            }
            assertEquals("SW %04X".format(status), CardProblem.PinBlocked, thrown.problem)
        }
    }

    /**
     * The problem names the step as a value, not as English prose.
     *
     * `:app` formats this into a translated sentence, and it used to be handed the
     * developer-facing command name to put there -- so a Japanese-language app
     * showed "COMPUTE DIGITAL SIGNATURE" inside its own wording. That name is
     * still on the exception's message, which is where developer-facing text
     * belongs.
     */
    @Test
    fun `a failed command names the step, not an english command name`() {
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                if ((command[1].toInt() and 0xFF) == 0xA4) {
                    byteArrayOf(0x90.toByte(), 0x00)
                } else {
                    byteArrayOf(0x6A, 0x86.toByte())
                }
        }

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).signDigestInfo(JpkiKey.DIGITAL_SIGNATURE, ByteArray(51))
        }

        assertEquals(
            CardProblem.CommandFailed(CardCommand.ComputeSignature, StatusWord(0x6A86)),
            thrown.problem,
        )
        assertTrue(
            "the developer-facing name belongs on the message: ${thrown.message}",
            thrown.message.orEmpty().contains("COMPUTE DIGITAL SIGNATURE"),
        )
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

    /**
     * A card whose EF header declares a size, regardless of what it can serve.
     * Stands in for a corrupt or hostile card, which is the only place a length
     * this large can come from.
     */
    private class LyingCard(private val header: ByteArray) : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray =
            when (command[1].toInt() and 0xFF) {
                0xA4 -> swBytes(StatusWord.SUCCESS)
                0xB0 -> header + swBytes(StatusWord.SUCCESS)
                else -> swBytes(StatusWord.FILE_NOT_FOUND)
            }

        private fun swBytes(v: Int) =
            byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    @Test
    fun `refuses a declared length that would overflow the allocation`() {
        // 30 84 7F FF FF FF: four length bytes, close enough to Int.MAX_VALUE that
        // `2 + 4 + length` wraps negative when it is computed in an Int.
        val card = LyingCard(
            byteArrayOf(0x30, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).readCertificate(JpkiKey.AUTHENTICATION)
        }
        assertTrue(thrown.problem is CardProblem.Unreadable)
    }

    @Test
    fun `refuses a declared length beyond anything the applet holds`() {
        // 16 MB: no overflow, but it would allocate the lot and then ask for tens
        // of thousands of NFC round trips to fill it.
        val card = LyingCard(
            byteArrayOf(0x30, 0x83.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

        val thrown = assertThrows(CardException::class.java) {
            JpkiSession(card).readCertificate(JpkiKey.AUTHENTICATION)
        }
        assertTrue(thrown.problem is CardProblem.Unreadable)
    }

    /**
     * The PIN reaches the card inside a command APDU, which is a second copy of
     * it. Wiping only the encoded PIN left that one on the heap until a collector
     * happened to reach it.
     */
    @Test
    fun `verifying a PIN leaves no copy of it in the command that carried it`() {
        val sent = mutableListOf<ByteArray>()
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray {
                // The array itself, not a copy: the point is what it holds after
                // verifyPin has returned.
                sent += command
                return byteArrayOf(0x90.toByte(), 0x00)
            }
        }
        val pin = byteArrayOf('1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte())

        JpkiSession(card).verifyPin(JpkiKey.AUTHENTICATION, charArrayOf('1', '2', '3', '4'))

        assertTrue("a VERIFY must have been sent", sent.any { it.size > 4 })
        assertTrue(
            "no command APDU may still hold the PIN",
            sent.none { it.containsSequence(pin) },
        )
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { at -> needle.indices.all { this[at + it] == needle[it] } }
}

/** Behaviour when the card leaves the field mid-command. */
class CardLostTest {

    /** Fails on the nth command with an IOException, as a removed card does. */
    private class FlakyCard(private val failOnInstruction: Int) : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            if (ins == failOnInstruction) throw java.io.IOException("Tag was lost")
            return byteArrayOf(0x90.toByte(), 0x00)
        }
    }

    @Test
    fun `a dropped link during verify says the attempt is uncertain`() {
        val session = JpkiSession(FlakyCard(failOnInstruction = 0x20))
        val error = org.junit.Assert.assertThrows(CardException::class.java) {
            session.verifyPin(JpkiKey.DIGITAL_SIGNATURE, "ABC123".toCharArray())
        }
        // The user must not be told "wrong PIN" or "nothing happened"; neither is known.
        assertEquals(CardProblem.VerifyOutcomeUnknown, error.problem)
        assertTrue(
            "message must admit the attempt may have counted: ${error.message}",
            error.message!!.contains("may or may not"),
        )
    }

    @Test
    fun `a dropped link during a read is reported as lost contact`() {
        val session = JpkiSession(FlakyCard(failOnInstruction = 0xB0))
        val error = org.junit.Assert.assertThrows(CardException::class.java) {
            session.readCertificate(JpkiKey.AUTHENTICATION)
        }
        assertEquals(CardProblem.LostContact, error.problem)
        assertTrue(error.message!!.contains("lost contact"))
    }

    /** One VERIFY per call, no matter what the card answers. */
    @Test
    fun `a wrong pin is never retried`() {
        var verifyCount = 0
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                when (command[1].toInt() and 0xFF) {
                    0x20 -> {
                        if (command.size > 4) verifyCount++
                        byteArrayOf(0x63, 0xC2.toByte())
                    }
                    else -> byteArrayOf(0x90.toByte(), 0x00)
                }
        }
        val error = org.junit.Assert.assertThrows(CardException::class.java) {
            JpkiSession(card).verifyPin(JpkiKey.DIGITAL_SIGNATURE, "ABC123".toCharArray())
        }
        assertEquals("exactly one VERIFY must be sent", 1, verifyCount)
        // The remaining count travels as data, not as a substring of a message.
        assertEquals(CardProblem.WrongPin(remainingAttempts = 2), error.problem)
    }

    @Test
    fun `a blocked pin points at the municipal window`() {
        val card = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                when (command[1].toInt() and 0xFF) {
                    0x20 -> if (command.size > 4) {
                        byteArrayOf(0x69, 0x83.toByte())
                    } else {
                        byteArrayOf(0x63, 0xC0.toByte())
                    }
                    else -> byteArrayOf(0x90.toByte(), 0x00)
                }
        }
        val error = org.junit.Assert.assertThrows(CardException::class.java) {
            JpkiSession(card).verifyPin(JpkiKey.DIGITAL_SIGNATURE, "ABC123".toCharArray())
        }
        assertEquals(CardProblem.PinBlocked, error.problem)
        assertTrue(error.statusWord!!.isPinBlocked)
    }
}
