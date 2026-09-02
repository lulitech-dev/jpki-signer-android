package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.MINIMAL_PDF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

/**
 * "This document has no signatures yet" is a claim about the document, and it can
 * only be made about one we actually read.
 *
 * Every read failure used to fall back to an empty list, which renders exactly
 * like a genuinely unsigned file -- so a document that would not parse looked
 * unsigned, and silently offered no way to remove anything from it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailReadabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: DocumentStore
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = DocumentStore(temp.newFolder("documents"))
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
     * The guard [MainViewModel.open] claims a serial number for is not open's
     * alone: a cascade delete reloads the detail too, and published it
     * unconditionally -- so backing out of a document while the truncation was in
     * flight put the screen the user had just left straight back on top of the
     * library.
     */
    @Test
    fun `a cascade delete finishing after the screen was closed does not reopen it`() =
        runTest(dispatcher) {
            val id = store.import("contract.pdf", MINIMAL_PDF.inputStream())
            viewModel.open(id).join()
            val length = viewModel.detail.value!!.sourceLength

            val job = viewModel.deleteSignatureCascade(id, length, length)
            viewModel.closeDetail()
            job.join()

            assertNull(
                "the user left the detail screen; a late reload must not put it back",
                viewModel.detail.value,
            )
        }

    /**
     * The library still has to be re-read, though: the truncation landed on disk
     * whatever became of the screen it was started from.
     */
    @Test
    fun `a cascade delete refreshes the library even when the screen is gone`() =
        runTest(dispatcher) {
            val id = store.import("contract.pdf", MINIMAL_PDF.inputStream())
            viewModel.open(id).join()
            val length = viewModel.detail.value!!.sourceLength

            val job = viewModel.deleteSignatureCascade(id, length - 1, length)
            viewModel.closeDetail()
            job.join()

            assertEquals(listOf(id), viewModel.documents.value.map { it.id })
        }

    @Test
    fun `an unsigned document reads as unsigned, not as unreadable`() = runTest(dispatcher) {
        val id = store.import("contract.pdf", MINIMAL_PDF.inputStream())

        viewModel.open(id).join()

        val detail = viewModel.detail.value!!
        assertTrue("a valid one-page PDF has no signatures", detail.rows.isEmpty())
        assertFalse("and it was read perfectly well", detail.unreadable)
    }

    @Test
    fun `a document that will not parse is marked unreadable rather than unsigned`() =
        runTest(dispatcher) {
            val id = store.import("broken.pdf", "not a PDF at all".toByteArray().inputStream())

            viewModel.open(id).join()

            val detail = viewModel.detail.value!!
            assertTrue(detail.rows.isEmpty())
            assertTrue(
                "an empty list from a failed read must not read as 'no signatures'",
                detail.unreadable,
            )
            assertEquals("broken.pdf", detail.displayName)
        }

    /**
     * Opening a document takes as long as parsing it, and the screen used to say
     * nothing at all for that time: a tap that was working looked exactly like a
     * tap that had missed.
     */
    @Test
    fun `a read in progress is visible, and is finished with`() = runTest(dispatcher) {
        val id = store.import("contract.pdf", MINIMAL_PDF.inputStream())

        val job = viewModel.open(id)
        assertTrue("the spinner is on from the moment of the tap", viewModel.detailLoading.value)

        job.join()

        assertFalse(viewModel.detailLoading.value)
        assertEquals(id, viewModel.detail.value?.id)
    }

    /**
     * A read the user walked away from must not reopen the screen behind them.
     *
     * Two reads race on the IO dispatcher and resume in whatever order they
     * finish, so a slow one landing late could put back a document that had been
     * closed -- or, between two taps, put up the wrong one.
     */
    @Test
    fun `a read the user left does not reopen the screen`() = runTest(dispatcher) {
        val id = store.import("contract.pdf", MINIMAL_PDF.inputStream())

        val job = viewModel.open(id)
        viewModel.closeDetail()
        job.join()

        assertNull(viewModel.detail.value)
        assertFalse("nor leave the spinner running", viewModel.detailLoading.value)
    }

    /** Between two taps, the newer one owns the screen however the reads finish. */
    @Test
    fun `the newest request is the one that lands`() = runTest(dispatcher) {
        val first = store.import("first.pdf", MINIMAL_PDF.inputStream())
        val second = store.import("second.pdf", MINIMAL_PDF.inputStream())

        val stale = viewModel.open(first)
        val fresh = viewModel.open(second)
        stale.join()
        fresh.join()

        assertEquals(second, viewModel.detail.value?.id)
    }

    /**
     * The same distinction one screen up.
     *
     * The library row shows a count and nothing else, and `SignatureInspector.count`
     * used to answer 0 for a file it could not parse -- so a damaged document sat
     * in the list rendered exactly like a plainly unsigned one, with no hint that
     * opening it would say something different.
     */
    @Test
    fun `a document that will not parse has no signature count, not a count of zero`() =
        runTest(dispatcher) {
            store.import("contract.pdf", MINIMAL_PDF.inputStream())
            store.import("broken.pdf", "not a PDF at all".toByteArray().inputStream())

            viewModel.refresh().join()

            val byName = viewModel.documents.value.associateBy { it.displayName }
            assertEquals(2, byName.size)
            assertEquals(
                "a readable, unsigned document really has none",
                0,
                byName.getValue("contract.pdf").signatureCount,
            )
            assertNull(
                "a file we could not read has no count to report",
                byName.getValue("broken.pdf").signatureCount,
            )
        }
}
