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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.lulitech.jpkisigner.data.DocumentSharing
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.jpki.NfcCardReader
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
import dev.lulitech.jpkisigner.ui.messageFor
import dev.lulitech.jpkisigner.ui.pinErrorFor
import dev.lulitech.jpkisigner.ui.theme.JpkiSignerTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single activity hosting both screens and the signing flow.
 *
 * PDFs arrive only by share-in (ACTION_SEND) or "Open with" (ACTION_VIEW), and
 * leave only through the share sheet; there are no SAF pickers.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: DocumentStore
    private lateinit var reader: NfcCardReader

    /**
     * Whether the NFC radio is currently on, re-read on every resume.
     *
     * Compose state rather than a direct read at composition time: the user can
     * switch NFC on in Settings and come straight back, and the signing sheet has
     * to stop refusing at that point without needing the screen to be rebuilt.
     */
    private val nfcEnabled = mutableStateOf(false)

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
        nfcEnabled.value = reader.isEnabled
        if (reader.isEnabled) {
            // Straight to the ViewModel. The run it dispatches to outlives this
            // activity, so a card tapped after a recreation still reaches the
            // signing flow that was already under way.
            // Both halves go to the ViewModel: a tap that cannot be turned into a
            // session still consumed the armed run, so it has to be reported
            // rather than dropped on the binder thread.
            reader.start(viewModel::onCard, viewModel::onCardUnusable)
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
        val signing by viewModel.signing.collectAsState()

        // Saveable, because not every configuration change is handled in place:
        // `locale` and `layoutDirection` are deliberately absent from the
        // manifest's configChanges so AppCompat can recreate on a language switch,
        // and uiMode, density and font scale are not listed either.
        var showAbout by rememberSaveable { mutableStateOf(false) }
        var showNotices by rememberSaveable { mutableStateOf(false) }
        var showLanguages by rememberSaveable { mutableStateOf(false) }
        // The one piece of signing state that stays in the composition. Its PIN
        // is a String and cannot be wiped, so it is deliberately not kept
        // anywhere longer-lived than the screen showing the field.
        //
        // Saved across a recreation, but *without* the PIN -- see the saver. The
        // signing flow itself lives in the ViewModel and survives regardless, so
        // without this a language switch mid-form reopened the sheet with the
        // reason and location the user had already typed thrown away.
        var form by rememberSaveable(stateSaver = SigningFormSaver) {
            mutableStateOf(SigningForm())
        }
        val scope = rememberCoroutineScope()
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // A card operation in flight cannot be cancelled, so the sheet must
            // not be swipeable away while one is: dismissing it would leave the
            // user believing they had stopped a signature that still lands.
            confirmValueChange = { signing?.state != SigningState.Working },
        )

        // Reader mode is on for as long as this screen is, and says nothing when
        // the radio is off or absent -- so the signing sheet has to say it.
        val nfcProblem = when {
            !reader.isAvailable -> stringResource(R.string.sign_nfc_unavailable)
            !nfcEnabled.value -> stringResource(R.string.sign_nfc_disabled)
            else -> null
        }

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
                            viewModel.openSigningSheet(current.id)
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
                            // Joined, so the screen can hold the swept rows off
                            // screen until the reloaded detail is published rather
                            // than letting them spring back mid-delete.
                            viewModel.deleteSignatureCascade(current.id, truncateTo).join()
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

        signing?.let { sheet ->
            fun close() {
                // Same reason as confirmValueChange above: there is nothing to
                // dismiss to while the card is being talked to.
                //
                // Putting the sheet back, not just declining. Material3 asks
                // *after* it has already animated to Hidden, and this composable
                // stays in the tree either way -- so a bare refusal left it parked
                // off screen with a run still under way: every state that run went
                // on to publish rendered where nobody could see it, the outcome
                // included, and reopening the sheet did nothing because it had
                // never been removed.
                if (sheet.state == SigningState.Working) {
                    scope.launch { sheetState.show() }
                    return
                }
                scope.launch {
                    // Hide before the sheet leaves the tree. Taking it out while
                    // still Expanded skipped the exit animation -- and, because
                    // this SheetState outlives each opening, left it Expanded for
                    // the next one, which then appeared with no entrance animation
                    // either. On a swipe Material3 has already hidden it, so this
                    // is a no-op there.
                    sheetState.hide()
                    // Asked after the animation rather than before it: deciding
                    // and taking the armed run has to stay one atomic step, so
                    // there is no way to ask permission without also committing.
                    // The ViewModel refuses for one case this cannot see -- a run
                    // already taken by a card tap that has not published Working
                    // yet -- and then the sheet comes back.
                    if (viewModel.closeSigningSheet()) {
                        form = SigningForm()
                    } else {
                        sheetState.show()
                    }
                }
            }

            ModalBottomSheet(
                onDismissRequest = ::close,
                sheetState = sheetState,
            ) {
                SigningSheet(
                    state = sheet.state,
                    form = form,
                    pinError = pinErrorFor(form.pin),
                    nfcProblem = nfcProblem,
                    acceptLastAttempt = sheet.acceptLastAttempt,
                    onFormChange = { form = it },
                    onStart = {
                        // Snapshot the form on the main thread; the run must never
                        // read Compose state itself.
                        val params = SignParams(
                            reason = form.reason.ifBlank { null },
                            location = form.location.ifBlank { null },
                            // The installed version, not a literal: a hardcoded
                            // one drifts from versionName without failing.
                            applicationVersion = versionName(),
                        )
                        val pin = form.pin.toCharArray()
                        // Drop the PIN from the form as soon as it is snapshotted.
                        // A String cannot be wiped, so the only thing that helps is
                        // holding it for less time, and the field is not shown
                        // again anywhere in the rest of this flow.
                        form = form.copy(pin = "")
                        // The ViewModel owns the run and wipes the array when it
                        // ends, however it ends.
                        viewModel.startSigning(pin, params)
                    },
                    // Back to the form, licensed to spend the last attempt. The
                    // PIN field is empty again on purpose -- retyping it is the
                    // confirmation.
                    onUseLastAttempt = viewModel::useLastAttempt,
                    onDismiss = ::close,
                )
            }
        }
    }

    @Composable
    private fun importErrorMessage(error: ImportError): String = when (error) {
        is ImportError.Failed -> error.message
        // The same sentence the signing sheet uses. Why a file cannot be signed
        // does not depend on whether we found out as it arrived or later.
        is ImportError.Rejected -> messageFor(error.reason)
    }

    /**
     * Read from the installed package rather than BuildConfig, which would mean
     * turning the buildConfig feature on for a single string.
     */
    private fun versionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

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

        // The read grant on a shared URI lasts only as long as this activity, so
        // the copy is started now rather than deferred to whenever the document is
        // first opened. Started, not performed: the ViewModel opens the stream on
        // an IO thread, because a share-in must not copy a large file on the main
        // one. It outlives a configuration change, which is as long as the grant
        // needs to hold.
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

    /**
     * Guarded, because `query` throws rather than returning null on the URIs this
     * app is actually handed: `IllegalArgumentException` for a `file://` one --
     * which "Open with" still sends -- and `SecurityException` when the grant is
     * missing. Both used to crash the activity before its first frame, on a path
     * `openInputStream` would then have read perfectly well. A name we cannot ask
     * for is no reason to fail an import, let alone to die.
     */
    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
}

/**
 * Saves the signing form across an activity recreation -- **without the PIN**.
 *
 * Saved instance state is written to a Bundle the system holds, and may be
 * persisted to disk when the process is killed. A PIN has no business being
 * there, and it is the one field the user can retype in seconds; reason and
 * location are ordinary text and are the ones worth keeping.
 */
private val SigningFormSaver: Saver<SigningForm, Any> = listSaver(
    save = { listOf(it.reason, it.location) },
    restore = { SigningForm(reason = it[0], location = it[1]) },
)
