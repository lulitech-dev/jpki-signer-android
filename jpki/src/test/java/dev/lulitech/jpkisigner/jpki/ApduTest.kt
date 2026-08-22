package dev.lulitech.jpkisigner.jpki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ApduTest {

    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `selects the jpki application`() {
        assertArrayEquals(
            hex(0x00, 0xA4, 0x04, 0x0C, 0x0A, 0xD3, 0x92, 0xF0, 0x00, 0x26, 0x01, 0x00, 0x00, 0x00, 0x01),
            Apdu.selectApplication(),
        )
    }

    @Test
    fun `selects an ef by identifier`() {
        assertArrayEquals(hex(0x00, 0xA4, 0x02, 0x0C, 0x02, 0x00, 0x1B), Apdu.selectFile(0x001B))
        assertArrayEquals(hex(0x00, 0xA4, 0x02, 0x0C, 0x02, 0x00, 0x0A), Apdu.selectFile(0x000A))
    }

    /**
     * Case-1 APDU: header only. A trailing 00 would be parsed as Le=0 and the
     * card answers 6700; verified against a real card.
     */
    @Test
    fun `retry counter query is a bare case-1 apdu`() {
        assertArrayEquals(hex(0x00, 0x20, 0x00, 0x80), Apdu.readRetryCounter())
        assertEquals("no Lc and no Le", 4, Apdu.readRetryCounter().size)
    }

    @Test
    fun `signature command wraps the digest info`() {
        val digestInfo = ByteArray(51) { 0x11 }
        val apdu = Apdu.computeSignature(digestInfo)
        assertArrayEquals(hex(0x80, 0x2A, 0x00, 0x80, 51), apdu.copyOfRange(0, 5))
        assertEquals(0, apdu.last().toInt())
        assertEquals(5 + 51 + 1, apdu.size)
    }

    @Test
    fun `status word decodes remaining attempts without decrementing`() {
        assertEquals(3, StatusWord(0x63C3).remainingAttempts)
        assertEquals(0, StatusWord(0x63C0).remainingAttempts)
        assertNull(StatusWord(0x9000).remainingAttempts)
        assertEquals(true, StatusWord(0x9000).isSuccess)
        assertEquals(true, StatusWord(0x63C0).isPinBlocked)
        assertEquals(true, StatusWord(0x6983).isPinBlocked)
    }

    @Test
    fun `response splits data from status word`() {
        val response = Response(hex(0xAA, 0xBB, 0x90, 0x00))
        assertArrayEquals(hex(0xAA, 0xBB), response.data)
        assertEquals(StatusWord.SUCCESS, response.statusWord.value)
    }

    @Test
    fun `signature pin rejects lowercase before touching the card`() {
        val key = JpkiKey.DIGITAL_SIGNATURE
        assertNull(key.validatePin("ABC123".toCharArray()))
        // Lowercase would be encoded faithfully, rejected, and cost an attempt.
        assertEquals("PIN must be digits and uppercase letters only", key.validatePin("abc123".toCharArray()))
        assertEquals("PIN must be 6 to 16 characters", key.validatePin("AB12".toCharArray()))
    }

    @Test
    fun `auth pin must be four digits`() {
        val key = JpkiKey.AUTHENTICATION
        assertNull(key.validatePin("1234".toCharArray()))
        assertEquals("PIN must be exactly 4 characters", key.validatePin("12345".toCharArray()))
        assertEquals("PIN must be digits only", key.validatePin("12AB".toCharArray()))
    }

    @Test
    fun `malformed pin never reaches the card`() {
        val session = JpkiSession(object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray =
                throw AssertionError("no APDU should be sent for a malformed PIN")
        })
        assertThrows(IllegalArgumentException::class.java) {
            session.verifyPin(JpkiKey.DIGITAL_SIGNATURE, "short".toCharArray())
        }
    }
}
