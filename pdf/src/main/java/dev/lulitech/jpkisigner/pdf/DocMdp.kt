package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature

/**
 * DocMDP -- the "certification signature" the reference implementation writes.
 *
 * A certification signature declares what may still be changed without
 * invalidating it, and there can be at most one per document, on the first
 * signature. Later signatures are ordinary approval signatures.
 *
 * Ported from the reference's `getMDPPermission` / `setMDPPermission`, which in
 * turn follow PDFBox's own `SigUtils`. The `SHA1` DigestMethod looks wrong for a
 * SHA-256 signature but is what the PDF spec prescribes for this transform, and
 * what the reference emits.
 */
internal object DocMdp {

    /** Permission level 2: form filling and further signing stay allowed. */
    const val DEFAULT_PERMISSION = 2

    /**
     * The existing DocMDP permission level, or 0 if the document has no
     * certification signature yet.
     */
    fun existingPermission(document: PDDocument): Int {
        val perms = document.documentCatalog.cosObject
            .getDictionaryObject(COSName.PERMS) as? COSDictionary ?: return 0
        val docMdp = perms.getDictionaryObject(COSName.DOCMDP) as? COSDictionary ?: return 0
        val references = docMdp.getDictionaryObject(COSName.REFERENCE) as? COSArray ?: return 0

        for (i in 0 until references.size()) {
            val reference = references.getObject(i) as? COSDictionary ?: continue
            if (reference.getDictionaryObject(TRANSFORM_METHOD) != COSName.DOCMDP) continue
            val params = reference.getDictionaryObject(TRANSFORM_PARAMS) as? COSDictionary ?: continue
            return params.getInt(COSName.P, DEFAULT_PERMISSION)
        }
        return 0
    }

    /**
     * Marks [signature] as the document's certification signature.
     *
     * Every dictionary touched is flagged `needToBeUpdated`, without which an
     * incremental save silently omits the changes.
     */
    fun apply(document: PDDocument, signature: PDSignature, permission: Int = DEFAULT_PERMISSION) {
        require(permission in 1..3) { "DocMDP permission must be 1, 2 or 3; got $permission" }

        val signatureDict = signature.cosObject

        val transformParams = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("TransformParams"))
            setItem(COSName.V, COSName.getPDFName("1.2"))
            setInt(COSName.P, permission)
            isNeedToBeUpdated = true
        }

        val reference = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("SigRef"))
            setItem(TRANSFORM_METHOD, COSName.DOCMDP)
            setItem(COSName.DIGEST_METHOD, COSName.getPDFName("SHA1"))
            setItem(TRANSFORM_PARAMS, transformParams)
            isNeedToBeUpdated = true
        }

        val references = COSArray().apply { add(reference) }
        signatureDict.setItem(COSName.REFERENCE, references)
        signatureDict.isNeedToBeUpdated = true

        val catalog = document.documentCatalog.cosObject
        val perms = (catalog.getDictionaryObject(COSName.PERMS) as? COSDictionary
            ?: COSDictionary().also { catalog.setItem(COSName.PERMS, it) })
        perms.setItem(COSName.DOCMDP, signatureDict)
        perms.isNeedToBeUpdated = true
        catalog.isNeedToBeUpdated = true
    }

    private val TRANSFORM_METHOD: COSName = COSName.getPDFName("TransformMethod")
    private val TRANSFORM_PARAMS: COSName = COSName.getPDFName("TransformParams")
}
