package dev.lulitech.jpkisigner.data

/**
 * A proposed export filename, with the part worth editing already selected.
 *
 * Pre-selecting only the inserted token lets the user type over it immediately
 * instead of clearing the whole field first.
 */
data class ProposedName(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Filename rules, shared by import and export.
 *
 * One definition, because both ends face the same constraints: a path component
 * is capped at 255 UTF-8 bytes, and separators or control characters either break
 * the write or get mangled by whichever app receives the file.
 */
object FileNames {

    const val EXTENSION = ".pdf"

    /** NAME_MAX on Android's filesystems, in bytes. Verified on device. */
    const val MAX_NAME_BYTES = 255

    private const val SIGNED_TOKEN = "signed"

    /**
     * Makes [displayName] safe to use as a single path component.
     *
     * Replaces separators and control characters, strips leading dots, and caps
     * the length in **UTF-8 bytes** — not characters, since a Japanese name runs
     * three bytes per character and a character-based cap would overshoot
     * NAME_MAX and fail the write. The extension is removed before truncating and
     * re-appended, so it always survives.
     */
    fun safe(displayName: String): String {
        val cleaned = displayName
            .map { if (it == '/' || it == '\\' || it == 0.toChar() || it < ' ') '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')

        val stem = stemOf(cleaned)
        val truncated = truncateUtf8(stem, MAX_NAME_BYTES - EXTENSION.length)
        return if (truncated.isBlank()) "document$EXTENSION" else "$truncated$EXTENSION"
    }

    /**
     * The export name offered by default: the stored name with `-signed` before
     * the extension, and that token selected.
     *
     * A name that already ends in `-signed` is left alone rather than becoming
     * `-signed-signed` after a second signature; the existing token is selected
     * instead, so it stays just as editable.
     *
     * The result always satisfies [validateExportName]. Appending the token can
     * push a stored name that was already at the cap over it, and a dialog that
     * opens on a name its own confirm button rejects is worse than a shortened
     * proposal the user can still edit.
     */
    fun proposeExportName(storedName: String): ProposedName {
        val stem = stemOf(storedName)

        if (stem.endsWith("-$SIGNED_TOKEN", ignoreCase = true) && fitsWithExtension(stem)) {
            val start = stem.length - SIGNED_TOKEN.length
            return ProposedName("$stem$EXTENSION", start, stem.length)
        }

        // Room for the extension, the token, and the hyphen joining it on.
        val room = MAX_NAME_BYTES - EXTENSION.length - SIGNED_TOKEN.length - 1
        val base = truncateUtf8(stem, room)
        val start = base.length + 1
        return ProposedName("$base-$SIGNED_TOKEN$EXTENSION", start, start + SIGNED_TOKEN.length)
    }

    private fun fitsWithExtension(stem: String): Boolean =
        stem.toByteArray(Charsets.UTF_8).size + EXTENSION.length <= MAX_NAME_BYTES

    /** Rejects a user-supplied export name, or null when it is usable. */
    fun validateExportName(name: String): ExportNameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isBlank() -> ExportNameProblem.BLANK
            trimmed.any { it == '/' || it == '\\' || it < ' ' } -> ExportNameProblem.SEPARATORS
            trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> ExportNameProblem.TOO_LONG
            !trimmed.endsWith(EXTENSION, ignoreCase = true) -> ExportNameProblem.NOT_PDF
            // ".pdf" on its own passed every check above and reached the recipient
            // as a nameless, hidden file. The extension is a suffix, not a name.
            trimmed.dropLast(EXTENSION.length).all { it == '.' } -> ExportNameProblem.BLANK
            else -> null
        }
    }

    private fun stemOf(name: String): String =
        if (name.length > EXTENSION.length && name.endsWith(EXTENSION, ignoreCase = true)) {
            name.dropLast(EXTENSION.length)
        } else {
            name
        }

    /**
     * Truncates to at most [maxBytes] of UTF-8, cutting only on code-point
     * boundaries so a surrogate pair is never split in half.
     */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.appendRange(value, i, i + chars)
            used += bytes
            i += chars
        }
        return out.toString()
    }
}

enum class ExportNameProblem { BLANK, SEPARATORS, TOO_LONG, NOT_PDF }
