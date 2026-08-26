package dev.lulitech.jpkisigner.pdf

/**
 * The boundary between [dev.lulitech.jpkisigner.pdf] and the card layer.
 *
 * Only byte arrays cross it — see DESIGN.md §3. `:jpki` implements this against a
 * real My Number Card over NFC; tests implement it with a software RSA key, which
 * is what lets the whole PDF/CMS layer be verified with no hardware.
 */
interface SignatureProvider {

    /** DER-encoded X.509 signer certificate (署名用電子証明書). */
    fun signerCertificate(): ByteArray

    /** DER-encoded issuing CA certificate, or null if unavailable. */
    fun caCertificate(): ByteArray?

    /**
     * RSASSA-PKCS1-v1_5 over [digestInfo], returning the raw signature.
     *
     * [digestInfo] is a DER-encoded PKCS#1 `DigestInfo` (SHA-256 OID + hash) —
     * exactly the payload the card's COMPUTE DIGITAL SIGNATURE command takes.
     * Implementations must not hash it again.
     */
    fun signDigestInfo(digestInfo: ByteArray): ByteArray
}
