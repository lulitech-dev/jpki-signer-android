package dev.lulitech.jpkisigner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.lulitech.jpkisigner.data.DocumentStore
import dev.lulitech.jpkisigner.data.MINIMAL_PDF
import dev.lulitech.jpkisigner.pdf.PdfRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A rejected import must stay on screen until it is read.
 *
 * Several share-ins in a row is ordinary -- a file manager sends them one after
 * another -- and the outcome of each used to be assigned to [importError]
 * whichever way it went. So one good file arriving behind one bad file cleared
 * the bad one's dialog before anyone had seen it, and the rejection became the
 * silent no-op that this field exists to prevent.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImportErrorTest {

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

    @Test
    fun `a rejected import is reported`() = runTest(dispatcher) {
        viewModel.import("junk.pdf") { "not a PDF at all".toByteArray().inputStream() }.join()

        assertEquals(
            ImportError.Rejected(PdfRejection.UNREADABLE),
            viewModel.importError.value,
        )
    }

    @Test
    fun `a later successful import does not clear an unread rejection`() = runTest(dispatcher) {
        viewModel.import("junk.pdf") { "not a PDF at all".toByteArray().inputStream() }.join()
        val rejection = viewModel.importError.value

        viewModel.import("contract.pdf") { MINIMAL_PDF.inputStream() }.join()

        assertEquals(
            "the good file imported, but it is not the good file's place to " +
                "dismiss the bad file's dialog",
            rejection,
            viewModel.importError.value,
        )
        // Straight to the store: `import` kicks off its list refresh without
        // awaiting it, so the flow has not necessarily caught up yet, and what is
        // being checked here is that the good file actually landed.
        assertEquals("and the good file did arrive", 1, store.list().size)
    }

    @Test
    fun `a second rejection replaces the first`() = runTest(dispatcher) {
        viewModel.import("junk.pdf") { "not a PDF at all".toByteArray().inputStream() }.join()
        viewModel.import("nothing.pdf") { null }.join()

        // Both are errors, so the newer one is what the user is looking at.
        val error = viewModel.importError.value
        assertEquals(ImportError.Failed::class.java, error!!::class.java)
    }

    @Test
    fun `dismissing is what clears it`() = runTest(dispatcher) {
        viewModel.import("junk.pdf") { "not a PDF at all".toByteArray().inputStream() }.join()

        viewModel.clearImportError()

        assertNull(viewModel.importError.value)
    }
}
