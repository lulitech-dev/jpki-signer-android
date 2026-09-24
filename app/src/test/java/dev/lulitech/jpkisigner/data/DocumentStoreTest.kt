package dev.lulitech.jpkisigner.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

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

    /**
     * The id is the library's sort key, compared as a plain string -- so it has
     * to be plain digits, whatever numbering system the *device* is set to.
     *
     * `%d` renders in the default locale's numbering system, and the app's own
     * language has nothing to do with what that is: an English-language app on a
     * phone set to ar-EG produced ids in Arabic-Indic digits. Ordering then held
     * only until the locale changed, at which point ids from two numbering
     * systems sat in one library and sorted by code point -- every older document
     * landing on one side of every newer one, silently.
     */
    @Test
    fun `ids are plain digits whatever the device locale is`() {
        val original = Locale.getDefault()
        try {
            val shape = Regex("""\d{13}-[0-9a-f]{8}""")
            for (tag in listOf("en-US", "ja-JP", "ar-EG", "fa-IR", "hi-IN-u-nu-deva")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val id = import("contract.pdf", "hello".toByteArray())
                assertTrue("id under $tag was \"$id\"", shape.matches(id))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

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
        store.truncate(id, original.size.toLong(), provenLength = head.length())

        assertArrayEquals(
            "must be byte-identical to the original",
            original,
            store.get(id)!!.head.readBytes(),
        )
    }

    @Test
    fun `truncating past the end of the file is rejected`() {
        val id = import("a.pdf", "SHORT".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            store.truncate(id, 9999L, provenLength = 5L)
        }
    }

    /**
     * A boundary is a boundary of the bytes it was proved against, and the offset
     * reaches the store through a screen. Bounding it by the file's *current*
     * length only catches a document that shrank, which is the harmless
     * direction: one that grew still accepts the old offset and loses more
     * revisions than the confirmation named.
     */
    @Test
    fun `truncating a document that grew since the boundary was proved is rejected`() {
        val original = "ORIGINAL".toByteArray()
        val id = import("a.pdf", original)
        val head = store.get(id)!!.head
        val proven = head.length()

        head.appendBytes("--A-WHOLE-NEW-REVISION".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            store.truncate(id, original.size.toLong(), provenLength = proven)
        }
        assertEquals(
            "nothing may be removed from a document we did not prove the boundary against",
            proven + "--A-WHOLE-NEW-REVISION".length,
            store.get(id)!!.head.length(),
        )
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
        assertThrows(IllegalArgumentException::class.java) {
            store.truncate(id, 0L, provenLength = 5L)
        }
    }

    /**
     * A process killed mid-copy never reaches [DocumentStore.import]'s own
     * cleanup, leaving a directory holding only a staging file. `list` skips it
     * for want of a `.pdf`, so it is invisible and would otherwise accumulate for
     * as long as the app is installed.
     */
    @Test
    fun `an abandoned import is swept once it is old enough`() {
        val root = temp.root.resolve("documents")
        val abandoned = root.resolve("1700000000000-0000abcd")
        abandoned.mkdirs()
        abandoned.resolve("staging.part").writeBytes("half a document".toByteArray())

        store.sweepAbandonedImports(now = 1700000000000L + 2 * 60 * 60 * 1000L)

        assertFalse("nothing here is reachable, so nothing here should stay", abandoned.exists())
    }

    /**
     * A sweep is not synchronised against an import, and an activity recreation
     * can build a second store over the same root while the retained ViewModel is
     * still copying. The age bound is what keeps the two apart.
     */
    @Test
    fun `a recent import in progress is left alone`() {
        val root = temp.root.resolve("documents")
        val inProgress = root.resolve("1700000000000-0000abcd")
        inProgress.mkdirs()
        inProgress.resolve("staging.part").writeBytes("still being copied".toByteArray())

        store.sweepAbandonedImports(now = 1700000000000L + 1000L)

        assertTrue("a copy in progress must survive a sweep", inProgress.exists())
    }

    /** Only directories this store could have named are ever dated, or removed. */
    @Test
    fun `a directory that is not one of our ids is never swept`() {
        val root = temp.root.resolve("documents")
        val foreign = root.resolve("not-an-id")
        foreign.mkdirs()

        store.sweepAbandonedImports(now = Long.MAX_VALUE)

        assertTrue(foreign.exists())
    }

    /** A real document is not an abandoned import, however old it is. */
    @Test
    fun `a finished import survives a sweep`() {
        val id = import("a.pdf", "PDF".toByteArray())

        store.sweepAbandonedImports(now = Long.MAX_VALUE)

        assertEquals("a.pdf", store.get(id)?.displayName)
    }
}
