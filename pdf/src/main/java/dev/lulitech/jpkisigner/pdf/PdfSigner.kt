package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDPropBuild
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDPropBuildDataDict
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/** The document forbids further change, so it must not be signed. */
class ChangesNotPermittedException :
    Exception("this document does not permit changes, so it cannot be signed")

/** Optional metadata written into the signature dictionary. */
data class SignParams(
    val reason: String? = null,
    val location: String? = null,
    val contactInfo: String? = null,
    val applicationName: String = "JPKI Signer",
    /**
     * Recorded in the signature's /Prop_Build. Callers should pass the real
     * version; the default is deliberately not a literal version number, because
     * one hardcoded here goes stale against the app's own `versionName` without
     * anything failing.
     */
    val applicationVersion: String = "unknown",
)

/**
 * Applies one detached PKCS#7 signature to a PDF as an incremental update.
 *
 * Uses `saveIncrementalForExternalSigning` rather than PDFBox's blocking
 * `SignatureInterface` callback, so the card interaction is a single explicit
 * step instead of something that blocks a save thread on a user's NFC tap.
 * See DESIGN.md §3.1.
 *
 * The original bytes are never rewritten — [output] begins with [input] byte for
 * byte, which is what makes the revision stack in DESIGN.md §3.3 possible.
 */
class PdfSigner(private val provider: SignatureProvider) {

    fun sign(input: File, output: File, params: SignParams = SignParams()) {
        PDDocument.load(input).use { document ->
            // Before anything is asked of the provider. A certification signature
            // may forbid all further change; the reference refuses in that case
            // rather than producing a signature that breaks the existing
            // certification, and so do we. Refusing first also means a document
            // we were never going to sign does not cost a certificate read, which
            // over NFC is several round trips against a card the user is holding
            // to the phone.
            //
            // Note we never *write* DocMDP: our signatures are ordinary approval
            // signatures. See DocMdp's documentation.
            if (DocMdp.existingPermission(document) == DocMdp.NO_CHANGES_PERMITTED) {
                throw ChangesNotPermittedException()
            }

            val holderName = CertificateNames.holderName(provider.signerCertificate())

            // The reference always writes a Reason, defaulting to a sentence
            // naming the signer, so the signature is self-describing in any
            // viewer. Deliberately Japanese regardless of app locale: it is
            // embedded in a document destined for Japanese authorities.
            //
            // Only when there is a name to name it with. `holderName` is null for
            // a certificate carrying neither the JPKI name extension nor a subject
            // CN, and interpolating it wrote the literal
            // "null によって署名されています。" into the document.
            val reason = params.reason
                ?: holderName?.let { "$it によって署名されています。" }

            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName(holderName)
                reason?.let { setReason(it) }
                params.location?.let { setLocation(it) }
                params.contactInfo?.let { setContactInfo(it) }
                signDate = Calendar.getInstance()

                // Records which application produced the signature. Informational,
                // but the reference emits it and it costs nothing.
                // setPDPropBuildApp pairs with getApp(), so it is not a Kotlin
                // property and has to be called by name.
                propBuild = PDPropBuild().apply {
                    setPDPropBuildApp(
                        PDPropBuildDataDict().apply {
                            setName(params.applicationName)
                            setVersion(params.applicationVersion)
                            setTrustedMode(true)
                        },
                    )
                }
            }

            SignatureOptions().use { options ->
                // preferredSignatureSize is deliberately left alone, so /Contents
                // reserves PDFBox's own DEFAULT_SIGNATURE_SIZE (9472). This used
                // to be raised to 16384 for headroom; the reference implementation
                // passes no SignatureOptions at all for an invisible signature and
                // therefore gets the default, and matching its output byte shape
                // matters more than headroom we were not using -- a real CMS with
                // the signer and CA certificates measures under 4 KB.
                document.addSignature(signature, options)

                FileOutputStream(output).use { out ->
                    val external = document.saveIncrementalForExternalSigning(out)
                    // Do not close this stream — it is backed by PDFBox's writer,
                    // and closing it corrupts the output being assembled.
                    val cms = CmsBuilder.buildDetached(external.content, provider)
                    external.setSignature(cms)
                }
            }
        }
    }
}
