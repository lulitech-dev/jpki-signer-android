package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignerDigestMismatchException
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
 * What the offline integrity check established about one signature.
 *
 * Three states, not two. "We checked, and it does not match" and "we could not
 * check it at all" are different things to say to someone about their document,
 * and collapsing them meant an intact PDF carrying a signature this app cannot
 * verify -- an ECDSA one, say, since the verifier here is RSA-only -- was
 * reported as a damaged file. See DESIGN.md §3.3.
 */
enum class SignatureIntegrity {
    /** The CMS signature verifies against the bytes in its ByteRange. */
    OK,

    /**
     * It was checked, and it does not match: either the signature itself failed
     * or the message-digest attribute disagrees with the covered bytes.
     */
    MISMATCH,

    /**
     * No verdict. The signature could not be checked here at all -- an algorithm
     * this app does not implement, a CMS structure it could not read, a
     * certificate it could not pair with the signer. Says nothing either way
     * about the document. [SignatureInfo.verificationError] carries the detail.
     */
    UNCHECKED,
}

/**
 * One signature as read out of the PDF itself.
 *
 * Deliberately carries no overall "valid" verdict. Offline we can prove that the
 * signature matches the bytes it covers and whether anything was appended after
 * it, but we cannot check revocation without network. A revoked certificate
 * passes every check available here. See DESIGN.md §3.3.
 */
data class SignatureInfo(
    val name: String?,
    val reason: String?,
    val location: String?,
    val signedAt: Date?,
    val signerCommonName: String?,
    /** What the offline check established, if anything. */
    val integrity: SignatureIntegrity,
    /** Nothing was appended after this signature — it covers the file to its end. */
    val coversWholeDocument: Boolean,
    /**
     * The failure behind [integrity], when one was thrown. Developer-facing:
     * always set for [SignatureIntegrity.UNCHECKED], and also set for a
     * [SignatureIntegrity.MISMATCH] that surfaced as a digest-mismatch exception
     * rather than as a plain false.
     */
    val verificationError: String? = null,
)

object SignatureInspector {

    /**
     * How many signatures [file] carries, without verifying any of them.
     *
     * A count is all the library list shows, and producing it through [inspect]
     * meant a full in-memory read plus one RSA verification per signature, for
     * every document, on every refresh. This reads through PDFBox's own buffered
     * file access instead, so the whole document is never resident.
     *
     * @return 0 for a file that cannot be parsed. Unlike [inspect] this does not
     *   throw: there is no count to show either way, and the caller is a list row.
     */
    fun count(file: File): Int = runCatching {
        PDDocument.load(file).use { it.signatureDictionaries.size }
    }.getOrDefault(0)

    /**
     * Reads every signature present in [file], oldest first.
     *
     * "Oldest first" is enforced, not assumed: PDFBox hands back signatures in
     * AcroForm field order, which is normally chronological but is not promised
     * to be. [revisionEndOf] is the ordering key, and [PdfRevisions] sorts by the
     * same one so index *i* here and index *i* there are the same signature.
     */
    fun inspect(file: File): List<SignatureInfo> = inspect(file.readBytes())

    /**
     * [inspect] over bytes already in hand.
     *
     * Exists so a caller that also needs [PdfRevisions.truncationLengths] can read
     * the file once and pass the same array to both. Asking each of them for a
     * `File` read a user's document into memory twice over.
     */
    fun inspect(bytes: ByteArray): List<SignatureInfo> {
        // Parsed from the bytes already in hand. PDDocument.load(File) would read
        // the whole document a second time, and these are user files.
        return PDDocument.load(bytes).use { document ->
            document.signatureDictionaries.sortedBy(::revisionEndOf).map { sig ->
                val byteRange = sig.byteRange
                val coversAll =
                    byteRange.size >= 4 && (byteRange[2].toLong() + byteRange[3]) == bytes.size.toLong()

                var integrity = SignatureIntegrity.UNCHECKED
                var signerCn: String? = null
                var error: String? = null
                runCatching {
                    val signedData = CMSSignedData(
                        CMSProcessableByteArray(sig.getSignedContent(bytes)),
                        parseContentInfo(sig.getContents(bytes)),
                    )
                    val signers = signedData.signerInfos.signers
                    // Exactly one, or no verdict at all. A PDF signature carries a
                    // single SignerInfo, and verifying the first of several would
                    // put a verdict on the row that says nothing about the rest --
                    // a claim we cannot support, which is the one thing this list
                    // must not make. An empty bundle fails here for the same
                    // reason, and both land in UNCHECKED like every other limit of
                    // this app.
                    check(signers.size == 1) {
                        "expected one SignerInfo, found ${signers.size}"
                    }
                    val signer: SignerInformation = signers.first()
                    // SignerId implements Selector<X509CertificateHolder>, but the
                    // Java generics do not survive into Kotlin's view of Store.
                    @Suppress("UNCHECKED_CAST")
                    val selector = signer.sid as Selector<X509CertificateHolder>
                    val cert = signedData.certificates.getMatches(selector).first()
                    signerCn = CertificateNames.holderName(cert.encoded)
                    integrity = if (signer.verify(buildVerifier(cert))) {
                        SignatureIntegrity.OK
                    } else {
                        SignatureIntegrity.MISMATCH
                    }
                }.onFailure {
                    error = "${it::class.java.simpleName}: ${it.message}"
                    // Only one throw is evidence *about the document*: the
                    // message-digest attribute disagreeing with the bytes the
                    // ByteRange covers. Everything else -- an algorithm this
                    // build does not implement, a CMS structure that would not
                    // parse, a signer certificate not present in the bundle --
                    // is a limit of this app, and saying anything about the
                    // document on the strength of it would be a claim we cannot
                    // support.
                    integrity = if (it is CMSSignerDigestMismatchException) {
                        SignatureIntegrity.MISMATCH
                    } else {
                        SignatureIntegrity.UNCHECKED
                    }
                }

                SignatureInfo(
                    name = sig.name,
                    reason = sig.reason,
                    location = sig.location,
                    signedAt = sig.signDate?.time,
                    signerCommonName = signerCn,
                    integrity = integrity,
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

    /** Provider-free verifier — see DESIGN.md §5.1. */
    private fun buildVerifier(cert: X509CertificateHolder) =
        BcRSASignerInfoVerifierBuilder(
            DefaultCMSSignatureAlgorithmNameGenerator(),
            DefaultSignatureAlgorithmIdentifierFinder(),
            DefaultDigestAlgorithmIdentifierFinder(),
            BcDigestCalculatorProvider(),
        ).build(cert)
}
