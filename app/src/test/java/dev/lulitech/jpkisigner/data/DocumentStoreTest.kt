package dev.lulitech.jpkisigner.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        store = DocumentStore(temp.newFolder("documents"))
    }

    private fun import(name: String, content: ByteArray) =
        store.import(name, content.inputStream())

    @Test
    fun `imports and lists documents`() {
        val id = import("contract.pdf", "hello".toByteArray())
        val document = store.get(id)!!

        assertEquals("contract.pdf", document.displayName)
        assertArrayEquals("hello".toByteArray(), document.head.readBytes())
        assertEquals(1, store.list().size)
    }

    /**
     * Import order, not modification order: signing or truncating a document
     * must not move it in the list.
     */
    @Test
    fun `lists newest import first and signing does not reorder`() {
        val root = temp.newFolder("ordered")
        val store = DocumentStore(root)
        // Ids are assigned from the clock, so write them directly to control order.
        listOf(
            "1700000000001-00000001" to "oldest.pdf",
            "1700000000002-00000002" to "middle.pdf",
            "1700000000003-00000003" to "newest.pdf",
        ).forEach { (id, name) ->
            java.io.File(root, id).apply { mkdirs() }
                .let { java.io.File(it, name).writeBytes("%PDF\n%%EOF\n".toByteArray()) }
        }

        assertEquals(
            listOf("newest.pdf", "middle.pdf", "oldest.pdf"),
            store.list().map { it.displayName },
        )

        // Touching the oldest document, as signing would, must not promote it.
        val oldest = store.get("1700000000001-00000001")!!
        oldest.head.appendBytes("appended".toByteArray())
        oldest.head.setLastModified(System.currentTimeMillis())

        assertEquals(
            "order must be unchanged after a write",
            listOf("newest.pdf", "middle.pdf", "oldest.pdf"),
            store.list().map { it.displayName },
        )
    }

    @Test
    fun `delete removes the document entirely`() {
        val id = import("a.pdf", "x".toByteArray())
        store.delete(id)
        assertNull(store.get(id))
        assertTrue(store.list().isEmpty())
    }

    /** No half-imported leftovers that later read as a valid document. */
    @Test
    fun `a failed import leaves nothing listable`() {
        val exploding = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("disk full")
        }
        runCatching { store.import("bad.pdf", exploding) }
        assertTrue("no document should be listed", store.list().isEmpty())
    }

    @Test
    fun `truncating restores the exact earlier bytes`() {
        val original = "ORIGINAL".toByteArray()
        val id = import("a.pdf", original)
        val head = store.get(id)!!.head

        head.appendBytes("--APPENDED".toByteArray())
        store.truncate(id, original.size.toLong())

        assertArrayEquals(
            "must be byte-identical to the original",
            original,
            store.get(id)!!.head.readBytes(),
        )
    }

    @Test
    fun `truncating past the end of the file is rejected`() {
        val id = import("a.pdf", "SHORT".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { store.truncate(id, 9999L) }
    }

    /**
     * FileProvider hands the recipient the file's real on-disk name, so the
     * stored filename is what a shared document arrives as. A fixed name would
     * deliver every document as "head.pdf".
     */
    @Test
    fun `the file is stored under its display name`() {
        val id = import("契約書.pdf", "x".toByteArray())
        assertEquals("契約書.pdf", store.get(id)!!.head.name)
    }

    @Test
    fun `path separators in the name cannot escape the directory`() {
        val id = import("../../etc/passwd.pdf", "x".toByteArray())
        val head = store.get(id)!!.head

        // Assert the properties that matter, not the exact mangling.
        assertTrue("no separators may survive", head.name.none { it == '/' || it == '\\' })
        assertTrue(
            "must stay inside the store: ${head.canonicalPath}",
            head.canonicalPath.startsWith(temp.root.canonicalPath),
        )
        assertArrayEquals("x".toByteArray(), head.readBytes())
    }

    @Test
    fun `a name without a pdf extension gains one`() {
        val id = import("contract", "x".toByteArray())
        assertEquals("contract.pdf", store.get(id)!!.head.name)
    }

    /**
     * A path component is capped at 255 **bytes**, so a character-based cap
     * would let a long Japanese name through and the import would fail with
     * ENAMETOOLONG.
     */
    @Test
    fun `a long japanese name is capped in bytes, not characters`() {
        val id = import("契".repeat(200) + ".pdf", "x".toByteArray())
        val name = store.get(id)!!.head.name

        val bytes = name.toByteArray(Charsets.UTF_8).size
        assertTrue("must fit NAME_MAX: was $bytes bytes", bytes <= 255)
        assertTrue("extension must survive truncation", name.endsWith(".pdf"))
        // 251 bytes of stem at 3 bytes per character, plus ".pdf".
        assertEquals(83, name.removeSuffix(".pdf").length)
    }

    @Test
    fun `a long ascii name is capped and keeps its extension`() {
        val id = import("a".repeat(400) + ".pdf", "x".toByteArray())
        val name = store.get(id)!!.head.name
        assertEquals(255, name.toByteArray(Charsets.UTF_8).size)
        assertTrue(name.endsWith(".pdf"))
    }

    /** Truncation must not cut a surrogate pair in half. */
    @Test
    fun `truncation does not split a surrogate pair`() {
        val emoji = "\uD83D\uDCC4" // page-facing-up, 4 UTF-8 bytes
        val id = import(emoji.repeat(100) + ".pdf", "x".toByteArray())
        val name = store.get(id)!!.head.name

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(
            "no unpaired surrogate may remain",
            name.none { it.isHighSurrogate() || it.isLowSurrogate() } ||
                name.removeSuffix(".pdf").length % 2 == 0,
        )
        // Round-trips through the filesystem, which a broken string would not.
        assertEquals(name, store.get(id)!!.head.name)
    }

    @Test
    fun `a blank name falls back to a default`() {
        val id = import("   ", "x".toByteArray())
        assertEquals("document.pdf", store.get(id)!!.head.name)
    }

    /**
     * The display name is the filename, so what the UI shows and what a recipient
     * receives are the same string by construction.
     */
    @Test
    fun `display name and filename are the same`() {
        val id = import("a/b.pdf", "x".toByteArray())
        val document = store.get(id)!!
        assertEquals(document.head.name, document.displayName)
        assertEquals("a_b.pdf", document.displayName)
    }

    
    
    /** A .part left by a crash mid-import must not be mistaken for the document. */
    @Test
    fun `a stale staging file is ignored`() {
        val id = import("real.pdf", "PDF".toByteArray())
        val dir = store.get(id)!!.head.parentFile!!
        java.io.File(dir, "staging.part").writeBytes("JUNK".toByteArray())

        assertEquals("real.pdf", store.get(id)!!.head.name)
        assertArrayEquals("PDF".toByteArray(), store.get(id)!!.head.readBytes())
    }

    @Test
    fun `truncating to zero is rejected`() {
        val id = import("a.pdf", "SHORT".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { store.truncate(id, 0L) }
    }
}
