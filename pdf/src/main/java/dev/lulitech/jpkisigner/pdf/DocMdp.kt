package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * DocMDP -- read only.
 *
 * A DocMDP entry marks a *certification* signature, which asserts that the
 * document is certified and constrains what may be changed afterwards. There can
 * be at most one, on the first signature.
 *
 * **This app never writes one.** The reference implementation does not either:
 * its `setMDPPermission` is public but called from nowhere, in the jar or in the
 * desktop app. What it actually does is read the permission and refuse to sign
 * when the document forbids changes. Signatures we produce are ordinary approval
 * signatures, which is the weaker and more honest claim -- we are adding a
 * signature, not certifying anyone's document.
 */
internal object DocMdp {

    /** No changes permitted at all: the document may not be signed further. */
    const val NO_CHANGES_PERMITTED = 1

    /**
     * The existing DocMDP permission level, or 0 when the document carries no
     * certification signature.
     *
     * 1 = no changes, 2 = form filling and signing, 3 = also annotations.
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
            return params.getInt(COSName.P, DEFAULT_WHEN_UNSTATED)
        }
        return 0
    }

    private const val DEFAULT_WHEN_UNSTATED = 2
    private val TRANSFORM_METHOD: COSName = COSName.getPDFName("TransformMethod")
    private val TRANSFORM_PARAMS: COSName = COSName.getPDFName("TransformParams")
}
