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
     */
    fun truncate(id: String, length: Long) {
        val document = requireNotNull(read(id)) { "no such document: $id" }
        require(length in 1..document.head.length()) {
            "truncation length $length outside 1..${document.head.length()}"
        }
        java.io.RandomAccessFile(document.head, "rw").use { it.setLength(length) }
    }

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
    }
}
