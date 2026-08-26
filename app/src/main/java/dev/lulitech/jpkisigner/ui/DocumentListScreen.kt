package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lulitech.jpkisigner.R

/**
 * Screen 1: the document library.
 *
 * A plain list of card-styled rows. Deletion is by swipe, long-press or an
 * accessibility action, so there are no delete buttons.
 */
@Composable
fun DocumentListScreen(
    documents: List<DocumentUi>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<DocumentUi?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            DeletableRow(
                deleteLabel = stringResource(R.string.documents_delete),
                accessibilityLabel = stringResource(
                    R.string.a11y_delete_document,
                    document.displayName,
                ),
                onDeleteRequested = { pending = document },
                onClick = { onOpen(document.id) },
            ) {
                DocumentCard(document)
            }
        }

        // Sharing a PDF in is the only way to add one, so the instruction has to
        // stay reachable rather than vanish once the library is not empty. As the
        // last row it follows the documents it is about, and on an empty library
        // it is simply the only thing there.
        item {
            Text(
                text = stringResource(R.string.documents_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }

    pending?.let { document ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.documents_delete_title)) },
            text = {
                Text(stringResource(R.string.documents_delete_body, document.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onDelete(document.id)
                }) { Text(stringResource(R.string.documents_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Opening is handled by the enclosing [DeletableRow], which owns the click. */
@Composable
private fun DocumentCard(document: DocumentUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(document.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (document.signatureCount == 0) {
                    stringResource(R.string.documents_no_signature)
                } else {
                    pluralStringResource(
                        R.plurals.documents_signature_count,
                        document.signatureCount,
                        document.signatureCount,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
