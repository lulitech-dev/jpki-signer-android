package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.bc.BcRSASignerInfoVerifierBuilder
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.util.Selector
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date

/**
 * One signature as read out of the PDF itself.
 *
 * Deliberately carries no overall "valid" verdict. Offline we can prove that the
 * signature matches the bytes it covers and whether anything was appended after
 * it, but we cannot check revocation without network. A revoked certificate
 * passes every check available here. See PLAN.md §3.3.
 */
data class SignatureInfo(
    val name: String?,
    val reason: String?,
    val location: String?,
    val signedAt: Date?,
    val signerCommonName: String?,
    /** The CMS signature verifies against the bytes in its ByteRange. */
    val integrityOk: Boolean,
    /** Nothing was appended after this signature — it covers the file to its end. */
    val coversWholeDocument: Boolean,
    /** Why [integrityOk] is false, when the failure was an error rather than a mismatch. */
    val verificationError: String? = null,
)

object SignatureInspector {

    /** Reads every signature present in [file], oldest first. */
    fun inspect(file: File): List<SignatureInfo> {
        val bytes = file.readBytes()
        return PDDocument.load(file).use { document ->
            document.signatureDictionaries.map { sig ->
                val byteRange = sig.byteRange
                val coversAll =
                    byteRange.size >= 4 && (byteRange[2].toLong() + byteRange[3]) == bytes.size.toLong()

                var integrityOk = false
                var signerCn: String? = null
                var error: String? = null
                runCatching {
                    val signedData = CMSSignedData(
                        CMSProcessableByteArray(sig.getSignedContent(bytes)),
                        parseContentInfo(sig.getContents(bytes)),
                    )
                    val signer: SignerInformation = signedData.signerInfos.signers.first()
                    // SignerId implements Selector<X509CertificateHolder>, but the
                    // Java generics do not survive into Kotlin's view of Store.
                    @Suppress("UNCHECKED_CAST")
                    val selector = signer.sid as Selector<X509CertificateHolder>
                    val cert = signedData.certificates.getMatches(selector).first()
                    signerCn = CertificateNames.holderName(cert.encoded)
                    integrityOk = signer.verify(buildVerifier(cert))
                }.onFailure { error = "${it::class.java.simpleName}: ${it.message}" }

                SignatureInfo(
                    name = sig.name,
                    reason = sig.reason,
                    location = sig.location,
                    signedAt = sig.signDate?.time,
                    signerCommonName = signerCn,
                    integrityOk = integrityOk,
                    coversWholeDocument = coversAll,
                    verificationError = error,
                )
            }
        }
    }

    /**
     * Reads the CMS ContentInfo out of a PDF `/Contents` string.
     *
     * `/Contents` is always zero-padded to the space reserved at signing time, so
     * the DER object is followed by junk. `CMSSignedData(byte[])` routes through
     * `ASN1Primitive.fromByteArray`, which is strict and throws
     * "Extra data detected in stream". Reading a single object from a stream
     * instead ignores whatever follows.
     */
    private fun parseContentInfo(contents: ByteArray): ContentInfo =
        ASN1InputStream(ByteArrayInputStream(contents)).use { asn1 ->
            ContentInfo.getInstance(asn1.readObject())
        }

    /** Provider-free verifier — see PLAN.md §5.1. */
    private fun buildVerifier(cert: X509CertificateHolder) =
        BcRSASignerInfoVerifierBuilder(
            DefaultCMSSignatureAlgorithmNameGenerator(),
            DefaultSignatureAlgorithmIdentifierFinder(),
            DefaultDigestAlgorithmIdentifierFinder(),
            BcDigestCalculatorProvider(),
        ).build(cert)
}
