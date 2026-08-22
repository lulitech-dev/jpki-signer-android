package dev.lulitech.jpkisigner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lulitech.jpkisigner.R

/**
 * Screen 1: the document library.
 *
 * A plain list of card-styled rows. Deletion is by swipe with a confirming
 * dialog, so there are no delete buttons.
 */
@Composable
fun DocumentListScreen(
    documents: List<DocumentUi>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (documents.isEmpty()) {
        EmptyLibrary(modifier)
        return
    }

    var pending by remember { mutableStateOf<DocumentUi?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            DeletableRow(
                deletable = true,
                deleteLabel = stringResource(R.string.documents_delete),
                blockedLabel = "",
                accessibilityLabel = stringResource(
                    R.string.a11y_delete_document,
                    document.displayName,
                ),
                onDeleteRequested = { pending = document },
            ) {
                DocumentCard(document = document, onClick = { onOpen(document.id) })
            }
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

@Composable
private fun DocumentCard(document: DocumentUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(document.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (document.signatureCount == 0) {
                    stringResource(R.string.documents_no_signature)
                } else {
                    stringResource(R.string.documents_signature_count, document.signatureCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.documents_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.documents_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
