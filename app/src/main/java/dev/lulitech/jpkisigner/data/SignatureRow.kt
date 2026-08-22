package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.pdf.SignatureInfo

/**
 * One row of the signature list, oldest first.
 *
 * Provenance is deliberately not modelled. Whether this app applied a signature
 * or it arrived with an imported file makes no difference: every revision
 * boundary is readable from the PDF, so any signature can be removed. The
 * library holds copies, and truncation can only ever remove signatures -- it
 * cannot fabricate one.
 *
 * @param truncateTo byte length to truncate the file to in order to remove this
 *   signature and every signature after it, or null when that cannot be done
 *   safely (for instance a signature whose ByteRange does not reach its revision
 *   end, which truncating would corrupt).
 */
data class SignatureRow(
    val position: Int,
    val info: SignatureInfo,
    val truncateTo: Long?,
) {
    val isRemovable: Boolean get() = truncateTo != null
}

object SignatureRows {

    /**
     * Oldest first, so the list reads forward in time and "everything after this"
     * maps onto "below".
     */
    fun build(signatures: List<SignatureInfo>, truncationLengths: List<Long?>): List<SignatureRow> =
        signatures.mapIndexed { position, info ->
            SignatureRow(
                position = position,
                info = info,
                truncateTo = truncationLengths.getOrNull(position),
            )
        }

    /**
     * How many signatures a delete starting at [row] removes, itself included.
     * Always the whole suffix: signature n+1 signed the bytes containing
     * signature n, so they can only go together.
     */
    fun cascadeCount(rows: List<SignatureRow>, row: SignatureRow): Int =
        if (!row.isRemovable) 0 else rows.size - row.position
}
