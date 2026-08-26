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
}
