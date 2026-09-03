package dev.lulitech.jpkisigner.pdf

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.SignerInfoGenerator
import org.bouncycastle.cms.SignerInfoGeneratorBuilder
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.util.CollectionStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Builds the detached CMS SignedData that goes into the PDF's /Contents.
 *
 * Everything here is pure BouncyCastle — no JCE provider is registered and no
 * [java.security.Signature] is obtained. See DESIGN.md §5.1 for why: Android
 * squats the provider name "BC" with a stripped fork, so `Security.addProvider`
 * is a silent no-op and `.setProvider("BC")` would route to it.
 */
internal object CmsBuilder {

    private val SHA256_ALG = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE)
    private val SHA256_WITH_RSA =
        AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)

    /**
     * @param content the PDF bytes covered by the signature's ByteRange.
     * @return BER-encoded CMS SignedData, detached (content not embedded).
     */
    fun buildDetached(content: InputStream, provider: SignatureProvider): ByteArray {
        val signerCert = X509CertificateHolder(provider.signerCertificate())
        val chain = buildList {
            add(signerCert)
            provider.caCertificate()?.let { add(X509CertificateHolder(it)) }
        }

        // BouncyCastle's default signed attributes: contentType, messageDigest,
        // signingTime and cmsAlgorithmProtect -- the same four, in the same order,
        // as the reference implementation produces. BouncyCastle handles the DER SET OF vs [0] IMPLICIT tagging difference
        // between hashing signedAttrs and embedding them in the SignerInfo,
        // which is the classic source of signatures that encode fine and
        // verify nowhere.
        val signerInfo: SignerInfoGenerator =
            SignerInfoGeneratorBuilder(BcDigestCalculatorProvider())
                .build(CardContentSigner(provider), signerCert)

        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(signerInfo)
            addCertificates(CollectionStore(chain))
        }

        // `getEncoded()`, not `getEncoded(DER)`, which lands on BER: an
        // indefinite-length SEQUENCE (30 80) terminated by end-of-contents
        // octets. That is deliberately the *less* standards-correct choice and it
        // reverses what this line used to do -- see DESIGN.md §5.1a for the whole
        // argument. In short: it is byte-for-byte what jpki-pdf-signer emits, that
        // implementation is accepted by the filing system, and ours was not.
        //
        // The reason originally given for DER does not survive measurement.
        // Padding was the worry -- PDFBox zero-pads /Contents to the reserved
        // size, so a verifier is handed the object followed by junk -- but that
        // affects both encodings identically: `ASN1Primitive.fromByteArray` on the
        // padded buffer throws "Extra data detected in stream" for DER *and* BER,
        // while `CMSSignedData(byte[])`, `ASN1InputStream`, openssl and
        // poppler/NSS all accept both. No verifier tested tells them apart.
        return generator.generate(StreamedContent(content), false)
            .getEncoded()
    }

    /**
     * Hands the signed attributes to the card and returns its raw RSA signature.
     *
     * BouncyCastle streams the DER-encoded signedAttrs into [getOutputStream],
     * then calls [getSignature]. We hash those bytes, wrap them in a PKCS#1
     * DigestInfo, and that is precisely what the card signs.
     */
    private class CardContentSigner(
        private val provider: SignatureProvider,
    ) : ContentSigner {

        private val buffer = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier = SHA256_WITH_RSA

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray {
            val signedAttrs = buffer.toByteArray()
            val sha256 = SHA256Digest()
            val digest = ByteArray(sha256.digestSize)
            sha256.update(signedAttrs, 0, signedAttrs.size)
            sha256.doFinal(digest, 0)
            val digestInfo = DigestInfo(SHA256_ALG, digest).encoded
            return provider.signDigestInfo(digestInfo)
        }
    }

    /** Streams the covered PDF bytes into the CMS generator without buffering them all. */
    private class StreamedContent(private val input: InputStream) : CMSTypedData {
        override fun getContentType(): ASN1ObjectIdentifier = PKCSObjectIdentifiers.data
        override fun getContent(): Any = input
        override fun write(out: OutputStream) {
            input.copyTo(out)
        }
    }
}
