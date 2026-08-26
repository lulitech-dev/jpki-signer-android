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

    /**
     * Bytes the card is still holding when it answers `61 xx`, else null.
     *
     * The card has the data but did not send it, and expects a GET RESPONSE. A
     * count of zero means 256, as it does everywhere else a length byte appears.
     */
    val bytesAvailable: Int? get() =
        if (value and 0xFF00 == MORE_DATA) (value and 0xFF).let { if (it == 0) 0x100 else it } else null

    /**
     * The Le the card wants when it answers `6C xx`, else null.
     *
     * It rejected the length we asked for and named the right one, so the same
     * command reissued with that Le succeeds.
     */
    val expectedLength: Int? get() =
        if (value and 0xFF00 == WRONG_LE) (value and 0xFF).let { if (it == 0) 0x100 else it } else null

    override fun toString(): String = "%04X".format(value)

    companion object {
        const val SUCCESS = 0x9000
        const val PIN_BLOCKED = 0x6983
        const val SECURITY_NOT_SATISFIED = 0x6982
        const val FILE_NOT_FOUND = 0x6A82
        const val WRONG_LENGTH = 0x6700

        /** `61 xx`: the low byte counts the bytes still waiting on the card. */
        const val MORE_DATA = 0x6100

        /** `6C xx`: the low byte is the Le the card will accept. */
        const val WRONG_LE = 0x6C00
    }
}

/**
 * What went wrong with the card, as data rather than as a message.
 *
 * This module has no resources and no locale, and its exception messages used to
 * reach the screen verbatim -- English text in an app whose primary language is
 * Japanese. The problem travels as a value so `:app` can resolve it against
 * `strings.xml`, which is the only place translations live.
 */
sealed interface CardProblem {

    /** The link dropped outside a VERIFY, so no attempt was spent. */
    data object LostContact : CardProblem

    /**
     * The link dropped *during* a VERIFY. The card may or may not have counted
     * the attempt, and there is no way to tell from here. Never presented as
     * either a wrong PIN or a no-op.
     */
    data object VerifyOutcomeUnknown : CardProblem

    /** The PIN is blocked; only a municipal window can reset it. */
    data object PinBlocked : CardProblem

    /** Wrong PIN. [remainingAttempts] is null when the card did not say. */
    data class WrongPin(val remainingAttempts: Int?) : CardProblem

    /**
     * Something answered, but it is not a card this app can use: SELECT of the
     * 公的個人認証AP was refused, or the tag does not speak ISO-DEP at all.
     *
     * Its own case rather than a [CommandFailed] on SELECT, because "hold a
     * different card" is the one thing the user can act on, and no PIN attempt
     * can have been spent reaching it.
     */
    data object NotJpkiCard : CardProblem

    /** A command answered with a status word we cannot act on. */
    data class CommandFailed(val what: String, val statusWord: StatusWord?) : CardProblem

    /**
     * The card answered with something that is not a response APDU at all: fewer
     * bytes than the trailing status word takes.
     *
     * Its own case rather than an `IllegalArgumentException`, which is what a
     * bare `require` used to raise here. That escaped past the IOException
     * handling every send site has, so a card or a reader stack that returned a
     * stub reached the screen as untranslated English through the unforeseen-
     * failure path -- in the one module whose entire error design exists to stop
     * that happening.
     */
    data object MalformedResponse : CardProblem

    /** A file on the card did not hold what it should. */
    data class Unreadable(val what: String) : CardProblem
}

/**
 * @param problem the machine-readable cause, for the UI to translate.
 * @param message developer-facing English, for logs and the debug screen. Never
 *   put in front of a user -- resolve [problem] against string resources instead.
 */
class CardException(
    val problem: CardProblem,
    message: String,
    val statusWord: StatusWord? = null,
) : Exception(message)

/** A response APDU: data plus status word. */
class Response(private val raw: ByteArray) {

    init {
        if (raw.size < 2) {
            throw CardException(
                CardProblem.MalformedResponse,
                "response too short to carry a status word: ${raw.size} bytes",
            )
        }
    }

    val statusWord: StatusWord =
        StatusWord(((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF))

    val data: ByteArray get() = raw.copyOfRange(0, raw.size - 2)

    fun requireSuccess(what: String): Response {
        if (!statusWord.isSuccess) {
            throw CardException(
                CardProblem.CommandFailed(what, statusWord),
                "$what failed: SW=$statusWord",
                statusWord,
            )
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

    /** Largest data field a short APDU can carry, i.e. what Lc can express. */
    const val MAX_DATA_LENGTH = 0xFF

    /** SELECT by DF name. */
    fun selectApplication(aid: ByteArray = JPKI_AID): ByteArray {
        require(aid.size in 1..MAX_DATA_LENGTH) { "AID length out of range: ${aid.size}" }
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, aid.size.toByte()) + aid
    }

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

    /**
     * VERIFY. Every failed attempt decrements the card's counter.
     *
     * The returned command carries the PIN in the clear after its header, so the
     * caller must zero it once the exchange is over. Wiping only the array passed
     * in here would leave this second copy on the heap.
     */
    fun verifyPin(pin: ByteArray): ByteArray {
        // A wrapped length would send a truncated PIN and spend an attempt on it,
        // so the bound is checked rather than left to the callers to respect.
        require(pin.size in 1..MAX_DATA_LENGTH) { "PIN length out of range: ${pin.size}" }
        return byteArrayOf(0x00, 0x20, 0x00, 0x80.toByte(), pin.size.toByte()) + pin
    }

    /** Largest offset a short-EF READ BINARY can address. */
    const val MAX_OFFSET = 0x7FFF

    /**
     * READ BINARY at [offset], requesting [length] bytes (0 means 256).
     *
     * Bit 8 of P1 selects short-EF addressing, so only 15 bits are left for the
     * offset. Masking a larger one would silently re-read from near the start of
     * the file and return plausible-looking wrong bytes, so it is rejected --
     * callers bound the read length instead.
     */
    fun readBinary(offset: Int, length: Int): ByteArray {
        require(offset in 0..MAX_OFFSET) { "READ BINARY offset out of range: $offset" }
        return byteArrayOf(
            0x00, 0xB0.toByte(),
            ((offset shr 8) and 0x7F).toByte(), (offset and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
    }

    /**
     * GET RESPONSE, collecting the [length] bytes a `61 xx` said were waiting.
     *
     * Case-2 APDU: header plus Le. A length of 256 is expressed as Le=0, which is
     * what the masking below produces.
     */
    fun getResponse(length: Int): ByteArray = byteArrayOf(
        0x00, 0xC0.toByte(), 0x00, 0x00, (length and 0xFF).toByte(),
    )

    /**
     * COMPUTE DIGITAL SIGNATURE over a PKCS#1 DigestInfo.
     *
     * The card applies PKCS#1 v1.5 padding to whatever it is handed and signs it,
     * so passing a bare hash would produce a structurally valid RSA signature
     * that no verifier accepts. It must be a DigestInfo.
     */
    fun computeSignature(digestInfo: ByteArray): ByteArray {
        require(digestInfo.size in 1..MAX_DATA_LENGTH) {
            "DigestInfo length out of range: ${digestInfo.size}"
        }
        return byteArrayOf(0x80.toByte(), 0x2A, 0x00, 0x80.toByte(), digestInfo.size.toByte()) +
            digestInfo + byteArrayOf(0x00)
    }
}
