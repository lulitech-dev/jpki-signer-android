package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.pdf.SignatureInfo
import dev.lulitech.jpkisigner.pdf.SignatureIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureRowTest {

    private fun signatures(n: Int) = List(n) {
        SignatureInfo(
            name = "signer $it",
            reason = null,
            location = null,
            signedAt = null,
            signerCommonName = null,
            integrity = SignatureIntegrity.OK,
            coversWholeDocument = it == n - 1,
        )
    }

    /**
     * Every signature is removable regardless of who made it, because revision
     * boundaries come from the PDF rather than from local bookkeeping.
     */
    @Test
    fun `all signatures are removable when boundaries are known`() {
        val rows = SignatureRows.build(signatures(5), listOf(100L, 200L, 300L, 400L, 500L))
        assertTrue(rows.all { it.isRemovable })
        assertEquals(listOf(100L, 200L, 300L, 400L, 500L), rows.map { it.truncateTo })
    }

    /** The one exclusion is a boundary that could not be determined safely. */
    @Test
    fun `a signature with no derivable boundary is not removable`() {
        val rows = SignatureRows.build(signatures(3), listOf(null, 200L, 300L))
        assertEquals(listOf(false, true, true), rows.map { it.isRemovable })
    }

    @Test
    fun `a missing boundary entry is treated as not removable`() {
        val rows = SignatureRows.build(signatures(3), listOf(100L))
        assertEquals(listOf(true, false, false), rows.map { it.isRemovable })
    }

    @Test
    fun `cascade count covers the whole suffix`() {
        val rows = SignatureRows.build(signatures(5), List(5) { (it + 1) * 100L })

        assertEquals("deleting the first removes all 5", 5, SignatureRows.cascadeCount(rows, rows[0]))
        assertEquals(3, SignatureRows.cascadeCount(rows, rows[2]))
        assertEquals(1, SignatureRows.cascadeCount(rows, rows[4]))
    }

    @Test
    fun `a non-removable row cascades nothing`() {
        val rows = SignatureRows.build(signatures(3), listOf(null, 200L, 300L))
        assertEquals(0, SignatureRows.cascadeCount(rows, rows[0]))
    }
}
