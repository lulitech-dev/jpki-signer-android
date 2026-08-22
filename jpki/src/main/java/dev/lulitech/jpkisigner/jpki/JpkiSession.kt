package dev.lulitech.jpkisigner.jpki

/**
 * One conversation with the JPKI applet, over an already-connected card.
 *
 * Deliberately has no retry logic anywhere near [verifyPin]. If a card leaves the
 * field mid-VERIFY we cannot know whether the card counted the attempt, so a
 * naive retry can spend two attempts on one user action. Session-level retries
 * belong above this class, never around a verify.
 */
class JpkiSession(private val io: ApduTransceiver) {

    private var applicationSelected = false

    /** SELECT the 公的個人認証AP. Idempotent within a session. */
    fun selectApplication() {
        if (applicationSelected) return
        send(Apdu.selectApplication()).requireSuccess("SELECT 公的個人認証AP")
        applicationSelected = true
    }

    /**
     * Remaining PIN attempts for [key], read without spending one.
     *
     * A successful VERIFY resets this to [JpkiKey.maxAttempts]; only failures
     * decrement it.
     */
    fun remainingAttempts(key: JpkiKey): Int {
        selectApplication()
        send(Apdu.selectFile(key.pinEf)).requireSuccess("SELECT PIN EF")
        val response = send(Apdu.readRetryCounter())
        return response.statusWord.remainingAttempts
            ?: if (response.statusWord.isSuccess) {
                key.maxAttempts
            } else {
                throw CardException(
                    "could not read retry counter: SW=${response.statusWord}",
                    response.statusWord,
                )
            }
    }

    /**
     * Verifies [pin] for [key]. Exactly one attempt, no retries.
     *
     * Validates the PIN format first so malformed input never reaches the card.
     * On success the card's counter resets to full.
     *
     * @throws CardException on a wrong PIN, with the remaining count if known.
     */
    fun verifyPin(key: JpkiKey, pin: CharArray) {
        key.validatePin(pin)?.let { throw IllegalArgumentException(it) }

        selectApplication()
        send(Apdu.selectFile(key.pinEf)).requireSuccess("SELECT PIN EF")

        val encoded = key.encodePin(pin)
        // Deliberately the raw send: the generic wrapper's "lost contact" message
        // would be wrong here, because losing the link mid-VERIFY has a
        // consequence the user needs to know about.
        val response = try {
            sendRaw(Apdu.verifyPin(encoded))
        } catch (e: java.io.IOException) {
            // The card left the field, or the link broke, at the one moment where
            // we cannot tell what happened: the card may or may not have
            // processed the VERIFY and decremented its counter. Do NOT retry --
            // that is how one user action becomes two spent attempts. Say so
            // plainly and let the next tap read the real counter.
            throw CardException(
                "lost contact with the card during PIN verification; " +
                    "the attempt may or may not have been counted. " +
                    "Re-read the remaining attempts before trying again.",
            )
        } finally {
            encoded.fill(0)
        }

        if (response.statusWord.isSuccess) return

        val remaining = response.statusWord.remainingAttempts
        throw CardException(
            when {
                response.statusWord.isPinBlocked ->
                    "PIN is blocked; it must be reset at a municipal window"
                remaining != null -> "wrong PIN; $remaining attempt(s) remaining"
                else -> "PIN verification failed: SW=${response.statusWord}"
            },
            response.statusWord,
        )
    }

    /** Reads the certificate for [key]. The 署名用 certificate requires a prior verify. */
    fun readCertificate(key: JpkiKey): ByteArray = readFile(key.certificateEf)

    /** Reads the issuing CA certificate for [key], or null if the EF is absent. */
    fun readCaCertificate(key: JpkiKey): ByteArray? =
        try {
            readFile(key.caCertificateEf)
        } catch (e: CardException) {
            if (e.statusWord?.value == StatusWord.FILE_NOT_FOUND) null else throw e
        }

    /**
     * Signs [digestInfo] with [key]'s private key. Requires a prior [verifyPin].
     *
     * @param digestInfo DER-encoded PKCS#1 DigestInfo, not a bare hash.
     * @return the raw RSA signature, 256 bytes for the card's 2048-bit keys.
     */
    fun signDigestInfo(key: JpkiKey, digestInfo: ByteArray): ByteArray {
        selectApplication()
        send(Apdu.selectFile(key.keyEf)).requireSuccess("SELECT key EF")
        return send(Apdu.computeSignature(digestInfo))
            .requireSuccess("COMPUTE DIGITAL SIGNATURE")
            .data
    }

    /**
     * Reads a whole DER file in short-APDU chunks.
     *
     * The total length comes from the ASN.1 header rather than from reading until
     * the card complains, so we ask for exactly what is there.
     */
    private fun readFile(fileId: Int): ByteArray {
        selectApplication()
        send(Apdu.selectFile(fileId)).requireSuccess("SELECT EF %04X".format(fileId))

        val header = send(Apdu.readBinary(0, HEADER_PROBE)).requireSuccess("READ BINARY header").data
        val total = derTotalLength(header)
            ?: throw CardException("EF %04X is not a DER object".format(fileId))

        val out = ByteArray(total)
        var read = minOf(header.size, total)
        header.copyInto(out, 0, 0, read)

        while (read < total) {
            val want = minOf(CHUNK, total - read)
            val chunk = send(Apdu.readBinary(read, want))
                .requireSuccess("READ BINARY at $read")
                .data
            if (chunk.isEmpty()) throw CardException("short read at offset $read")
            chunk.copyInto(out, read, 0, minOf(chunk.size, total - read))
            read += chunk.size
        }
        return out
    }

    /**
     * Sends one command.
     *
     * A dropped link surfaces as [CardException] rather than a raw
     * [java.io.IOException], except in [verifyPin], which needs to say something
     * more careful. Nothing here retries: a retry after an ambiguous failure can
     * spend a second PIN attempt on a single user action.
     */
    private fun sendRaw(command: ByteArray): Response = Response(io.transceive(command))

    private fun send(command: ByteArray): Response =
        try {
            sendRaw(command)
        } catch (e: java.io.IOException) {
            throw CardException(
                "lost contact with the card; hold it still against the phone and try again",
            )
        }

    private companion object {
        const val CHUNK = 0xFF
        const val HEADER_PROBE = 0x08

        /**
         * Total encoded length of a DER object, header included, from its first
         * bytes. Certificates are long-form (81/82), but short form is handled
         * too rather than assumed away.
         */
        fun derTotalLength(header: ByteArray): Int? {
            if (header.size < 2) return null
            val first = header[1].toInt() and 0xFF
            if (first < 0x80) return 2 + first
            val lengthBytes = first and 0x7F
            if (lengthBytes == 0 || lengthBytes > 4 || header.size < 2 + lengthBytes) return null
            var length = 0
            for (i in 0 until lengthBytes) {
                length = (length shl 8) or (header[2 + i].toInt() and 0xFF)
            }
            return 2 + lengthBytes + length
        }
    }
}
