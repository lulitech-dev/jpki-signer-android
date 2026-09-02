package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lulitech.jpkisigner.data.DocumentSigner
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.SignFailure
import dev.lulitech.jpkisigner.data.SignatureRow
import dev.lulitech.jpkisigner.data.SignatureRows
import dev.lulitech.jpkisigner.data.describe
import dev.lulitech.jpkisigner.jpki.CardProblem
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.pdf.PdfRejection
import dev.lulitech.jpkisigner.pdf.PdfRevisions
import dev.lulitech.jpkisigner.pdf.PdfValidator
import dev.lulitech.jpkisigner.pdf.SignParams
import dev.lulitech.jpkisigner.pdf.SignatureInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * How many signatures the file carries, or null when it could not be read.
     *
     * Null is not 0. "No signatures" is a claim about the document, and a file we
     * failed to parse is not one we can make it about -- it used to borrow the
     * sentence and sit in the library looking plainly unsigned. Same distinction
     * [DocumentDetailUi.unreadable] draws one screen down.
     */
    val signatureCount: Int?,
)

/** Screen 2's view of one document. */
data class DocumentDetailUi(
    val id: String,
    val displayName: String,
    val head: File,
    val rows: List<SignatureRow>,
    /**
     * The document could not be read, so [rows] is not its history -- it is
     * simply what we have, which is nothing.
     *
     * Distinguished because reading one costs a full in-memory parse of a file
     * whose size is not ours to bound, so `OutOfMemoryError` is an expected
     * outcome. Every failure used to fall back to an empty list, which renders
     * identically to a genuinely unsigned document: a file too large to parse
     * looked unsigned, and silently offered no way to remove anything from it.
     */
    val unreadable: Boolean = false,
    /**
     * [unreadable] because the document did not fit in memory, rather than
     * because it is damaged.
     *
     * Reading the history holds the whole file plus what PDFBox builds from it,
     * so `OutOfMemoryError` is an expected outcome on a large document and a
     * small device. It used to borrow "this document could not be read" -- the
     * sentence reserved for a file that really is broken -- which tells the owner
     * of a perfectly sound PDF that their document is damaged. Same distinction
     * as [SignatureIntegrity.UNCHECKED] against MISMATCH: a limit of this app is
     * not a finding about the file.
     */
    val tooLarge: Boolean = false,
    /**
     * The file length [rows] were read from.
     *
     * Travels with the boundaries derived from it, because a boundary is only a
     * boundary of those bytes -- see `DocumentStore.truncate`. Zero when nothing
     * was read, which no removable row can exist under.
     */
    val sourceLength: Long = 0L,
)

/** The signing sheet: which document it is for, and where the flow has got to. */
data class SigningUi(
    val documentId: String,
    val state: SigningState,
    /**
     * The user has been shown that only one attempt remains and asked to proceed
     * anyway, so the run may spend it. Carried on the sheet rather than on the
     * armed run because it survives the trip back to the form, where the PIN has
     * to be retyped -- which is the point: the one attempt that can lock the card
     * is not spent on a PIN still sitting in a field from last time.
     */
    val acceptLastAttempt: Boolean = false,
)

/**
 * @param io where the file work goes. Injectable for one reason: a plain JVM test
 *   drives `Dispatchers.Main` through `Dispatchers.setMain`, but a coroutine that
 *   has hopped to the real IO dispatcher resumes back onto Main on a background
 *   thread at a time no test controls -- including the refresh this class starts
 *   in its own `init`. Resuming after a test has reset Main takes the coroutine
 *   machinery down with it, in whichever test happened to be running. Handing the
 *   tests one dispatcher for both ends makes that ordering theirs to decide.
 */
class MainViewModel(
    private val store: DocumentStore,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private val signer = DocumentSigner(store)

    private val _documents = MutableStateFlow<List<DocumentUi>>(emptyList())
    val documents: StateFlow<List<DocumentUi>> = _documents.asStateFlow()

    private val _detail = MutableStateFlow<DocumentDetailUi?>(null)
    val detail: StateFlow<DocumentDetailUi?> = _detail.asStateFlow()

    /** Last import failure, for display. Import must never fail silently. */
    private val _importError = MutableStateFlow<ImportError?>(null)
    val importError: StateFlow<ImportError?> = _importError.asStateFlow()

    private val _signing = MutableStateFlow<SigningUi?>(null)
    val signing: StateFlow<SigningUi?> = _signing.asStateFlow()

    /**
     * A document is being read for the detail screen.
     *
     * Opening one parses the whole file and verifies a signature per row, which
     * on a large document is seconds. Nothing was published until it finished, so
     * a tap on a library row did nothing at all for that time -- indistinguishable
     * from a tap that missed.
     */
    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    /**
     * Serial number of the newest detail request.
     *
     * A load that finishes after a newer request was made publishes nothing. Two
     * loads race on the IO dispatcher and resume in whatever order they finish,
     * so without this a slow read of the first document tapped could land *after*
     * a fast read of the second and put the wrong document on screen -- or put
     * back one the user had just closed or deleted.
     *
     * Atomic, and claimed by [open] *before* it launches anything: the number
     * belongs to the moment the user tapped, not to whenever a dispatcher gets
     * round to the coroutine body. Taking it inside the launch made the guard
     * depend on whether the dispatcher runs that body eagerly, which is the kind
     * of thing that holds on `Dispatchers.Main.immediate` and stops holding under
     * test. Atomic because [reloadDetail] reaches this from the NFC binder
     * thread, the same reason [pendingRun] is.
     */
    private val detailRequest = AtomicInteger(0)

    /**
     * One armed signing run: what the next card tap should do, and the PIN it
     * would send.
     *
     * The PIN is held here rather than only captured inside [dispatch] so that a
     * run which is never dispatched can still be wiped. Dropping the lambda on its
     * own left the array on the heap until a collector happened to reach it --
     * undoing the reason the whole path takes a `CharArray` instead of a `String`.
     */
    private class PendingRun(
        val documentId: String,
        val pin: CharArray,
        val dispatch: (JpkiSession) -> Unit,
    )

    /**
     * The armed run, or null to ignore card taps.
     *
     * Atomic because the NFC callback runs on a binder thread, and a fresh one per
     * tap: [onCard] takes the run and clears the slot in a single step, so two
     * taps in quick succession cannot both find it set and spend two PIN attempts
     * on one user action. Every other site that replaces or drops the run does so
     * with the same take-and-clear, then wipes the PIN it took.
     *
     * Held here rather than on the activity so that a configuration change cannot
     * strand a run in flight. When the activity owned it, a rotation mid-signing
     * destroyed the instance the callback was posting its result to: the user saw
     * nothing at all, having already spent an attempt on a key that takes a trip
     * to a municipal window to unblock.
     */
    private val pendingRun = AtomicReference<PendingRun?>(null)

    /** Drops any armed run and wipes the PIN it was holding. */
    private fun discardPendingRun() {
        pendingRun.getAndSet(null)?.pin?.fill(' ')
    }

    /**
     * The ViewModel is going away, so nothing will ever dispatch an armed run.
     * Its PIN would otherwise outlive every screen that could have used it.
     */
    override fun onCleared() {
        super.onCleared()
        discardPendingRun()
    }

    fun clearImportError() {
        _importError.value = null
    }

    init {
        // Once per ViewModel, never per refresh: a sweep is not synchronised
        // against an import, and running one on every listing would eventually
        // race a copy in progress. The age bound in the store is what makes even
        // this safe -- see `DocumentStore.sweepAbandonedImports`.
        viewModelScope.launch {
            withContext(io) { orElse(Unit) { store.sweepAbandonedImports() } }
        }
        refresh()
    }

    /**
     * Re-reads the library.
     *
     * A listing that throws leaves the previous one in place rather than replacing
     * it with an empty list. "You have no documents" is a claim too, and it sits
     * next to a hint explaining how to add one -- which is a bad thing to show
     * someone whose documents are all still there.
     */
    fun refresh() = viewModelScope.launch {
        val listed = withContext(io) {
            orElse(null) {
                store.list().map { document ->
                    DocumentUi(
                        id = document.id,
                        displayName = document.displayName,
                        // Read from the PDF, never from local bookkeeping, so
                        // co-signers and pre-existing signatures are counted too.
                        //
                        // A count only. Going through the inspector for it meant a
                        // full in-memory read and one RSA verification per
                        // signature, for every document, every time the list was
                        // refreshed -- to render a number on a row. Null where the
                        // file would not parse, which the row says rather than
                        // rendering as "no signatures".
                        signatureCount = SignatureInspector.count(document.head),
                    )
                }
            }
        }
        if (listed != null) _documents.value = listed
    }

    /**
     * Copies an incoming PDF in.
     *
     * [open] is invoked on an IO thread, so the copy never runs on the main
     * thread, and a failure surfaces in [importError] rather than vanishing --
     * a share-in that quietly does nothing looks like a broken app.
     */
    fun import(displayName: String, open: () -> InputStream?) = viewModelScope.launch {
        val failure = withContext(io) {
            val id = try {
                val stream = open() ?: error("could not open the shared file")
                stream.use { store.import(displayName, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext ImportError.Failed(describe(e))
            }

            // Reject unsignable input now, while the user still has the context
            // of having just shared it -- not later, possibly after a PIN entry.
            //
            // A document the store cannot read back counts as unreadable rather
            // than as a successful import, so the library never gains a row that
            // cannot be opened.
            //
            // Guarded, like every other read here: PdfValidator parses a whole
            // user file, so OutOfMemoryError is an expected outcome and not a
            // broken VM -- and an Error escaping a viewModelScope coroutine takes
            // the process down instead of rejecting the file.
            val rejection: PdfRejection? = orElse(PdfRejection.UNREADABLE) {
                val head = store.get(id)?.head
                if (head == null) PdfRejection.UNREADABLE else PdfValidator.validate(head)
            }
            if (rejection != null) {
                orElse(Unit) { store.delete(id) }
                ImportError.Rejected(rejection)
            } else {
                null
            }
        }
        // Raises an error, never clears one. Several share-ins in a row is
        // ordinary -- a file manager sends them one after another -- and
        // assigning this unconditionally let a later success take an earlier
        // rejection off the screen before anyone had read it, which is the
        // silent-failure this whole field exists to prevent. The dialog goes
        // away through [clearImportError] and nothing else.
        if (failure != null) _importError.value = failure
        refresh()
    }

    fun delete(id: String) = viewModelScope.launch {
        withContext(io) { orElse(Unit) { store.delete(id) } }
        if (_detail.value?.id == id) closeDetail()
        refresh()
    }

    fun open(id: String): Job {
        val request = detailRequest.incrementAndGet()
        _detailLoading.value = true
        return viewModelScope.launch {
            val loaded = withContext(io) { loadDetail(id) }
            // Someone asked for something else while this was reading -- another
            // document, or the screen being closed. That request owns the screen
            // now, and owns clearing the spinner too.
            if (request != detailRequest.get()) return@launch
            _detail.value = loaded
            _detailLoading.value = false
        }
    }

    fun closeDetail() {
        // Bumped so a read still in flight cannot put the document back on screen
        // after the user has left it.
        detailRequest.incrementAndGet()
        _detailLoading.value = false
        _detail.value = null
    }

    /**
     * Removes a signature and every signature after it by truncating the head
     * file to [truncateTo], a revision boundary derived from the PDF.
     *
     * @param provenLength the length the document had when [truncateTo] was
     *   derived from it. The store refuses the truncation if the file is no
     *   longer that file.
     */
    fun deleteSignatureCascade(id: String, truncateTo: Long, provenLength: Long): Job {
        // Claimed before launching, exactly as [open] does. The truncation lands
        // on disk either way, but the reload after it must not put a document
        // back on screen that the user has since closed -- which is what this
        // published unconditionally, undoing the guard [open] exists to provide.
        val request = detailRequest.incrementAndGet()
        return viewModelScope.launch {
            val loaded = withContext(io) {
                // Guarded because [truncateTo] came from an earlier read: the
                // store rejects it if the document is not the one it was proved
                // against, and the reload below then shows the boundaries as they
                // actually are. A stale offset is a reason to redraw the screen,
                // not to take the process down.
                orElse(Unit) { store.truncate(id, truncateTo, provenLength) }
                loadDetail(id)
            }
            // Unconditional: the file on disk changed whatever became of the
            // screen, so the library has to be re-read either way.
            refresh()
            if (request != detailRequest.get()) return@launch
            _detail.value = loaded
            _detailLoading.value = false
        }
    }

    fun reloadDetail(id: String) = open(id)

    // --- signing ------------------------------------------------------------

    /** Opens the signing sheet on [documentId], with the form empty. */
    fun openSigningSheet(documentId: String) {
        _signing.value = SigningUi(documentId, SigningState.Form)
    }

    /**
     * Closes the signing sheet, unless a card operation is under way.
     *
     * A run in flight cannot be stopped -- the VERIFY has been sent and the
     * signature is being written -- so closing on top of one would tell the user
     * they had cancelled a signature that still lands.
     *
     * @return false when the sheet was left open because a run has it.
     */
    fun closeSigningSheet(): Boolean {
        val state = _signing.value?.state
        if (state == SigningState.Working) return false

        // Take the run rather than ask whether one is armed. [onCard] takes and
        // clears the slot on a binder thread *before* it can publish Working, so a
        // state read alone leaves a window in which cancelling closes the sheet on
        // top of a run already under way: the signature lands, an attempt is
        // spent, and every state this run publishes afterwards is dropped because
        // the sheet it belonged to is gone. An empty slot while the sheet still
        // says WaitingForCard means exactly that, so refuse.
        //
        // Wiping, not just dropping: cancelling from WaitingForCard is the
        // ordinary way out of an armed run, and the PIN it holds goes with it.
        val taken = pendingRun.getAndSet(null)
        if (taken == null && state == SigningState.WaitingForCard) return false
        taken?.pin?.fill(' ')
        _signing.value = null
        return true
    }

    /**
     * Arms the next card tap with one signing run.
     *
     * @param pin wiped once the run finishes, however it finishes. Taken as a
     *   `CharArray` rather than a `String` precisely so it can be.
     */
    fun startSigning(pin: CharArray, params: SignParams) {
        val current = _signing.value
        if (current == null) {
            pin.fill(' ')
            return
        }
        val documentId = current.documentId
        val acceptLastAttempt = current.acceptLastAttempt
        _signing.value = current.copy(state = SigningState.WaitingForCard)
        // Swap, so that anything already armed has its PIN wiped rather than
        // being quietly overwritten.
        pendingRun.getAndSet(
            PendingRun(documentId, pin) { session ->
                runSigning(session, documentId, pin, params, acceptLastAttempt)
            },
        )?.pin?.fill(' ')
    }

    /**
     * Returns to the form, licensed to spend the last attempt.
     *
     * Offered only after the card has told us the counter is at one. The PIN is
     * deliberately not carried over -- retyping it is the confirmation, on the one
     * attempt whose loss means a trip to a municipal window.
     */
    fun useLastAttempt() {
        val current = _signing.value ?: return
        if (current.state == SigningState.Working) return
        discardPendingRun()
        _signing.value = SigningUi(current.documentId, SigningState.Form, acceptLastAttempt = true)
    }

    /**
     * Invoked by the NFC reader, on a binder thread.
     *
     * The run is taken *and cleared* before it starts, not when it finishes. A
     * card lifted and tapped again mid-run arrives on a second binder thread, and
     * clearing the slot afterwards left that tap a live run to invoke -- one user
     * action, two VERIFYs, two attempts off the counter.
     */
    fun onCard(session: JpkiSession) {
        pendingRun.getAndSet(null)?.dispatch?.invoke(session)
    }

    /**
     * Invoked by the NFC reader, on a binder thread, when a tag was discovered but
     * no session could be opened on it -- the wrong kind of card, or one held
     * where it could not be read.
     *
     * Reported rather than ignored. The tap has already consumed the armed run, so
     * staying silent left the sheet saying "hold your card" over a run that no
     * longer existed, waiting for a card that had come and gone. Nothing was sent
     * to any card on this path, so nothing was spent.
     */
    fun onCardUnusable(problem: CardProblem) {
        val taken = pendingRun.getAndSet(null) ?: return
        taken.pin.fill(' ')
        setSigningState(taken.documentId, SigningState.Failed(SignFailure.Card(problem)))
    }

    private fun runSigning(
        session: JpkiSession,
        documentId: String,
        pin: CharArray,
        params: SignParams,
        acceptLastAttempt: Boolean,
    ) {
        // Every state write below is a StateFlow assignment, which is safe from
        // the binder thread this runs on, and the screen collecting it is whichever
        // one is alive now -- not the one that started the run.
        setSigningState(documentId, SigningState.Working)
        try {
            signer.sign(session, documentId, pin, params, acceptLastAttempt = acceptLastAttempt)
            setSigningState(documentId, SigningState.Succeeded)
            reloadDetail(documentId)
            refresh()
        } catch (e: Throwable) {
            // Throwable, not Exception. This runs on an NFC binder thread, so
            // anything that escapes here kills the process -- with no message, and
            // after an attempt has already been spent on a key that needs a
            // municipal window to unblock. That is the exact outcome this flow
            // exists to prevent, and it is reachable: the sign path parses and
            // buffers whole user documents, whose size is not ours to bound, so
            // OutOfMemoryError is an expected result here rather than a sign of a
            // broken VM. Reporting it as a failure is strictly better than dying.
            val failure = (e as? DocumentSigner.Failure)?.reason
                ?: SignFailure.Unexpected(describe(e))
            setSigningState(documentId, SigningState.Failed(failure))
        } finally {
            // onCard already took and cleared the slot, so nothing is dropped
            // here; the array it handed over is this one, and it is wiped however
            // the run ended.
            pin.fill(' ')
        }
    }

    /** Advances the sheet, but only while it is still the one this run belongs to. */
    private fun setSigningState(documentId: String, state: SigningState) {
        _signing.update { current ->
            if (current?.documentId == documentId) current.copy(state = state) else current
        }
    }

    // --- reading ------------------------------------------------------------

    private fun loadDetail(id: String): DocumentDetailUi? {
        val document = store.get(id) ?: return null

        // Which kind of failure, not just that there was one. Running out of
        // memory on a large file and failing to parse a damaged one both end with
        // no signatures to show, and they are different things to tell the owner
        // of the document.
        var ranOutOfMemory = false
        fun <T> read(block: () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                ranOutOfMemory = true
                null
            } catch (e: Throwable) {
                null
            }

        // One read serving both. Each of these used to pull its own full copy of
        // the document into memory, so opening a large file cost three times its
        // size at peak rather than two.
        val bytes = read { document.head.readBytes() }
        val signatures = bytes?.let { read { SignatureInspector.inspect(it) } }
        // Revision boundaries come from the PDF, so imported signatures are
        // handled exactly like ones this app made.
        val truncationLengths = bytes?.let { read { PdfRevisions.truncationLengths(it) } }
        val failed = bytes == null || signatures == null || truncationLengths == null
        return DocumentDetailUi(
            id = document.id,
            displayName = document.displayName,
            head = document.head,
            rows = SignatureRows.build(signatures.orEmpty(), truncationLengths.orEmpty()),
            // Null means the read threw rather than found nothing, and the two
            // must not look the same on screen: an empty list reads as "this
            // document is unsigned", which is a claim we cannot make about a file
            // we failed to parse.
            unreadable = failed,
            tooLarge = failed && ranOutOfMemory,
            sourceLength = bytes?.size?.toLong() ?: 0L,
        )
    }

    /**
     * Runs [block], falling back to [fallback] if it throws.
     *
     * An uncaught throw inside a `viewModelScope` coroutine kills the process, and
     * none of the reads here are worth that: a document that will not parse should
     * show as empty, not take the app down with it.
     *
     * `Throwable` rather than `Exception` deliberately. These blocks parse and
     * allocate whole user files, whose size is not ours to bound, so
     * `OutOfMemoryError` -- and a `StackOverflowError` out of a pathologically
     * nested PDF -- are expected outcomes here rather than signs of a broken VM.
     *
     * `CancellationException` is the one thing passed through: it is how a
     * cancelled scope unwinds, and swallowing it would leave a coroutine running
     * past the point its ViewModel was cleared.
     */
    private fun <T> orElse(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback
        }
}
