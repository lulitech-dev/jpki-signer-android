package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.MINIMAL_PDF
import dev.lulitech.jpkisigner.data.SignFailure
import dev.lulitech.jpkisigner.jpki.ApduTransceiver
import dev.lulitech.jpkisigner.jpki.CardProblem
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.jpki.StatusWord
import dev.lulitech.jpkisigner.pdf.SignParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The floor at two attempts, and the deliberate way past it.
 *
 * Refusing at the floor is right by default, but only a *successful* VERIFY
 * resets the card's counter -- so an app that refuses and offers nothing else can
 * never send one again, and the card stays unusable here for good. The override
 * exists so the user, not the app, decides whether to spend the last attempt.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LastAttemptTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MainViewModel
    private lateinit var documentId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val store = DocumentStore(temp.newFolder("documents"))
        documentId = store.import("contract.pdf", MINIMAL_PDF.inputStream())
        viewModel = ViewModelProvider(
            ViewModelStore(),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(store, dispatcher) as T
            },
        )[MainViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * A card with [remaining] attempts left that fails any real VERIFY, so a run
     * that gets past the floor is visible as having reached the card at all.
     */
    private class CountingCard(private val remaining: Int) : ApduTransceiver {
        var verifiesSent = 0

        override fun transceive(command: ByteArray): ByteArray {
            val ins = command[1].toInt() and 0xFF
            return when {
                ins == 0xA4 -> sw(StatusWord.SUCCESS)
                // Case-1 VERIFY: the retry counter, which costs nothing.
                ins == 0x20 && command.size == 4 -> sw(0x63C0 or remaining)
                ins == 0x20 -> {
                    verifiesSent++
                    sw(0x63C0 or (remaining - 1))
                }
                else -> sw(StatusWord.FILE_NOT_FOUND)
            }
        }

        private fun sw(v: Int) =
            byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    private fun runWith(card: CountingCard) {
        viewModel.startSigning("ABC123".toCharArray(), SignParams())
        viewModel.onCard(JpkiSession(card))
    }

    @Test
    fun `one attempt left is refused, and the card is never asked to verify`() {
        val card = CountingCard(remaining = 1)
        viewModel.openSigningSheet(documentId)

        runWith(card)

        assertEquals(0, card.verifiesSent)
        val state = viewModel.signing.value?.state
        assertTrue("$state", state is SigningState.Failed)
        val failure = (state as SigningState.Failed).failure
        assertEquals(SignFailure.TooFewAttempts(remaining = 1), failure)
        assertTrue(
            "a refusal at one attempt must offer the way past it",
            (failure as SignFailure.TooFewAttempts).canOverride,
        )
    }

    @Test
    fun `taking the override lets the same card be verified`() {
        viewModel.openSigningSheet(documentId)
        runWith(CountingCard(remaining = 1))

        viewModel.useLastAttempt()

        // Back on the form, licensed -- and with no PIN, which has to be retyped.
        assertEquals(SigningState.Form, viewModel.signing.value?.state)
        assertTrue(viewModel.signing.value?.acceptLastAttempt == true)

        val card = CountingCard(remaining = 1)
        runWith(card)

        assertEquals("the run must now reach the card", 1, card.verifiesSent)
        val failure = (viewModel.signing.value?.state as SigningState.Failed).failure
        // It reached the VERIFY and the card refused the PIN -- which is the point:
        // the attempt was spent deliberately, not withheld forever.
        assertEquals(SignFailure.Card(CardProblem.PinBlocked), failure)
    }

    /**
     * The override lowers our floor, not the card's. Zero is still zero -- and an
     * exhausted card is *blocked*, which is a different sentence from a small
     * count and the only one the user can act on.
     *
     * It used to be reported as `TooFewAttempts(0)`, so the screen read "only 0
     * attempts are left on this card; signing was stopped so a mistyped PIN
     * cannot use them up" -- said about attempts that were already gone, with no
     * mention of the municipal window that is the only way back, and no button,
     * because there was nothing left to override. The refusal was right; the
     * thing it said was not.
     */
    @Test
    fun `an exhausted card is named as blocked, not as one attempt short`() {
        viewModel.openSigningSheet(documentId)
        runWith(CountingCard(remaining = 0))
        viewModel.useLastAttempt()

        val card = CountingCard(remaining = 0)
        runWith(card)

        assertEquals("still never asked to verify", 0, card.verifiesSent)
        val failure = (viewModel.signing.value?.state as SigningState.Failed).failure
        assertEquals(SignFailure.Card(CardProblem.PinBlocked), failure)
        assertNull(
            "a blocked card has no count worth stating",
            failure.remainingAttempts,
        )
    }

    /** The other status word a card uses to say the same thing. */
    @Test
    fun `a card that answers 6983 to the counter is blocked too`() {
        viewModel.openSigningSheet(documentId)

        val card = object : ApduTransceiver {
            var verifiesSent = 0
            override fun transceive(command: ByteArray): ByteArray {
                val ins = command[1].toInt() and 0xFF
                if (ins == 0x20 && command.size > 4) verifiesSent++
                return when {
                    ins == 0xA4 -> byteArrayOf(0x90.toByte(), 0x00)
                    ins == 0x20 -> byteArrayOf(0x69, 0x83.toByte())
                    else -> byteArrayOf(0x6A, 0x82.toByte())
                }
            }
        }
        viewModel.startSigning("ABC123".toCharArray(), SignParams())
        viewModel.onCard(JpkiSession(card))

        assertEquals(0, card.verifiesSent)
        val failure = (viewModel.signing.value?.state as SigningState.Failed).failure
        // Not a generic "the card did not accept a command", which is what a
        // 6983 on the counter read used to fall through to.
        assertEquals(SignFailure.Card(CardProblem.PinBlocked), failure)
    }

    /**
     * A tap that cannot be turned into a session has still taken the armed run.
     * Staying silent left the sheet waiting for a card that had come and gone.
     */
    @Test
    fun `an unusable card reports, and wipes the pin the run was holding`() {
        val pin = "ABC123".toCharArray()
        viewModel.openSigningSheet(documentId)
        viewModel.startSigning(pin, SignParams())

        viewModel.onCardUnusable(CardProblem.NotJpkiCard)

        assertTrue("the run is over, so its pin goes with it", pin.all { it == ' ' })
        assertEquals(
            SignFailure.Card(CardProblem.NotJpkiCard),
            (viewModel.signing.value?.state as SigningState.Failed).failure,
        )
    }

    /** With nothing armed there is no run to fail, and nothing to say about it. */
    @Test
    fun `an unusable card with no run armed changes nothing`() {
        viewModel.onCardUnusable(CardProblem.LostContact)
        assertNull(viewModel.signing.value)

        viewModel.openSigningSheet(documentId)
        viewModel.onCardUnusable(CardProblem.LostContact)
        assertEquals(SigningState.Form, viewModel.signing.value?.state)
    }
}
