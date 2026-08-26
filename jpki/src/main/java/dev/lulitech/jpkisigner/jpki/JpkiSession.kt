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
        val response = send(Apdu.selectApplication())
        if (!response.statusWord.isSuccess) {
            // Not a generic CommandFailed: a card that answers but refuses this
            // SELECT is simply not a My Number Card, and "hold a different card"
            // is the only thing the user can do about it. Nothing has been asked
            // of a PIN at this point, so no attempt can have been spent.
            throw CardException(
                CardProblem.NotJpkiCard,
                "SELECT 公的個人認証AP failed: SW=${response.statusWord}",
                response.statusWord,
            )
        }
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
                    CardProblem.CommandFailed("read retry counter", response.statusWord),
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
        // A programming error rather than a user error: callers validate first,
        // precisely so a malformed PIN never costs an attempt.
        key.validatePin(pin)?.let { throw IllegalArgumentException("malformed PIN: $it") }

        selectApplication()
        send(Apdu.selectFile(key.pinEf)).requireSuccess("SELECT PIN EF")

        val encoded = key.encodePin(pin)
        // Built into a local so it can be wiped too. It embeds the PIN after its
        // header, so zeroing only `encoded` would leave a second cleartext copy
        // on the heap for as long as the collector took to reach it.
        //
        // Built *inside* the try. Assembling the command bounds the PIN length a
        // second time, and a throw there sat outside the only code that wipes
        // `encoded` -- leaving on the heap the very copy the finally exists for.
        var command: ByteArray? = null
        // Deliberately the raw send: the generic wrapper's "lost contact" message
        // would be wrong here, because losing the link mid-VERIFY has a
        // consequence the user needs to know about.
        val response = try {
            command = Apdu.verifyPin(encoded)
            sendRaw(command)
        } catch (e: java.io.IOException) {
            // The card left the field, or the link broke, at the one moment where
            // we cannot tell what happened: the card may or may not have
            // processed the VERIFY and decremented its counter. Do NOT retry --
            // that is how one user action becomes two spent attempts. Say so
            // plainly and let the next tap read the real counter.
            throw verifyOutcomeUnknown("lost contact with the card")
        } catch (e: CardException) {
            // The only thing [sendRaw] itself raises is a reply too short to hold
            // a status word. Something answered, so the VERIFY may well have been
            // processed, but with no status word there is no way to tell a success
            // from a wrong PIN -- which is the same position a dropped link leaves
            // us in, and deserves the same careful answer rather than a message
            // that says nothing about the counter.
            if (e.problem !is CardProblem.MalformedResponse) throw e
            throw verifyOutcomeUnknown("the card's reply carried no status word")
        } finally {
            encoded.fill(0)
            command?.fill(0)
        }

        if (response.statusWord.isSuccess) return

        val remaining = response.statusWord.remainingAttempts
        val problem = when {
            response.statusWord.isPinBlocked -> CardProblem.PinBlocked
            remaining != null -> CardProblem.WrongPin(remaining)
            else -> CardProblem.CommandFailed("PIN verification", response.statusWord)
        }
        throw CardException(
            problem,
            when (problem) {
                CardProblem.PinBlocked ->
                    "PIN is blocked; it must be reset at a municipal window"
                is CardProblem.WrongPin -> "wrong PIN; $remaining attempt(s) remaining"
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
            // Only a missing *file* means "absent". A card that refuses the
            // 公的個人認証AP answers 6A82 to the SELECT as well, and matching on the
            // status word alone would read that as "this card has no CA
            // certificate" -- quietly dropping it from a signature made on a card
            // we had no business talking to.
            if (e.problem is CardProblem.CommandFailed &&
                e.statusWord?.value == StatusWord.FILE_NOT_FOUND
            ) {
                null
            } else {
                throw e
            }
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
        return sendForData(Apdu.computeSignature(digestInfo), "COMPUTE DIGITAL SIGNATURE")
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

        val header = sendForData(Apdu.readBinary(0, HEADER_PROBE), "READ BINARY header")
        val total = derTotalLength(header)
            ?: throw CardException(
                CardProblem.Unreadable("EF %04X".format(fileId)),
                "EF %04X is not a DER object of a readable size".format(fileId),
            )

        val out = ByteArray(total)
        var read = minOf(header.size, total)
        header.copyInto(out, 0, 0, read)

        while (read < total) {
            val want = minOf(CHUNK, total - read)
            val chunk = sendForData(Apdu.readBinary(read, want), "READ BINARY at $read")
            if (chunk.isEmpty()) {
                throw CardException(
                    CardProblem.Unreadable("EF %04X".format(fileId)),
                    "short read at offset $read",
                )
            }
            chunk.copyInto(out, read, 0, minOf(chunk.size, total - read))
            read += chunk.size
        }
        return out
    }

    /**
     * Sends a command that expects data back, following the two status words that
     * mean "ask again" rather than "failed".
     *
     * `6C xx` says our Le was wrong and names the right one; the same command
     * reissued with it succeeds. `61 xx` says the card is holding data it did not
     * send and wants a GET RESPONSE. Neither is a failure, but both used to reach
     * [Response.requireSuccess] and be reported as one -- and for
     * COMPUTE DIGITAL SIGNATURE that lands *after* the VERIFY, i.e. after an
     * attempt has been spent, which is the outcome this whole flow exists to
     * avoid.
     *
     * Deliberately not used by [verifyPin]: neither status word can follow a
     * VERIFY, and nothing near that command may reissue anything.
     *
     * @param what developer-facing name of the command, for the failure it raises.
     */
    private fun sendForData(command: ByteArray, what: String): ByteArray {
        var response = send(command)

        // 6C xx: reissue once, with the length the card just named. The Le is the
        // last byte of every command that reaches here.
        response.statusWord.expectedLength?.let { le ->
            val corrected = command.copyOf()
            corrected[corrected.lastIndex] = le.toByte()
            response = send(corrected)
        }

        val parts = mutableListOf(response.data)
        var status = response.statusWord

        // 61 xx: collect what is waiting. Bounded rather than trusting the card to
        // stop asking -- the count driving this loop comes from the card itself.
        var rounds = 0
        while (true) {
            val waiting = status.bytesAvailable ?: break
            if (++rounds > MAX_GET_RESPONSE_ROUNDS) {
                throw CardException(
                    CardProblem.Unreadable(what),
                    "$what: the card asked for more than $MAX_GET_RESPONSE_ROUNDS GET RESPONSEs",
                )
            }
            val next = send(Apdu.getResponse(waiting))
            parts += next.data
            status = next.statusWord
        }

        if (!status.isSuccess) {
            throw CardException(
                CardProblem.CommandFailed(what, status),
                "$what failed: SW=$status",
                status,
            )
        }

        if (parts.size == 1) return parts[0]
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
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

    /**
     * The one honest thing to say when a VERIFY produced no readable outcome: the
     * attempt may or may not have been counted, so the next tap must re-read the
     * counter rather than anything here guessing at it.
     */
    private fun verifyOutcomeUnknown(cause: String): CardException = CardException(
        CardProblem.VerifyOutcomeUnknown,
        "$cause during PIN verification; the attempt may or may not have been " +
            "counted. Re-read the remaining attempts before trying again.",
    )

    private fun send(command: ByteArray): Response =
        try {
            sendRaw(command)
        } catch (e: java.io.IOException) {
            throw CardException(
                CardProblem.LostContact,
                "lost contact with the card; hold it still against the phone and try again",
            )
        }

    private companion object {
        const val CHUNK = 0xFF
        const val HEADER_PROBE = 0x08

        /**
         * How many GET RESPONSEs one command may chain into.
         *
         * Every command here asks for at most 256 bytes, so a well-behaved card
         * needs one round at the very most. The bound exists because the loop is
         * driven by a status word the *card* chooses, and a card that keeps
         * answering 61 xx would otherwise spin against a user's phone forever.
         */
        const val MAX_GET_RESPONSE_ROUNDS = 8

        /**
         * Largest EF this will read.
         *
         * The card's certificates are around 1.5 KB, so this is generous, and it
         * stays inside [Apdu.MAX_OFFSET] where a short-EF READ BINARY offset is
         * still addressable.
         *
         * A cap is required, not tidiness: the length below is read *from the
         * card*, and a corrupt or hostile EF declaring 0x7FFFFFFF would otherwise
         * overflow the total into a negative array size, while a merely large one
         * would ask for tens of thousands of NFC round trips.
         */
        const val MAX_FILE_BYTES = 0x4000

        /**
         * Total encoded length of a DER object, header included, from its first
         * bytes. Certificates are long-form (81/82), but short form is handled
         * too rather than assumed away.
         *
         * @return null when the header is not a DER length, or declares more than
         *   [MAX_FILE_BYTES] -- both of which the caller reports as unreadable.
         */
        fun derTotalLength(header: ByteArray): Int? {
            if (header.size < 2) return null
            val first = header[1].toInt() and 0xFF
            if (first < 0x80) return 2 + first
            val lengthBytes = first and 0x7F
            if (lengthBytes == 0 || lengthBytes > 4 || header.size < 2 + lengthBytes) return null
            // Long, because four length bytes reach past Int.MAX_VALUE and the
            // overflow would turn a bogus length into a negative size that the
            // bound below could not catch.
            var length = 0L
            for (i in 0 until lengthBytes) {
                length = (length shl 8) or (header[2 + i].toLong() and 0xFF)
            }
            val total = 2L + lengthBytes + length
            return if (total > MAX_FILE_BYTES) null else total.toInt()
        }
    }
}
