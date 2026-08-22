package dev.lulitech.jpkisigner.jpki

import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DigestInfoTest {

    /**
     * The whole point of the hardcoded prefix is that it is byte-identical to a
     * real ASN.1 encoder's output. BouncyCastle is a test-only dependency here;
     * :jpki ships without it.
     */
    @Test
    fun `matches bouncycastle byte for byte`() {
        val digest = ByteArray(32) { it.toByte() }
        val expected = org.bouncycastle.asn1.x509.DigestInfo(
            AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE),
            digest,
        ).encoded

        assertArrayEquals(expected, DigestInfo.forSha256(digest))
        assertEquals("19-byte prefix plus a 32-byte digest", 51, DigestInfo.forSha256(digest).size)
    }

    @Test
    fun `rejects a wrongly sized digest`() {
        assertThrows(IllegalArgumentException::class.java) { DigestInfo.forSha256(ByteArray(20)) }
        assertThrows(IllegalArgumentException::class.java) { DigestInfo.forSha256(ByteArray(64)) }
    }
}
