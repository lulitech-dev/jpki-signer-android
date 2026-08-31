package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.io.RandomAccessBuffer
import com.tom_roush.pdfbox.io.RandomAccessRead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import kotlin.random.Random

/**
 * [PrefixRead] replaced `bytes.copyOf(length)` inside the one computation this
 * app must not get wrong: the byte length a signature can be truncated to. A view
 * that disagrees with the copy it replaced, anywhere PDFBox happens to look,
 * would move a revision boundary and take a user's unsigned work with it.
 *
 * So it is not merely exercised -- it is checked against PDFBox's own reader over
 * exactly that copy, operation for operation, including the cases the parser
 * reaches only on a damaged file: seeking past the end, reading there, and
 * rewinding back out of it.
 */
class PrefixReadTest {

    private val whole = ByteArray(5000) { (it * 31 % 251).toByte() }
    private val limit = 3333

    private fun pair(): Pair<RandomAccessRead, RandomAccessRead> =
        PrefixRead(whole, limit) to RandomAccessBuffer(whole.copyOf(limit))

    /** Every accessor that reports state, compared after each operation. */
    private fun assertSameState(step: String, mine: RandomAccessRead, theirs: RandomAccessRead) {
        assertEquals("$step: position", theirs.position, mine.position)
        assertEquals("$step: length", theirs.length(), mine.length())
        assertEquals("$step: isEOF", theirs.isEOF, mine.isEOF)
        assertEquals("$step: available", theirs.available(), mine.available())
    }

    @Test
    fun `agrees with pdfbox over a long scripted sequence`() {
        val (mine, theirs) = pair()
        val random = Random(20260831)

        assertSameState("start", mine, theirs)

        repeat(4000) { step ->
            when (random.nextInt(6)) {
                0 -> assertEquals("$step: read()", theirs.read(), mine.read())

                1 -> {
                    val length = random.nextInt(0, 64)
                    val a = ByteArray(length + 8) { -1 }
                    val b = ByteArray(length + 8) { -1 }
                    val offset = random.nextInt(0, 8)
                    assertEquals(
                        "$step: read(buffer, $offset, $length)",
                        theirs.read(b, offset, length),
                        mine.read(a, offset, length),
                    )
                    assertArrayEquals("$step: bytes read", b, a)
                }

                2 -> assertEquals("$step: peek()", theirs.peek(), mine.peek())

                // Deliberately reaches past the end: a cross-reference offset in
                // a damaged file can point anywhere, and the parser reads the
                // position back afterwards.
                3 -> {
                    val to = random.nextLong(0, limit + 500L)
                    mine.seek(to)
                    theirs.seek(to)
                }

                4 -> {
                    val by = random.nextInt(0, 32)
                    if (theirs.position - by >= 0) {
                        mine.rewind(by)
                        theirs.rewind(by)
                    }
                }

                else -> {
                    val length = random.nextInt(0, 40)
                    if (theirs.position + length <= limit) {
                        assertArrayEquals(
                            "$step: readFully($length)",
                            theirs.readFully(length),
                            mine.readFully(length),
                        )
                    }
                }
            }
            assertSameState("step $step", mine, theirs)
        }
    }

    @Test
    fun `a read at the end answers -1, not zero`() {
        val (mine, theirs) = pair()
        mine.seek(limit.toLong())
        theirs.seek(limit.toLong())

        assertEquals(-1, mine.read())
        assertEquals(theirs.read(), mine.read())
        assertEquals(-1, mine.read(ByteArray(16), 0, 16))
        assertEquals(theirs.read(ByteArray(16), 0, 16), mine.read(ByteArray(16), 0, 16))
        assertEquals(-1, mine.peek())
    }

    /**
     * The limit is a limit, not a suggestion: the bytes beyond it are still in the
     * array, and a view that let one through would prove a boundary against
     * content that is not part of the revision.
     */
    @Test
    fun `nothing past the limit is ever readable`() {
        val mine = PrefixRead(whole, limit)
        val all = mine.readFully(limit)

        assertArrayEquals(whole.copyOf(limit), all)
        assertTrue(mine.isEOF)
        assertEquals(0, mine.available())
        assertThrows(EOFException::class.java) { mine.readFully(1) }
    }

    @Test
    fun `seeking beyond the end reports where it was asked to go`() {
        val (mine, theirs) = pair()

        mine.seek(limit + 400L)
        theirs.seek(limit + 400L)

        assertEquals(limit + 400L, mine.position)
        assertEquals(theirs.position, mine.position)
        assertEquals(theirs.isEOF, mine.isEOF)
        assertEquals(theirs.available(), mine.available())
        assertEquals(theirs.read(), mine.read())
    }

    @Test
    fun `an empty prefix is empty, not the whole array`() {
        val mine = PrefixRead(whole, 0)

        assertEquals(0L, mine.length())
        assertTrue(mine.isEOF)
        assertEquals(-1, mine.read())
    }

    @Test
    fun `a limit outside the array is refused rather than silently clamped`() {
        assertThrows(IllegalArgumentException::class.java) { PrefixRead(whole, whole.size + 1) }
        assertThrows(IllegalArgumentException::class.java) { PrefixRead(whole, -1) }
    }
}
