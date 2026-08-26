package dev.lulitech.jpkisigner.jpki

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import java.io.IOException

/** [ApduTransceiver] backed by an NFC ISO-DEP connection. */
class IsoDepTransceiver(private val isoDep: IsoDep) : ApduTransceiver {
    override fun transceive(command: ByteArray): ByteArray = isoDep.transceive(command)
}

/**
 * Reader-mode helper for My Number Cards.
 *
 * The card is Type B, and it carries no NDEF, so NDEF discovery is skipped -- it
 * would only add latency and can disturb the connection.
 */
class NfcCardReader(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Starts reader mode. Both callbacks run on a binder thread, not the main
     * thread, and the connection is closed once [onCard] returns.
     *
     * @param onCard a card this app can talk to is in the field.
     * @param onUnusable a tag was discovered but no session could be opened on it.
     *   Reported rather than dropped: a tap that silently does nothing leaves an
     *   armed run waiting for a card that already came and went.
     */
    fun start(onCard: (JpkiSession) -> Unit, onUnusable: (CardProblem) -> Unit) {
        val adapter = adapter ?: return
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
        }
        adapter.enableReaderMode(
            activity,
            { tag: Tag -> handle(tag, onCard, onUnusable) },
            NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    /**
     * Opens a session on [tag] and hands it to [onCard].
     *
     * Nothing may throw out of here. This runs on an NFC binder thread, where an
     * escaping exception reaches no handler that can report it, and
     * `IsoDep.connect` throws `IOException` for the most ordinary thing a user
     * does -- holding the card slightly off the antenna. It is a checked
     * exception, so Kotlin let it straight out, past every guard the signing run
     * puts around itself.
     */
    private fun handle(
        tag: Tag,
        onCard: (JpkiSession) -> Unit,
        onUnusable: (CardProblem) -> Unit,
    ) {
        // Not ISO-DEP at all: a transit pass, a hotel key, an NDEF tag. Nothing
        // to connect to, so this is as far as it goes.
        val isoDep = IsoDep.get(tag) ?: return onUnusable(CardProblem.NotJpkiCard)

        isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
        try {
            isoDep.connect()
        } catch (e: IOException) {
            runCatching { isoDep.close() }
            // Nothing was sent, so nothing was spent. The card was simply not held
            // where it could be read.
            return onUnusable(CardProblem.LostContact)
        }

        try {
            onCard(JpkiSession(IsoDepTransceiver(isoDep)))
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private companion object {
        /**
         * Generous, because a signing session involves several round trips and an
         * aggressive presence check can drop a card mid-conversation.
         */
        const val PRESENCE_CHECK_DELAY_MS = 1000

        /** Card RSA operations are slow; the default 300 ms is not enough. */
        const val TRANSCEIVE_TIMEOUT_MS = 20_000
    }
}
