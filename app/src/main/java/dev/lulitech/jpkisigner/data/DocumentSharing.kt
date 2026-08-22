package dev.lulitech.jpkisigner.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Export is the Android share sheet; there is no SAF picker anywhere in the app.
 */
object DocumentSharing {

    /**
     * @param exportName what the recipient sees.
     *
     * Passed to the four-argument [FileProvider.getUriForFile], which appends a
     * `displayName` query parameter that the provider's `query()` returns in
     * preference to the file's own name. So the export name is decoupled from
     * storage and nothing is renamed on disk.
     *
     * `EXTRA_TITLE` is set too, but only labels the chooser preview — receiving
     * apps take the filename from the provider, not from the extra.
     */
    fun shareIntent(context: Context, file: File, exportName: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
            exportName,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, exportName)
            // ClipData, not just EXTRA_STREAM. The share sheet runs in another
            // process, and this is how the URI grant and the preview metadata
            // reach it; with EXTRA_STREAM alone it cannot read the provider and
            // falls back to the URI's last path segment -- the on-disk filename.
            clipData = ClipData.newUri(context.contentResolver, exportName, uri)
            // Without this the receiving app cannot read the file at all.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, exportName).apply {
            // The chooser is what actually gets started, so the grant has to be
            // on it too.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
