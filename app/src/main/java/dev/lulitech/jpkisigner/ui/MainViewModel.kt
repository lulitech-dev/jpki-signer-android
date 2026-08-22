package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.SignatureRow
import dev.lulitech.jpkisigner.data.SignatureRows
import dev.lulitech.jpkisigner.pdf.PdfRejection
import dev.lulitech.jpkisigner.pdf.PdfRevisions
import dev.lulitech.jpkisigner.pdf.PdfValidator
import dev.lulitech.jpkisigner.pdf.SignatureInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Why an incoming file did not become a document. */
sealed interface ImportError {
    /** It was read, but is not something this app can sign. */
    data class Rejected(val reason: PdfRejection) : ImportError

    /** It could not be read or copied at all. */
    data class Failed(val message: String) : ImportError
}

/** A document as the list screen needs it. */
data class DocumentUi(
    val id: String,
    val displayName: String,
    val signatureCount: Int,
)

/** Screen 2's view of one document. */
data class DocumentDetailUi(
    val id: String,
    val displayName: String,
    val head: File,
    val rows: List<SignatureRow>,
)

class MainViewModel(private val store: DocumentStore) : ViewModel() {

    private val _documents = MutableStateFlow<List<DocumentUi>>(emptyList())
    val documents: StateFlow<List<DocumentUi>> = _documents.asStateFlow()

    private val _detail = MutableStateFlow<DocumentDetailUi?>(null)
    val detail: StateFlow<DocumentDetailUi?> = _detail.asStateFlow()

    /** Last import failure, for display. Import must never fail silently. */
    private val _importError = MutableStateFlow<ImportError?>(null)
    val importError: StateFlow<ImportError?> = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _documents.value = withContext(Dispatchers.IO) {
            store.list().map { document ->
                DocumentUi(
                    id = document.id,
                    displayName = document.displayName,
                    // Read from the PDF, never from local bookkeeping, so
                    // co-signers and pre-existing signatures are counted too.
                    signatureCount = countSignatures(document.head),
                )
            }
        }
    }

    /**
     * Copies an incoming PDF in.
     *
     * [open] is invoked on an IO thread, so the copy never runs on the main
     * thread, and a failure surfaces in [importError] rather than vanishing --
     * a share-in that quietly does nothing looks like a broken app.
     */
    fun import(displayName: String, open: () -> InputStream?) = viewModelScope.launch {
        _importError.value = withContext(Dispatchers.IO) {
            val id = try {
                val stream = open() ?: error("could not open the shared file")
                stream.use { store.import(displayName, it) }
            } catch (e: Exception) {
                return@withContext ImportError.Failed(
                    e.message ?: e::class.java.simpleName,
                )
            }

            // Reject unsignable input now, while the user still has the context
            // of having just shared it -- not later, possibly after a PIN entry.
            val rejection = PdfValidator.validate(requireNotNull(store.get(id)).head)
            if (rejection != null) {
                store.delete(id)
                ImportError.Rejected(rejection)
            } else {
                null
            }
        }
        refresh()
    }

    fun delete(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.delete(id) }
        if (_detail.value?.id == id) _detail.value = null
        refresh()
    }

    fun open(id: String) = viewModelScope.launch {
        _detail.value = withContext(Dispatchers.IO) { loadDetail(id) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    /**
     * Removes a signature and every signature after it by truncating the head
     * file to [truncateTo], a revision boundary derived from the PDF.
     */
    fun deleteSignatureCascade(id: String, truncateTo: Long) = viewModelScope.launch {
        _detail.value = withContext(Dispatchers.IO) {
            store.truncate(id, truncateTo)
            loadDetail(id)
        }
        refresh()
    }

    fun reloadDetail(id: String) = open(id)

    private fun loadDetail(id: String): DocumentDetailUi? {
        val document = store.get(id) ?: return null
        val signatures = runCatching { SignatureInspector.inspect(document.head) }
            .getOrDefault(emptyList())
        // Revision boundaries come from the PDF, so imported signatures are
        // handled exactly like ones this app made.
        val truncationLengths = signatures.indices.map {
            PdfRevisions.truncationLengthFor(document.head, it)
        }
        return DocumentDetailUi(
            id = document.id,
            displayName = document.displayName,
            head = document.head,
            rows = SignatureRows.build(signatures, truncationLengths),
        )
    }

    private fun countSignatures(head: File): Int =
        runCatching { SignatureInspector.inspect(head).size }.getOrDefault(0)
}
