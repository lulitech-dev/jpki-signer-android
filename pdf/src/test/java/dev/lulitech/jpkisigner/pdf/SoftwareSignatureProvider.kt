package dev.lulitech.jpkisigner.pdf

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Date
import javax.crypto.Cipher

/**
 * Stands in for a My Number Card so the whole PDF/CMS layer can be tested with no
 * hardware. See PLAN.md §3 — the card only ever hands over a certificate and
 * signs a DigestInfo, and both are reproducible in software.
 *
 * This is test-only code, so it may use JCA freely for fixture generation. The
 * production path stays provider-free (PLAN.md §5.1).
 */
class SoftwareSignatureProvider(
    commonName: String = "署名 太郎",
    private val keyPair: KeyPair = generateKeyPair(),
) : SignatureProvider {

    private val certificate: ByteArray = selfSign(commonName, keyPair)

    override fun signerCertificate(): ByteArray = certificate

    /** A real card also returns the issuing CA certificate; a self-signed fixture has none. */
    override fun caCertificate(): ByteArray? = null

    /**
     * Exactly what the card's COMPUTE DIGITAL SIGNATURE does: raw RSA with PKCS#1
     * v1.5 padding over an already-formed DigestInfo. No second hashing.
     */
    override fun signDigestInfo(digestInfo: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, keyPair.private)
            doFinal(digestInfo)
        }

    private companion object {

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun selfSign(commonName: String, keyPair: KeyPair): ByteArray {
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()
            return JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - 86_400_000L),
                Date(now + 3_650L * 86_400_000L),
                name,
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)).encoded
        }
    }
}
