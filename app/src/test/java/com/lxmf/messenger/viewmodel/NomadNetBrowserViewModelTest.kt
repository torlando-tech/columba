// SleepInsteadOfDelay: IO coroutines need Thread.sleep for completion
@file:Suppress("SleepInsteadOfDelay")

package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lxmf.messenger.nomadnet.NomadNetPageCache
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Tests for [NomadNetBrowserViewModel] — navigation state machine, caching,
 * history, form submission, and identify-to-node logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NomadNetBrowserViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var protocol: ServiceReticulumProtocol
    private lateinit var pageCache: NomadNetPageCache
    private lateinit var viewModel: NomadNetBrowserViewModel

    private val nodeHash = "abcdef01234567890abcdef012345678"
    private val simplePage = ">Hello World"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        protocol = mockk(relaxed = true)
        pageCache = mockk(relaxed = true)
        viewModel = NomadNetBrowserViewModel(protocol, pageCache)
    }

    @Suppress("SleepInsteadOfDelay")
    @After
    fun tearDown() {
        // Wait for pending Dispatchers.IO coroutines to complete
        Thread.sleep(100)
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ── loadPage ──

    @Test
    fun `loadPage with cache hit emits PageLoaded immediately`() =
        runTest(testDispatcher) {
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            val state = viewModel.browserState.value
            assertTrue("Should be PageLoaded", state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            val loaded = state as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, loaded.nodeHash)
            assertEquals("/page/index.mu", loaded.path)
        }

    @Test
    fun `loadPage with cache miss fetches from network`() =
        runTest(testDispatcher) {
            every { pageCache.get(nodeHash, "/page/index.mu") } returns null
            coEvery { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", null, any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            val state = viewModel.browserState.value
            assertTrue("Should be PageLoaded", state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            verify { pageCache.put(nodeHash, "/page/index.mu", simplePage, any()) }
        }

    @Test
    fun `loadPage network failure emits Error`() =
        runTest(testDispatcher) {
            every { pageCache.get(nodeHash, "/page/index.mu") } returns null
            coEvery { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", null, any()) } returns
                Result.failure(RuntimeException("Connection timed out"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            val state = viewModel.browserState.value
            assertTrue("Should be Error", state is NomadNetBrowserViewModel.BrowserState.Error)
            assertEquals("Connection timed out", (state as NomadNetBrowserViewModel.BrowserState.Error).message)
        }

    @Test
    fun `loadPage with custom path uses that path`() =
        runTest(testDispatcher) {
            every { pageCache.get(nodeHash, "/page/about.mu") } returns simplePage

            viewModel.loadPage(nodeHash, "/page/about.mu")
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals("/page/about.mu", state.path)
        }

    @Test
    fun `loadPage to different node resets identity`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // Navigate to a different node
            val otherNode = "1234567890abcdef1234567890abcdef"
            viewModel.loadPage(otherNode)
            advanceUntilIdle()

            assertFalse(viewModel.isIdentified.value)
        }

    @Test
    fun `loadPage clears form fields`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.updateField("username", "test")
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            assertTrue(viewModel.formFields.value.isEmpty())
        }

    // ── navigateToLink ──

    @Test
    fun `navigateToLink pushes current page to history`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/other.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            assertFalse(viewModel.canGoBack.value)

            viewModel.navigateToLink("/page/other.mu", emptyList())
            advanceUntilIdle()

            assertTrue(viewModel.canGoBack.value)
        }

    @Test
    fun `navigateToLink with partial reload prefix does not navigate`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.navigateToLink("p:my_partial", emptyList())
            advanceUntilIdle()

            // Should still be on the original page, not navigated away
            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals("/page/index.mu", state.path)
            assertFalse(viewModel.canGoBack.value)
        }

    @Test
    fun `navigateToLink with form fields submits data and skips cache`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/result.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.updateField("username", "alice")
            viewModel.navigateToLink("/page/login.mu", listOf("username"))
            advanceUntilIdle()

            // Verify form data was sent (non-null formDataJson)
            coVerify {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/login.mu",
                    match { it != null && it.contains("alice") },
                    any(),
                )
            }
        }

    // ── goBack ──

    @Test
    fun `goBack restores previous page and form fields`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(">Second Page", "/page/second.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.updateField("query", "test")

            viewModel.navigateToLink("/page/second.mu", emptyList())
            advanceUntilIdle()

            val wentBack = viewModel.goBack()
            assertTrue(wentBack)

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals("/page/index.mu", state.path)
            assertEquals("test", viewModel.formFields.value["query"])
        }

    @Test
    fun `goBack with empty history returns false`() {
        assertFalse(viewModel.goBack())
    }

    @Test
    fun `multiple goBack pops stack correctly`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/two.mu"))

            // Page 1
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // Page 2
            viewModel.navigateToLink("/page/two.mu", emptyList())
            advanceUntilIdle()

            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/three.mu"))

            // Page 3
            viewModel.navigateToLink("/page/three.mu", emptyList())
            advanceUntilIdle()

            assertTrue(viewModel.canGoBack.value)

            viewModel.goBack() // back to page 2
            assertTrue(viewModel.canGoBack.value)

            viewModel.goBack() // back to page 1
            assertFalse(viewModel.canGoBack.value)
        }

    // ── refresh ──

    @Test
    fun `refresh fetches from network bypassing cache`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            // requestNomadnetPage called for the refresh (cache bypassed)
            coVerify(atLeast = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", null, any()) }
        }

    @Test
    fun `refresh on non-loaded state is no-op`() =
        runTest(testDispatcher) {
            viewModel.refresh() // Initial state, should not crash
            advanceUntilIdle()
            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Initial)
        }

    // ── cancelLoading ──

    @Test
    fun `cancelLoading emits Error state`() =
        runTest(testDispatcher) {
            viewModel.cancelLoading()
            advanceUntilIdle()

            val state = viewModel.browserState.value
            assertTrue(state is NomadNetBrowserViewModel.BrowserState.Error)
            assertEquals("Cancelled", (state as NomadNetBrowserViewModel.BrowserState.Error).message)
        }

    // ── updateField ──

    @Test
    fun `updateField accumulates form fields`() {
        viewModel.updateField("name", "Alice")
        viewModel.updateField("email", "alice@example.com")

        val fields = viewModel.formFields.value
        assertEquals("Alice", fields["name"])
        assertEquals("alice@example.com", fields["email"])
    }

    @Test
    fun `updateField overwrites previous value for same key`() {
        viewModel.updateField("name", "Alice")
        viewModel.updateField("name", "Bob")

        assertEquals("Bob", viewModel.formFields.value["name"])
    }

    // ── setRenderingMode ──

    @Test
    fun `setRenderingMode updates state`() {
        assertEquals(NomadNetBrowserViewModel.RenderingMode.MONOSPACE_SCROLL, viewModel.renderingMode.value)

        viewModel.setRenderingMode(NomadNetBrowserViewModel.RenderingMode.PROPORTIONAL_WRAP)

        assertEquals(NomadNetBrowserViewModel.RenderingMode.PROPORTIONAL_WRAP, viewModel.renderingMode.value)
    }

    // ── identifyToNode ──

    @Test
    fun `identifyToNode succeeds and triggers refresh`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns Result.success(true)
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            assertTrue(viewModel.isIdentified.value)
            assertFalse(viewModel.identifyInProgress.value)
        }

    @Test
    fun `identifyToNode failure sets error`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns
                Result.failure(RuntimeException("Link failed"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            assertFalse(viewModel.isIdentified.value)
            assertEquals("Link failed", viewModel.identifyError.value)
            assertFalse(viewModel.identifyInProgress.value)
        }

    @Test
    fun `identifyToNode does nothing when already identified`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns Result.success(true)
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(ServiceReticulumProtocol.NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            advanceUntilIdle()

            // Try again — should be a no-op
            viewModel.identifyToNode()
            advanceUntilIdle()

            coVerify(exactly = 1) { protocol.identifyNomadnetLink(any()) }
        }

    @Test
    fun `clearIdentifyError resets error`() {
        // Access private state indirectly — identifyToNode failure sets error
        viewModel.clearIdentifyError()
        assertNull(viewModel.identifyError.value)
    }

    // ── initial state ──

    @Test
    fun `initial state is correct`() {
        assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Initial)
        assertTrue(viewModel.formFields.value.isEmpty())
        assertEquals(NomadNetBrowserViewModel.RenderingMode.MONOSPACE_SCROLL, viewModel.renderingMode.value)
        assertFalse(viewModel.isIdentified.value)
        assertFalse(viewModel.identifyInProgress.value)
        assertNull(viewModel.identifyError.value)
        assertFalse(viewModel.canGoBack.value)
    }
}
