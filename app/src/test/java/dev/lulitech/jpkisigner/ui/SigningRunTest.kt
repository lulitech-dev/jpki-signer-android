package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.MINIMAL_PDF
import dev.lulitech.jpkisigner.jpki.ApduTransceiver
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.pdf.SignParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch

/**
 * What must be true of an armed signing run that is never dispatched.
 *
 * The PIN travels as a `CharArray` for one reason: so it can be wiped. Dropping
 * an armed run without wiping it -- which cancelling from "hold your card" used
 * to do -- left the PIN on the heap for as long as it took a collector to reach
 * it, which is the whole thing the `CharArray` was for.
 */
// setMain / resetMain are still flagged experimental; they are the only way to
// give viewModelScope a dispatcher in a plain JVM test.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SigningRunTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var store: DocumentStore
    private lateinit var viewModel: MainViewModel
    private lateinit var documentId: String

    /**
     * Held so a test can clear it, which is what actually invokes `onCleared` --
     * that method is protected, and this is the path the framework itself takes.
     */
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        // viewModelScope resolves Dispatchers.Main eagerly, and there is no Looper
        // in a plain JVM test.
        Dispatchers.setMain(dispatcher)
        store = DocumentStore(temp.newFolder("documents"))
        // A document the rehearsal accepts, so a run that is meant to reach the
        // card actually gets there instead of being refused before the first APDU.
        documentId = store.import("contract.pdf", MINIMAL_PDF.inputStream())

        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(store, dispatcher) as T
            },
        )[MainViewModel::class.java]
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pin() = "ABC123".toCharArray()

    private fun CharArray.isWiped() = all { it == ' ' }

    @Test
    fun `cancelling while waiting for the card wipes the pin`() {
        val pin = pin()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin, SignParams())
        assertEquals(SigningState.WaitingForCard, viewModel.signing.value?.state)
        assertFalse("precondition: the pin is still readable", pin.isWiped())

        viewModel.closeSigningSheet()

        assertTrue("the pin must not survive a cancelled run", pin.isWiped())
    }

    /** Nothing will ever dispatch the run once the ViewModel is gone. */
    @Test
    fun `clearing the view model wipes an armed pin`() {
        val pin = pin()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin, SignParams())

        viewModelStore.clear()

        assertTrue(pin.isWiped())
    }

    /** Re-arming must not orphan the PIN the previous run was holding. */
    @Test
    fun `re-arming wipes the pin of the run it replaces`() {
        val first = pin()
        val second = pin()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(first, SignParams())
        viewModel.startSigning(second, SignParams())

        assertTrue("the replaced run's pin must be wiped", first.isWiped())
        assertFalse("the newly armed run's pin must not be", second.isWiped())
    }

    @Test
    fun `a run with no sheet open wipes the pin rather than arming`() {
        val pin = pin()
        viewModel.startSigning(pin, SignParams())

        assertTrue(pin.isWiped())
        // Nothing was armed, so a card tap must do nothing at all.
        viewModel.onCard(JpkiSession(RefusingCard))
    }

    /** A dispatched run wipes it too, however it ends -- here, in failure. */
    @Test
    fun `a dispatched run wipes the pin when it fails`() {
        val pin = pin()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin, SignParams())

        viewModel.onCard(JpkiSession(RefusingCard))

        assertTrue(pin.isWiped())
        assertTrue(
            "the failure must reach the sheet: ${viewModel.signing.value?.state}",
            viewModel.signing.value?.state is SigningState.Failed,
        )
    }

    /**
     * Cancelling and a card tap race for the same run, and the atomic slot is what
     * settles it: whichever takes the run, the other must do nothing at all.
     *
     * This is the cancel-wins half; the test below is the tap-wins half. Together
     * they are why a cancel can no longer close the sheet over a live run, leaving
     * the signature to land with nothing on screen and an attempt already spent.
     */
    @Test
    fun `a cancelled run is not dispatched by a later tap`() {
        val pin = pin()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin, SignParams())

        assertTrue("the sheet was closable", viewModel.closeSigningSheet())

        // Nothing is armed, so the tap must not so much as select the application.
        viewModel.onCard(JpkiSession(ExplodingCard))
        assertTrue(pin.isWiped())
        assertNull(viewModel.signing.value)
    }

    /**
     * A VERIFY has been sent and the signature is being written; there is nothing
     * left to cancel, and saying otherwise would be a lie about a signature that
     * still lands.
     */
    @Test
    fun `the sheet refuses to close while the card is being talked to`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : ApduTransceiver {
            override fun transceive(command: ByteArray): ByteArray {
                reached.countDown()
                release.await()
                return byteArrayOf(0x6A, 0x82.toByte())
            }
        }

        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin(), SignParams())
        // onCard runs on a binder thread on a device; a plain thread here is the
        // same thing as far as the ViewModel is concerned.
        val tap = Thread { viewModel.onCard(JpkiSession(blocking)) }
        tap.start()
        try {
            reached.await()
            assertEquals(SigningState.Working, viewModel.signing.value?.state)
            assertFalse("a run in flight must not be closable", viewModel.closeSigningSheet())
            assertEquals(
                "and the sheet must still be the one that run belongs to",
                documentId,
                viewModel.signing.value?.documentId,
            )
        } finally {
            release.countDown()
            tap.join()
        }
    }

    /** Answers every command with "file not found", so the run cannot succeed. */
    private object RefusingCard : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray =
            byteArrayOf(0x6A, 0x82.toByte())
    }

    /** For taps that must never happen: any APDU at all is the failure. */
    private object ExplodingCard : ApduTransceiver {
        override fun transceive(command: ByteArray): ByteArray =
            throw AssertionError("a cancelled run must send no APDU")
    }
}
