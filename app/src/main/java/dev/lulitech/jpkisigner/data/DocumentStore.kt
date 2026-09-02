package dev.lulitech.jpkisigner.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * A PDF held by the app.
 *
 * No signature bookkeeping: revision boundaries are derived from the PDF itself,
 * which works identically for signatures this app made and signatures that
 * arrived with an imported file. No stored display name either: the file's own
 * name is the display name, and is also what a recipient receives when the
 * document is shared out.
 */
data class StoredDocument(
    val id: String,
    val displayName: String,
    val head: File,
)

/**
 * App-private document library.
 *
 * PDFs arrive by share-in, and a shared `content://` read grant dies with the
 * receiving activity, so every import is copied in immediately. Deliberately
 * plain `java.io.File` with no Android dependency, which keeps it unit-testable.
 */
class DocumentStore(private val root: File) {

    /** Copies [source] in and returns the new document id. */
    fun import(displayName: String, source: InputStream): String {
        val id = newId()
        val dir = File(root, id)
        // Checked, not assumed. `mkdirs` returns false when the directory is
        // already there, which for an id is another document -- and this would go
        // on to rename its content file out from under it. The id is 13 digits of
        // clock plus 28 bits of randomness, so it takes a collision inside one
        // millisecond, but the cost of not looking is another document's bytes.
        // Failing here is safe: the caller reports the import as failed and
        // nothing has been written.
        check(dir.mkdirs()) { "could not create a directory for $id" }
        try {
            // Write to a temp name first, so a failure mid-copy cannot leave a
            // half-imported document that looks valid.
            val staging = File(dir, STAGING)
            // Flushed to the disk itself, not just out of our buffers, before the
            // rename that publishes it. A rename is ordered against the file's
            // metadata but not against its contents, so a power loss just after
            // one can leave the name in place over blocks that were never
            // written -- an entry in the library that opens as a damaged
            // document.
            FileOutputStream(staging).use { out ->
                source.copyTo(out)
                out.fd.sync()
            }
            check(staging.renameTo(File(dir, FileNames.safe(displayName)))) {
                "could not finalise import of $id"
            }
            return id
        } catch (e: Throwable) {
            // Leave nothing behind. An abandoned directory is invisible in the
            // list but would accumulate silently.
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * The document's bytes, stored under their real filename inside a directory
     * named by the document id.
     *
     * Not a fixed name, and not one prefixed with the id: `FileProvider` reports
     * a file's actual on-disk name as its display name when sharing out, and
     * `EXTRA_TITLE` is only a hint to the chooser. So whatever this file is
     * called is what a recipient receives. The id lives in the directory name,
     * where it guarantees uniqueness without reaching the recipient.
     *
     * Because the filename is the display name, no metadata file is needed.
     * Selecting on the extension rather than "the only file here" tolerates a
     * `.part` left by a crash mid-import, and leaves room for metadata later.
     */
    private fun contentFileOf(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("pdf", ignoreCase = true) }


    fun list(): List<StoredDocument> =
        (root.listFiles { f: File -> f.isDirectory } ?: emptyArray())
            .mapNotNull { read(it.name) }
            // Newest import first. The id begins with a zero-padded 13-digit
            // epoch, so a plain string comparison is chronological, and unlike
            // lastModified() it never shifts when a document is signed or
            // truncated. Sorting by modification time can be offered later as a
            // switch rather than being the accidental default.
            .sortedByDescending { it.id }

    fun get(id: String): StoredDocument? = read(id)

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Truncates the head file to [length], which must be a revision boundary
     * computed by `PdfRevisions`.
     *
     * Because a PDF incremental update never rewrites the original bytes, the
     * result is the byte-identical earlier revision.
     *
     * @param provenLength the length the file had when [length] was proved
     *   against it. A boundary is only a boundary of the bytes it was derived
     *   from: the offset reaches the caller through a screen, and a document that
     *   gained a signature in between would still accept the old offset -- it is
     *   below the new length -- and lose more revisions than the confirmation
     *   named. Bounding by the *current* length only catches the case where the
     *   file shrank, which is the harmless direction.
     */
    fun truncate(id: String, length: Long, provenLength: Long) {
        val document = requireNotNull(read(id)) { "no such document: $id" }
        val current = document.head.length()
        require(current == provenLength) {
            "document changed since the boundary was computed: $current != $provenLength"
        }
        require(length in 1..current) {
            "truncation length $length outside 1..$current"
        }
        java.io.RandomAccessFile(document.head, "rw").use { it.setLength(length) }
    }

    /**
     * Removes directories left behind by an import that never finished.
     *
     * [import] deletes its own directory when the copy throws, but a process
     * killed mid-copy never reaches that. What is left is a directory holding
     * only a staging file, which [list] skips for want of a `.pdf` -- so it is
     * invisible and accumulates, which is the outcome [import] documents wanting
     * to avoid.
     *
     * Only directories older than [ABANDONED_AFTER_MS] are touched, and the age
     * comes from the id's own epoch prefix rather than from the filesystem. A
     * sweep is not synchronised against an import, and two of them can overlap:
     * an activity recreation builds a second store over the same root while the
     * retained ViewModel is still copying. Deleting only what is an hour old
     * cannot reach a copy still in progress, and a directory this cannot date --
     * anything not named like an id -- is left alone entirely.
     */
    fun sweepAbandonedImports(now: Long = System.currentTimeMillis()) {
        for (dir in root.listFiles { f: File -> f.isDirectory } ?: emptyArray()) {
            if (contentFileOf(dir) != null) continue
            val importedAt = importTimeOf(dir.name) ?: continue
            if (now - importedAt < ABANDONED_AFTER_MS) continue
            dir.deleteRecursively()
        }
    }

    /** Epoch milliseconds from an id built by [newId], or null if it is not one. */
    private fun importTimeOf(id: String): Long? =
        ID_SHAPE.matchEntire(id)?.groupValues?.get(1)?.toLongOrNull()

    private fun read(id: String): StoredDocument? {
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        val head = contentFileOf(dir) ?: return null
        return StoredDocument(id = id, displayName = head.name, head = head)
    }


    /**
     * Sortable and unique: 13 digits of epoch milliseconds, zero-padded so a plain
     * string comparison is chronological, then 28 bits of randomness to separate
     * two imports landing in the same millisecond. [import] checks the directory
     * really was new rather than trusting that.
     *
     * Formatted against [Locale.ROOT], not the device's. `%d` renders in the
     * default locale's numbering system, so on a phone set to ar-EG or fa-IR the
     * id came out in Arabic-Indic digits -- and the ordering above is a *string*
     * comparison, which silently stops being chronological the moment ids from
     * two numbering systems sit in the same library. The app's own language has
     * nothing to do with it: this is the device locale.
     */
    private fun newId(): String = "%013d-%08x".format(
        Locale.ROOT,
        System.currentTimeMillis(),
        (Math.random() * 0xFFFFFFFL).toInt(),
    )

    private companion object {
        const val STAGING = "staging.part"

        /**
         * How old a directory with no document in it has to be before a sweep
         * will remove it. Generous: an import is a file copy, and even a large
         * one over a slow provider is minutes rather than hours.
         */
        const val ABANDONED_AFTER_MS = 60 * 60 * 1000L

        /** Exactly what [newId] produces, so nothing else is ever dated by it. */
        val ID_SHAPE = Regex("""(\d{13})-[0-9a-f]{8}""")
    }
}
