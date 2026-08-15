package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/** Optional metadata written into the signature dictionary. */
data class SignParams(
    val reason: String? = null,
    val location: String? = null,
    val contactInfo: String? = null,
    val applicationName: String = "JPKI Signer",
    val applicationVersion: String = "0.1.0",
)

/**
 * Applies one detached PKCS#7 signature to a PDF as an incremental update.
 *
 * Uses `saveIncrementalForExternalSigning` rather than PDFBox's blocking
 * `SignatureInterface` callback, so the card interaction is a single explicit
 * step instead of something that blocks a save thread on a user's NFC tap.
 * See PLAN.md §3.1.
 *
 * The original bytes are never rewritten — [output] begins with [input] byte for
 * byte, which is what makes the revision stack in PLAN.md §3.3 possible.
 */
class PdfSigner(private val provider: SignatureProvider) {

    fun sign(input: File, output: File, params: SignParams = SignParams()) {
        PDDocument.load(input).use { document ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName(CertificateNames.commonNameOf(provider.signerCertificate()))
                params.reason?.let { setReason(it) }
                params.location?.let { setLocation(it) }
                params.contactInfo?.let { setContactInfo(it) }
                signDate = Calendar.getInstance()
            }

            // TODO(M2): port the reference's DocMDP certification signature —
            // /Perms /DocMDP plus the SigRef transform — for the first signature
            // only. See PLAN.md §5.

            SignatureOptions().use { options ->
                options.preferredSignatureSize = PREFERRED_SIGNATURE_SIZE
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

    private companion object {
        /**
         * Reserved /Contents space. A CMS with the signer certificate plus the CA
         * certificate lands around 3–4 KB; this leaves generous headroom, and
         * unused space is harmless zero padding.
         */
        const val PREFERRED_SIGNATURE_SIZE = 16384
    }
}
