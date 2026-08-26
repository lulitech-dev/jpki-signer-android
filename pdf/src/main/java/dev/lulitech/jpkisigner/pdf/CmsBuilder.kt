package dev.lulitech.jpkisigner.pdf

import org.bouncycastle.asn1.ASN1Encoding
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
     * @return DER-encoded CMS SignedData, detached (content not embedded).
     */
    fun buildDetached(content: InputStream, provider: SignatureProvider): ByteArray {
        val signerCert = X509CertificateHolder(provider.signerCertificate())
        val chain = buildList {
            add(signerCert)
            provider.caCertificate()?.let { add(X509CertificateHolder(it)) }
        }

        // Default signed attributes: contentType, messageDigest, signingTime.
        // BouncyCastle handles the DER SET OF vs [0] IMPLICIT tagging difference
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

        // DER, explicitly. BouncyCastle defaults to BER when the content is
        // streamed, which emits an indefinite-length SEQUENCE (30 80) terminated
        // by 00 00 end-of-contents octets. PDFBox then zero-pads /Contents out to
        // the reserved size, and a BER parser reads that padding as a sea of EOC
        // markers and fails with "IOException reading content". DER is also what
        // the PDF spec requires for adbe.pkcs7.detached.
        return generator.generate(StreamedContent(content), false)
            .getEncoded(ASN1Encoding.DER)
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
