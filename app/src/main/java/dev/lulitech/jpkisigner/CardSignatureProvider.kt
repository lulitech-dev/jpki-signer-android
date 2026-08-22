package dev.lulitech.jpkisigner

import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.pdf.SignatureProvider

/**
 * Bridges the card layer to the PDF layer.
 *
 * Lives in :app precisely so that :jpki and :pdf stay unaware of each other --
 * :jpki has no PDFBox, :pdf has no NFC, and only byte arrays cross here.
 *
 * The caller must have verified the PIN on [session] first: the 署名用 private key
 * will not sign without it, and the certificate may itself require it. Both
 * certificates are read once and cached, since a signing run asks for the signer
 * certificate more than once and each read is several NFC round trips.
 */
class CardSignatureProvider(
    private val session: JpkiSession,
    private val key: JpkiKey = JpkiKey.DIGITAL_SIGNATURE,
) : SignatureProvider {

    private val certificate: ByteArray by lazy { session.readCertificate(key) }
    private val caCertificate: ByteArray? by lazy { session.readCaCertificate(key) }

    override fun signerCertificate(): ByteArray = certificate

    override fun caCertificate(): ByteArray? = caCertificate

    override fun signDigestInfo(digestInfo: ByteArray): ByteArray =
        session.signDigestInfo(key, digestInfo)
}
