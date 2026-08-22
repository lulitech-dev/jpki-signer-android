package dev.lulitech.jpkisigner.jpki

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle

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
     * Starts reader mode. [onCard] runs on a binder thread, not the main thread,
     * and the connection is closed once it returns.
     */
    fun start(onCard: (JpkiSession) -> Unit) {
        val adapter = adapter ?: return
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
        }
        adapter.enableReaderMode(
            activity,
            { tag: Tag -> handle(tag, onCard) },
            NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    private fun handle(tag: Tag, onCard: (JpkiSession) -> Unit) {
        val isoDep = IsoDep.get(tag) ?: return
        isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
        try {
            isoDep.connect()
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
