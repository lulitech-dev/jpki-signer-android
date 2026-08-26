package dev.lulitech.jpkisigner.jpki

/**
 * Why a PIN is malformed, as data rather than as a message.
 *
 * Same reason as [CardProblem]: this module cannot translate, and these strings
 * used to be shown verbatim under the PIN field. `:app` resolves them.
 */
sealed interface PinProblem {

    /** Outside the key's accepted length. [minLength] == [maxLength] when fixed. */
    data class WrongLength(val minLength: Int, val maxLength: Int) : PinProblem

    /** The 認証用 PIN is digits only. */
    data object NotDigits : PinProblem

    /** The 署名用 PIN is digits and uppercase letters only. */
    data object NotUppercaseAlphanumeric : PinProblem
}

/**
 * The two key pairs on the 公的個人認証AP.
 *
 * EF identifiers cross-checked against independent public documentation of the
 * applet. The CA-certificate identifiers are the one part still unconfirmed --
 * see [caCertificateEf].
 */
enum class JpkiKey(
    val certificateEf: Int,
    val caCertificateEf: Int,
    val pinEf: Int,
    val keyEf: Int,
    val pinMinLength: Int,
    val pinMaxLength: Int,
    val maxAttempts: Int,
) {
    /**
     * 認証用 -- 4-digit PIN, 3 attempts.
     *
     * Signs with the same COMPUTE DIGITAL SIGNATURE command as the signature key,
     * so the whole APDU path can be developed here first. A lockout is far
     * cheaper to recover from than the signature PIN's, which is why every new
     * card interaction is proven against this key before switching over.
     */
    AUTHENTICATION(
        certificateEf = 0x000A,
        caCertificateEf = 0x000B,
        pinEf = 0x0018,
        keyEf = 0x0017,
        pinMinLength = 4,
        pinMaxLength = 4,
        maxAttempts = 3,
    ),

    /**
     * 署名用 -- 6 to 16 uppercase alphanumerics, 5 attempts.
     *
     * The only key acceptable for 法務省 filings. A lockout requires a PIN reset
     * at the municipal window, so this key is only ever touched by code already
     * proven against [AUTHENTICATION].
     */
    DIGITAL_SIGNATURE(
        certificateEf = 0x0001,
        caCertificateEf = 0x0002,
        pinEf = 0x001B,
        keyEf = 0x001A,
        pinMinLength = 6,
        pinMaxLength = 16,
        maxAttempts = 5,
    ),
    ;

    /**
     * Rejects malformed PINs before any APDU is sent.
     *
     * The card cannot tell a mistyped PIN from a wrongly encoded one, so both
     * cost an attempt. Everything checkable offline is checked offline.
     */
    fun validatePin(pin: CharArray): PinProblem? {
        if (pin.size < pinMinLength || pin.size > pinMaxLength) {
            return PinProblem.WrongLength(pinMinLength, pinMaxLength)
        }
        return when (this) {
            AUTHENTICATION ->
                if (pin.all { it in '0'..'9' }) null else PinProblem.NotDigits
            DIGITAL_SIGNATURE ->
                // The card expects uppercase ASCII. Lowercase would be encoded
                // faithfully and rejected, burning an attempt.
                if (pin.all { it in '0'..'9' || it in 'A'..'Z' }) {
                    null
                } else {
                    PinProblem.NotUppercaseAlphanumeric
                }
        }
    }

    /** ASCII encoding, as the card expects. Caller must zero the result. */
    fun encodePin(pin: CharArray): ByteArray = ByteArray(pin.size) { pin[it].code.toByte() }
}
