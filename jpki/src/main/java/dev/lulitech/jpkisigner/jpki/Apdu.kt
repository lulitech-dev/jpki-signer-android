package dev.lulitech.jpkisigner.jpki

/** Raw APDU exchange. Implemented over IsoDep on device, and by fakes in tests. */
interface ApduTransceiver {
    /** Sends a command APDU and returns the full response, status word included. */
    fun transceive(command: ByteArray): ByteArray
}

/** ISO 7816-4 status word, i.e. the trailing two bytes of a response APDU. */
@JvmInline
value class StatusWord(val value: Int) {

    val isSuccess: Boolean get() = value == SUCCESS

    /**
     * Remaining PIN attempts when the card answers 63 Cx, else null.
     *
     * This is the only safe way to learn the counter: a VERIFY with no data
     * returns it *without* decrementing.
     */
    val remainingAttempts: Int? get() = if (value and 0xFFF0 == 0x63C0) value and 0x000F else null

    val isPinBlocked: Boolean get() = value == PIN_BLOCKED || remainingAttempts == 0

    override fun toString(): String = "%04X".format(value)

    companion object {
        const val SUCCESS = 0x9000
        const val PIN_BLOCKED = 0x6983
        const val SECURITY_NOT_SATISFIED = 0x6982
        const val FILE_NOT_FOUND = 0x6A82
        const val WRONG_LENGTH = 0x6700
    }
}

class CardException(message: String, val statusWord: StatusWord? = null) : Exception(message)

/** A response APDU: data plus status word. */
class Response(private val raw: ByteArray) {

    init {
        require(raw.size >= 2) { "response too short: ${raw.size} bytes" }
    }

    val statusWord: StatusWord =
        StatusWord(((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF))

    val data: ByteArray get() = raw.copyOfRange(0, raw.size - 2)

    fun requireSuccess(what: String): Response {
        if (!statusWord.isSuccess) {
            throw CardException("$what failed: SW=$statusWord", statusWord)
        }
        return this
    }
}

/**
 * Command APDU builders for the JPKI applet.
 *
 * Short APDUs only. `IsoDep.isExtendedLengthApduSupported()` is not dependable
 * across devices, so certificates are read in chunks instead.
 */
internal object Apdu {

    /** 公的個人認証AP. */
    val JPKI_AID = byteArrayOf(
        0xD3.toByte(), 0x92.toByte(), 0xF0.toByte(), 0x00, 0x26, 0x01, 0x00, 0x00, 0x00, 0x01,
    )

    /** SELECT by DF name. */
    fun selectApplication(aid: ByteArray = JPKI_AID): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, aid.size.toByte()) + aid

    /** SELECT EF under the current DF, by two-byte file identifier. */
    fun selectFile(fileId: Int): ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02,
        ((fileId shr 8) and 0xFF).toByte(), (fileId and 0xFF).toByte(),
    )

    /**
     * VERIFY with an empty data field: asks for the retry counter.
     *
     * Returns 63 Cx and does NOT decrement the counter, which is what makes it
     * safe to call before every real verify.
     *
     * This is a **case-1 APDU**: header only, with no Lc and no Le. Appending a
     * 00 byte makes the card read it as Le=0 and answer 6700 (wrong length),
     * which is what a real card did on the first attempt.
     */
    fun readRetryCounter(): ByteArray = byteArrayOf(0x00, 0x20, 0x00, 0x80.toByte())

    /** VERIFY. Every failed attempt decrements the card's counter. */
    fun verifyPin(pin: ByteArray): ByteArray =
        byteArrayOf(0x00, 0x20, 0x00, 0x80.toByte(), pin.size.toByte()) + pin

    /** READ BINARY at [offset], requesting [length] bytes (0 means 256). */
    fun readBinary(offset: Int, length: Int): ByteArray = byteArrayOf(
        0x00, 0xB0.toByte(),
        ((offset shr 8) and 0x7F).toByte(), (offset and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )

    /**
     * COMPUTE DIGITAL SIGNATURE over a PKCS#1 DigestInfo.
     *
     * The card applies PKCS#1 v1.5 padding to whatever it is handed and signs it,
     * so passing a bare hash would produce a structurally valid RSA signature
     * that no verifier accepts. It must be a DigestInfo.
     */
    fun computeSignature(digestInfo: ByteArray): ByteArray =
        byteArrayOf(0x80.toByte(), 0x2A, 0x00, 0x80.toByte(), digestInfo.size.toByte()) +
            digestInfo + byteArrayOf(0x00)
}
