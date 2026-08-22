package dev.lulitech.jpkisigner.jpki

/**
 * PKCS#1 DigestInfo construction.
 *
 * The card applies PKCS#1 v1.5 padding to whatever it is handed and signs it, so
 * handing it a bare hash yields a structurally valid RSA signature that no
 * verifier accepts. It must receive a DigestInfo.
 *
 * Hardcoded rather than built with an ASN.1 library, because :jpki deliberately
 * has no BouncyCastle dependency. A test cross-checks the bytes against
 * BouncyCastle's encoder so the shortcut cannot silently drift.
 */
object DigestInfo {

    /**
     * DER for `SEQUENCE { SEQUENCE { OID 2.16.840.1.101.3.4.2.1, NULL }, OCTET STRING (32) }`
     * -- everything ahead of the SHA-256 digest itself.
     */
    private val SHA256_PREFIX = byteArrayOf(
        0x30, 0x31,
        0x30, 0x0D,
        0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01,
        0x05, 0x00,
        0x04, 0x20,
    )

    const val SHA256_LENGTH = 32

    fun forSha256(digest: ByteArray): ByteArray {
        require(digest.size == SHA256_LENGTH) {
            "SHA-256 digest must be $SHA256_LENGTH bytes, got ${digest.size}"
        }
        return SHA256_PREFIX + digest
    }
}
