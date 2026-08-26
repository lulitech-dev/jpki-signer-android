package dev.lulitech.jpkisigner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.lulitech.jpkisigner.data.DocumentSharing
import dev.lulitech.jpkisigner.data.DocumentSigner
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.jpki.NfcCardReader
import dev.lulitech.jpkisigner.pdf.ChangesNotPermittedException
import dev.lulitech.jpkisigner.pdf.PdfRejection
import dev.lulitech.jpkisigner.pdf.SignParams
import dev.lulitech.jpkisigner.ui.AboutDialog
import dev.lulitech.jpkisigner.ui.DocumentListScreen
import dev.lulitech.jpkisigner.ui.ImportError
import dev.lulitech.jpkisigner.ui.DocumentScreen
import dev.lulitech.jpkisigner.ui.LanguageDialog
import dev.lulitech.jpkisigner.ui.MainViewModel
import dev.lulitech.jpkisigner.ui.NoticesScreen
import dev.lulitech.jpkisigner.ui.SigningForm
import dev.lulitech.jpkisigner.ui.SigningSheet
import dev.lulitech.jpkisigner.ui.SigningState
import dev.lulitech.jpkisigner.ui.theme.JpkiSignerTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/**
 * Single activity hosting both screens and the signing flow.
 *
 * PDFs arrive only by share-in (ACTION_SEND) or "Open with" (ACTION_VIEW), and
 * leave only through the share sheet; there are no SAF pickers.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: DocumentStore
    private lateinit var signer: DocumentSigner
    private lateinit var reader: NfcCardReader

    /**
     * What the NFC callback should do on the next tap, or null to ignore taps.
     *
     * Volatile because the callback runs on a binder thread. Reading Compose or
     * View state directly from there can observe stale values -- that already
     * caused the app to validate a signature PIN against the authentication
     * key's rules once.
     */
    @Volatile
    private var pendingRun: ((JpkiSession) -> Unit)? = null

    /**
     * Held by the activity rather than created inside the composition, so an
     * import arriving via onNewIntent can refresh it directly. A retained
     * ViewModel does not re-run its init when the composition restarts.
     */
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(store) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A PIN is entered on this screen.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        PDFBoxResourceLoader.init(applicationContext)

        store = DocumentStore(File(filesDir, "documents").apply { mkdirs() })
        signer = DocumentSigner(store)
        reader = NfcCardReader(this)

        setContent { JpkiSignerTheme { App() } }

        // Only on a genuinely new launch. On a recreate (rotation, process
        // death) the original intent is still attached, and re-handling it would
        // import the same file again.
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the activity's intent, or a later recreate would replay the
        // stale one instead of this.
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        if (reader.isEnabled) {
            reader.start { session -> pendingRun?.invoke(session) }
        }
    }

    override fun onPause() {
        super.onPause()
        reader.stop()
    }

    // TopAppBar / ModalBottomSheet are still flagged experimental in Material3.
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val documents by viewModel.documents.collectAsState()
        val detail by viewModel.detail.collectAsState()
        val importError by viewModel.importError.collectAsState()

        var showAbout by remember { mutableStateOf(false) }
        var showNotices by remember { mutableStateOf(false) }
        var showLanguages by remember { mutableStateOf(false) }
        var signing by remember { mutableStateOf(false) }
        var signingState by remember { mutableStateOf<SigningState>(SigningState.Form) }
        var form by remember { mutableStateOf(SigningForm()) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // Detail is a screen, not a dialog: back should return to the list.
        BackHandler(enabled = showNotices) { showNotices = false }
        BackHandler(enabled = !showNotices && detail != null) { viewModel.closeDetail() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                showNotices -> stringResource(R.string.notices_title)
                                detail != null -> detail!!.displayName
                                else -> stringResource(R.string.documents_title)
                            },
                        )
                    },
                    actions = {
                        // Only on the library screen; the detail screen's actions
                        // belong to the document.
                        if (detail == null && !showNotices) {
                            // A globe rather than a word: it is readable whichever
                            // language the app is currently in, which matters most
                            // to someone who switched by accident.
                            IconButton(onClick = { showLanguages = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_language),
                                    contentDescription = stringResource(R.string.language_title),
                                )
                            }
                            IconButton(onClick = { showAbout = true }) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = stringResource(R.string.about),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // A visible affordance alongside the gesture; a swipe
                        // alone is undiscoverable.
                        if (showNotices) {
                            IconButton(onClick = { showNotices = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        } else if (detail != null) {
                            IconButton(onClick = viewModel::closeDetail) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val current = detail
                if (showNotices) {
                    NoticesScreen()
                } else if (current == null) {
                    DocumentListScreen(
                        documents = documents,
                        onOpen = viewModel::open,
                        onDelete = viewModel::delete,
                    )
                } else {
                    DocumentScreen(
                        detail = current,
                        onSign = {
                            form = SigningForm()
                            signingState = SigningState.Form
                            signing = true
                        },
                        onShare = { exportName ->
                            startActivity(
                                DocumentSharing.shareIntent(
                                    this@MainActivity,
                                    current.head,
                                    exportName,
                                ),
                            )
                        },
                        onDeleteCascade = { truncateTo ->
                            viewModel.deleteSignatureCascade(current.id, truncateTo)
                        },
                        onBack = viewModel::closeDetail,
                    )
                }
            }
        }

        if (showLanguages) {
            LanguageDialog(onDismiss = { showLanguages = false })
        }

        if (showAbout) {
            AboutDialog(
                versionName = versionName(),
                onShowNotices = {
                    showAbout = false
                    showNotices = true
                },
                onDismiss = { showAbout = false },
            )
        }

        importError?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::clearImportError,
                title = { Text(stringResource(R.string.import_failed_title)) },
                text = { Text(importErrorMessage(message)) },
                confirmButton = {
                    TextButton(onClick = viewModel::clearImportError) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }

        if (signing) {
            val documentId = detail?.id
            ModalBottomSheet(
                onDismissRequest = {
                    signing = false
                    pendingRun = null
                    form = SigningForm()
                },
                sheetState = sheetState,
            ) {
                SigningSheet(
                    state = signingState,
                    form = form,
                    pinError = pinErrorFor(form.pin),
                    onFormChange = { form = it },
                    onStart = {
                        if (documentId == null) return@SigningSheet
                        signingState = SigningState.WaitingForCard
                        // Snapshot the form on the main thread; the callback must
                        // never read Compose state itself.
                        val params = SignParams(
                            reason = form.reason.ifBlank { null },
                            location = form.location.ifBlank { null },
                        )
                        val pin = form.pin.toCharArray()
                        pendingRun = { session ->
                            signingState = SigningState.Working
                            try {
                                signer.sign(session, documentId, pin, params)
                                runOnUiThread {
                                    signingState = SigningState.Succeeded
                                    form = SigningForm()
                                    viewModel.reloadDetail(documentId)
                                }
                            } catch (e: Exception) {
                                val remaining = (e as? DocumentSigner.Failure)?.remainingAttempts
                                // A refusal the user can understand and act on,
                                // rather than an exception string.
                                val message = if (e is ChangesNotPermittedException) {
                                    getString(R.string.sign_changes_not_permitted)
                                } else {
                                    e.message ?: e::class.java.simpleName
                                }
                                runOnUiThread {
                                    signingState = SigningState.Failed(message, remaining)
                                }
                            } finally {
                                // One VERIFY per user action: clear the pending
                                // run so a card left in the field cannot trigger
                                // a second attempt.
                                pendingRun = null
                                pin.fill(' ')
                            }
                        }
                    },
                    onDismiss = {
                        signing = false
                        pendingRun = null
                        form = SigningForm()
                    },
                )
            }
        }
    }

    @Composable
    private fun importErrorMessage(error: ImportError): String = when (error) {
        is ImportError.Failed -> error.message
        is ImportError.Rejected -> stringResource(
            when (error.reason) {
                PdfRejection.ENCRYPTED -> R.string.import_rejected_encrypted
                PdfRejection.NO_PAGES -> R.string.import_rejected_no_pages
                PdfRejection.UNREADABLE -> R.string.import_rejected_unreadable
            },
        )
    }

    /**
     * Read from the installed package rather than BuildConfig, which would mean
     * turning the buildConfig feature on for a single string.
     */
    private fun versionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

    /** Client-side PIN validation, so malformed input never reaches the card. */
    private fun pinErrorFor(pin: String): String? =
        if (pin.isEmpty()) null else JpkiKey.DIGITAL_SIGNATURE.validatePin(pin.toCharArray())

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            // IntentCompat, not Intent.getParcelableExtra(String, Class): the typed
            // overload is API 33+ and minSdk is 26.
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) return

        // The read grant on a shared URI dies with this activity, so copy now.
        val name = displayNameOf(uri) ?: "document.pdf"
        viewModel.import(name) { contentResolver.openInputStream(uri) }

        // Consume it. An import intent must act exactly once; leaving it in place
        // makes every return to the app add another copy of the same document.
        intent?.apply {
            action = null
            data = null
            removeExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun displayNameOf(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment
}
