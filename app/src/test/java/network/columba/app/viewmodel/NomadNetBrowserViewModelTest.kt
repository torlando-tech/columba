// SleepInsteadOfDelay: IO coroutines need Thread.sleep for completion
@file:Suppress("SleepInsteadOfDelay")

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.R
import network.columba.app.micron.MicronParser
import network.columba.app.nomadnet.NomadNetPageCache
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NomadnetPageResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [NomadNetBrowserViewModel] — navigation state machine, caching,
 * history, form submission, identify-to-node logic, URL helpers, and lxmf@ routing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NomadNetBrowserViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var protocol: RnsNomadnet
    private lateinit var pageCache: NomadNetPageCache
    private lateinit var imageCache: network.columba.app.nomadnet.NomadNetImageCache
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var viewModel: NomadNetBrowserViewModel

    private val nodeHash = "abcdef01234567890abcdef012345678"
    private val simplePage = ">Hello World"

    /** Fake of the singleton session store, shared across VM instances. */
    private val sessionSnapshot = AtomicReference<NomadNetPageCache.SessionSnapshot?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        protocol = mockk()
        pageCache = mockk()
        imageCache = mockk()
        settingsRepository = mockk()
        rnsCore = mockk()
        // RNS is already READY in these tests; the re-READY sync collector sees
        // no transition and does nothing, so a static flow is enough.
        every { rnsCore.networkStatus } returns MutableStateFlow(NetworkStatus.READY)
        every { pageCache.put(any(), any(), any(), any()) } just Runs
        sessionSnapshot.set(null)
        val nodeSlot = slot<String>()
        val pathSlot = slot<String>()
        val docSlot = slot<network.columba.app.micron.MicronDocument>()
        val tokensSlot = slot<List<String>>()
        val fieldsSlot = slot<Map<String, String>>()
        every {
            pageCache.putSession(
                capture(nodeSlot),
                capture(pathSlot),
                capture(docSlot),
                capture(tokensSlot),
                capture(fieldsSlot),
            )
        } answers {
            sessionSnapshot.set(
                NomadNetPageCache.SessionSnapshot(
                    NomadNetPageCache.SessionPage(
                        nodeSlot.captured,
                        pathSlot.captured,
                        docSlot.captured,
                        tokensSlot.captured,
                        fieldsSlot.captured,
                    ),
                ),
            )
        }
        every { pageCache.putSessionSnapshot(any()) } answers { sessionSnapshot.set(firstArg()) }
        every { pageCache.getSessionSnapshot(any(), any()) } answers {
            val node = firstArg<String>()
            val path = secondArg<String>()
            sessionSnapshot.get()?.takeIf { it.current.nodeHash == node && it.current.path == path }
        }
        every { pageCache.clearSession() } answers { sessionSnapshot.set(null) }
        // Page-image cache: only the explicit clear (clearImageCache) and the
        // loader's miss-path get() are reachable from these tests; stub both
        // explicitly rather than using a relaxed mock.
        every { imageCache.clear() } just Runs
        every { imageCache.get(any()) } returns null
        coEvery { protocol.cancelNomadnetPageRequest() } just Runs
        coEvery { protocol.getNomadnetRequestStatus() } returns ""
        coEvery { protocol.getNomadnetLinkStats(any()) } returns null
        // Auto-identify: the ViewModel syncs the flagged set to the backend;
        // the backend identifies at link establishment (not the ViewModel).
        coEvery { protocol.setIdentifyOnConnectNodes(any()) } just Runs
        // Flagged nodes bypass the page cache and fetch fresh, so the fetch
        // path must be stubbed for any test that loads a flagged node's page.
        coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
            Result.success(NomadnetPageResult(simplePage, "/page/index.mu"))
        coEvery { protocol.getNomadnetDownloadProgress() } returns 0f
        // No persisted rendering mode by default; individual tests can override.
        every { settingsRepository.nomadNetRenderingModeFlow } returns flowOf(null)
        every { settingsRepository.nomadNetImageLoadingModeFlow } returns flowOf(null)
        every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns flowOf(emptySet())
        coEvery { settingsRepository.saveNomadNetRenderingMode(any()) } just Runs
        coEvery { settingsRepository.saveNomadNetImageLoadingMode(any()) } just Runs
        coEvery { settingsRepository.saveNomadNetLastNodeHash(any(), any(), any()) } just Runs
        coEvery { settingsRepository.clearNomadNetLastNodeHash() } just Runs
        viewModel = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
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
            // The bottom-nav NomadNet tab reopens the exact page the user left
            // on, so every successful page load must persist its node hash and
            // the deep path.
            coVerify {
                settingsRepository.saveNomadNetLastNodeHash(nodeHash, "/page/index.mu", any())
            }
        }

    @Test
    fun `a deep in-browser navigation persists the deep path for tab re-entry`() =
        runTest(testDispatcher) {
            // Index loads from cache, then the user follows an in-page link to a
            // deep forum thread (also cached). Tapping the NomadNet tab later
            // must be able to restore that exact deep path, so it is persisted
            // on navigation.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage
            every { pageCache.get(nodeHash, "/page/forum/thread.mu") } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.navigateToLink("/page/forum/thread.mu", emptyList())
            advanceUntilIdle()

            // The browser must have actually landed on the deep page (behavior,
            // not just wiring), so the persisted path is the real current page.
            val state = viewModel.browserState.value
            assertTrue(
                "Should be PageLoaded, was $state",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            assertEquals("/page/forum/thread.mu", (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).path)

            // ...and that exact deep path was persisted for tab re-entry.
            coVerify {
                settingsRepository.saveNomadNetLastNodeHash(nodeHash, "/page/forum/thread.mu", any())
            }
        }

    @Test
    fun `a var-bearing page persists the full path with its backtick field block`() =
        runTest(testDispatcher) {
            // A forum thread is opened with request variables (the backtick
            // block). Restoring it later must re-submit those variables, so the
            // persisted path must carry the full block, not just the bare path.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage
            coEvery {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/forum/thread.mu",
                    match { it != null },
                    any(),
                )
            } returns Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            // In-page link to the thread with var fields (the reported repro).
            viewModel.navigateToLink(
                "/page/forum/thread.mu",
                listOf("cat=general", "thread=a-gentle-look-at-prns"),
            )
            advanceUntilIdle()
            Thread.sleep(100) // Wait for the Dispatchers.IO fetch

            val state = viewModel.browserState.value
            assertTrue(
                "Should be PageLoaded, was $state",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )

            // The persisted path must be the FULL path with the backtick block,
            // so restoring it re-submits the same request variables.
            coVerify {
                settingsRepository.saveNomadNetLastNodeHash(
                    nodeHash,
                    "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns",
                    any(),
                )
            }
        }

    @Test
    fun `restoring a var-bearing path serves the cache instead of re-submitting`() =
        runTest(testDispatcher) {
            // Regression: the NomadNet tab reopens the persisted full path
            // (backtick block included) on every tab tap. loadPage must serve
            // the response cached under that full path; submitting on every
            // restore would repeat the request - a site whose submission
            // changes data would redo its action without another click.
            val varPath = "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns"
            every { pageCache.get(nodeHash, varPath) } returns simplePage

            viewModel.loadPage(nodeHash, varPath)
            advanceUntilIdle()

            val state = viewModel.browserState.value
            assertTrue("Should be PageLoaded, was $state", state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals("/page/forum/thread.mu", (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).path)
            coVerify(exactly = 0) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
            // Re-persisting must keep the backtick block so the next restore
            // hits this same cache key instead of a bare path the node rejects.
            coVerify { settingsRepository.saveNomadNetLastNodeHash(nodeHash, varPath, any()) }
        }

    @Test
    fun `a cold restore of a var-bearing path submits the variables on cache miss`() =
        runTest(testDispatcher) {
            // Cold start (empty cache) must still re-submit the variables: a
            // dynamic node rejects the bare path, so the submission IS the
            // restore there. And the response must be cached under the full
            // path so the next restore takes the cache-first route.
            val varPath = "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns"
            every { pageCache.get(nodeHash, varPath) } returns null
            coEvery {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/forum/thread.mu",
                    match { it != null },
                    any(),
                )
            } returns Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))

            viewModel.loadPage(nodeHash, varPath)
            advanceUntilIdle()
            Thread.sleep(100) // Wait for the Dispatchers.IO fetch

            assertTrue(
                "cold restore must still load the page, was ${viewModel.browserState.value}",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            coVerify(exactly = 1) {
                protocol.requestNomadnetPage(nodeHash, "/page/forum/thread.mu", match { it != null }, any())
            }
            coVerify(exactly = 1) { pageCache.put(nodeHash, varPath, simplePage, any()) }
        }

    @Test
    fun `a tab session restore shows the last page without re-fetching even when the site opts out of caching`() =
        runTest(testDispatcher) {
            // A `#!c=0` page (the majority of executable NomadNet pages) never
            // enters the file cache - get always misses, as it does for a real
            // opted-out page. The in-memory session store must serve the tab
            // restore so returning to the NomadNet tab never re-fetches the
            // page the user is already looking at.
            every { pageCache.get(any(), any()) } returns null

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "initial load should be PageLoaded, was ${viewModel.browserState.value}",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )

            viewModel.loadPage(nodeHash, fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(
                "restore must show the page, was ${viewModel.browserState.value}",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            coVerify(exactly = 1) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `ordinary navigation to an opted-out page still re-fetches`() =
        runTest(testDispatcher) {
            // Only the session restore may serve the in-memory page. A plain
            // loadPage (deep link, URL bar, address entry) must still honour
            // the site's `#!c=0` directive and fetch again.
            every { pageCache.get(any(), any()) } returns null

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "the re-fetch must land on the page, was ${viewModel.browserState.value}",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            coVerify(exactly = 2) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `a flagged node's session restore serves the page and refreshes identified without re-submitting`() =
        runTest(testDispatcher) {
            // Regression (Greptile P1 x2): a flagged restore must neither
            // strand the user on anonymous content behind an identified
            // badge nor re-submit a var-bearing request. A plain page is
            // served from the store instantly, then the existing identify
            // refresh re-fetches it identified - a safe GET, exactly one
            // request. Var-bearing pages are covered by the sibling test.
            val nodesFlow = MutableStateFlow<Set<String>>(setOf(nodeHash))
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            // The page the user was looking at when they left the tab,
            // captured while the node was still unflagged (so anonymous).
            sessionSnapshot.set(
                NomadNetPageCache.SessionSnapshot(
                    NomadNetPageCache.SessionPage(nodeHash, "/page/index.mu", MicronParser.parse(simplePage)),
                ),
            )

            // Returning to the tab builds a fresh entry-scoped ViewModel
            // with the flag already set - no transition for its collector to
            // react to, so the restore path itself must handle the flag.
            val restoredVm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            restoredVm.loadPage(nodeHash, fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(
                "restore must show the stored page, was ${restoredVm.browserState.value}",
                restoredVm.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            // Exactly one request: the background identify refresh (plain
            // GET). Not a restore-blocking fetch, and never a re-submit.
            coVerify(exactly = 1) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `a flagged node's var-page restore serves the store without re-submitting`() =
        runTest(testDispatcher) {
            // The submission behind a var-bearing page may have changed data
            // on the node; restoring the tab must never redo it without a
            // click. Even with "Always identify" set, the stored response is
            // served and the identify refresh skips the page rather than
            // repeating the submission.
            val varPath = "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns"
            val nodesFlow = MutableStateFlow<Set<String>>(setOf(nodeHash))
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            sessionSnapshot.set(
                NomadNetPageCache.SessionSnapshot(
                    NomadNetPageCache.SessionPage(
                        nodeHash,
                        varPath,
                        MicronParser.parse(simplePage),
                        fieldTokens = listOf("cat=general", "thread=a-gentle-look-at-prns"),
                    ),
                ),
            )

            val restoredVm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            restoredVm.loadPage(nodeHash, path = varPath, fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(
                "restore must serve the stored response, was ${restoredVm.browserState.value}",
                restoredVm.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            coVerify(exactly = 0) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `leaving the tab preserves the page and form drafts for the restore`() =
        runTest(testDispatcher) {
            // Regression (Greptile P1): the entry-scoped ViewModel dies when
            // the tab is left. Its page and half-typed form must survive in
            // the singleton store and come back with the restore - without
            // re-issuing any request.
            every { pageCache.get(any(), any()) } returns null
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.updateField("name", "draft-value")
            // The tab is left: the entry's ViewModel is cleared.
            viewModel.saveSessionSnapshot()

            val restoredVm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            restoredVm.loadPage(nodeHash, fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(
                "restore must show the page, was ${restoredVm.browserState.value}",
                restoredVm.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            assertTrue(
                "the form draft must survive the tab switch, got ${restoredVm.formFields.value}",
                restoredVm.formFields.value["name"] == "draft-value",
            )
            // Only the initial load requested anything; the restore was free.
            coVerify(exactly = 1) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `leaving the tab preserves the in-page back history`() =
        runTest(testDispatcher) {
            // Back must keep returning to earlier pages after a tab switch:
            // the history stack rides along in the session snapshot and is
            // replayed from memory, so going back issues no request.
            every { pageCache.get(any(), any()) } returns null
            // The session store keys on the displayed page's own path, so
            // this test's stub must echo the requested path back rather
            // than return the shared fixed one.
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg()))
            }
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.navigateToLink("/page/forum/thread.mu", emptyList())
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(viewModel.canGoBack.value)
            viewModel.saveSessionSnapshot()

            val restoredVm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            restoredVm.loadPage(nodeHash, path = "/page/forum/thread.mu", fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "the restored session must still offer Back",
                restoredVm.canGoBack.value,
            )
            assertTrue(restoredVm.goBack())
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "Back must serve the earlier page from memory, was ${restoredVm.browserState.value}",
                restoredVm.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            // Initial load + the link navigation; restore and Back are free.
            coVerify(exactly = 2) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `restored history keeps var pages as bare paths with separate tokens`() =
        runTest(testDispatcher) {
            // Regression (Greptile P1): the snapshot must store history
            // entries the way HistoryEntry holds them - bare path, request
            // variables in the tokens. A backtick-bearing path here makes
            // emitPageLoaded append the field block a second time when
            // saving the address, and makes refresh send the full persisted
            // path to the node.
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg()))
            }
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.navigateToLink("/page/forum/thread.mu", listOf("cat=general"))
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.navigateToLink("/page/other.mu", emptyList())
            advanceUntilIdle()
            Thread.sleep(100)
            viewModel.saveSessionSnapshot()

            val restoredVm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            restoredVm.loadPage(nodeHash, path = "/page/other.mu", fromSessionRestore = true)
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(restoredVm.goBack())
            advanceUntilIdle()
            Thread.sleep(100)

            val state = restoredVm.browserState.value
            assertTrue(
                "Back must land on the var page, was $state",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            state as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertTrue(
                "history path must stay bare; the field block belongs to the tokens, got ${state.path}",
                state.path == "/page/forum/thread.mu",
            )
            assertTrue(
                "the request variables must survive as tokens, got ${state.fieldTokens}",
                state.fieldTokens == listOf("cat=general"),
            )
            // Initial load + var submit + link navigation; restore and Back are free.
            coVerify(exactly = 3) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
        }

    @Test
    fun `refresh on a var-bearing page re-submits the request data, not a bare fetch`() =
        runTest(testDispatcher) {
            // Pull-to-refresh (and any re-fetch) of a page that was loaded with
            // request variables must re-submit those variables. A bare fetch
            // (null data) drops them and the node rejects the page with
            // "Invalid thread" - the same failure this whole change targets.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage
            coEvery {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/forum/thread.mu",
                    match { it != null },
                    any(),
                )
            } returns Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))
            coEvery {
                protocol.requestNomadnetPage(nodeHash, "/page/forum/thread.mu", null, any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.navigateToLink(
                "/page/forum/thread.mu",
                listOf("cat=general", "thread=a-gentle-look-at-prns"),
            )
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "Thread page should be loaded",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )

            viewModel.refresh()
            advanceUntilIdle()
            Thread.sleep(100)

            // The refresh must NOT issue a bare (null-data) fetch for the
            // var-bearing path - the initial link tap already covered that.
            coVerify(exactly = 0) {
                protocol.requestNomadnetPage(nodeHash, "/page/forum/thread.mu", null, any())
            }
        }

    @Test
    fun `refresh after goBack re-submits the displayed page's own vars, not a later page's`() =
        runTest(testDispatcher) {
            // Regression for the (node, path) match in refresh(): back-navigating
            // to an earlier page that shares the node+path but uses different
            // request vars must refresh with THAT page's vars (the displayed
            // state), not a later page's vars left in lastFetch* state. Refresh
            // rebuilds data from the displayed page's fieldTokens, so it can't
            // resurrect a later page's data over the restored one.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage
            coEvery {
                protocol.requestNomadnetPage(nodeHash, "/page/forum/thread.mu", match { it != null }, any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            // Forward page 1: thread=a
            viewModel.navigateToLink("/page/forum/thread.mu", listOf("thread=a"))
            advanceUntilIdle()
            Thread.sleep(100)
            // Forward page 2 (same node+path, different var): thread=b
            viewModel.navigateToLink("/page/forum/thread.mu", listOf("thread=b"))
            advanceUntilIdle()
            Thread.sleep(100)
            // Go BACK to thread=a (same node+path, different var). lastFetch*
            // still points at thread=b (the later page) after this.
            viewModel.goBack()
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertTrue(
                "Displayed page after goBack must be the earlier (thread=a) page",
                state.fieldTokens == listOf("thread=a"),
            )

            viewModel.refresh()
            advanceUntilIdle()
            Thread.sleep(100)

            // Refresh must re-submit thread=a (the displayed page's vars), not
            // thread=b (the later page's vars still in lastFetch*). The inline
            // var token "thread=a" is sent as var_thread=a in the request data.
            coVerify {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/forum/thread.mu",
                    match { it?.contains("\"var_thread\":\"a\"") == true },
                    any(),
                )
            }
        }

    @Test
    fun `retry on a failed var-bearing page preserves the field tokens for persist`() =
        runTest(testDispatcher) {
            // A var-bearing page that fails on first request, then succeeds on
            // retry, must persist its FULL path (with the backtick block) so a
            // later restore re-submits the same request variables - not a bare
            // path the node would reject.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage
            var threadCalls = 0
            coEvery {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/forum/thread.mu",
                    match { it != null },
                    any(),
                )
            } coAnswers {
                threadCalls++
                if (threadCalls == 1) {
                    Result.failure(RuntimeException("NomadNet timeout"))
                } else {
                    Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))
                }
            }

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.navigateToLink(
                "/page/forum/thread.mu",
                listOf("cat=general", "thread=a-gentle-look-at-prns"),
            )
            advanceUntilIdle()
            Thread.sleep(100)
            assertTrue(
                "First request should fail",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Error,
            )

            viewModel.retry()
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(
                "Retry should recover the page",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            coVerify {
                settingsRepository.saveNomadNetLastNodeHash(
                    nodeHash,
                    "/page/forum/thread.mu`cat=general|thread=a-gentle-look-at-prns",
                    any(),
                )
            }
        }

    @Test
    fun `loadPage with cache miss fetches from network`() =
        runTest(testDispatcher) {
            every { pageCache.get(nodeHash, "/page/index.mu") } returns null
            coEvery { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", null, any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/index.mu"))

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
            assertEquals(UiMessage.Raw("Connection timed out"), (state as NomadNetBrowserViewModel.BrowserState.Error).message)
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

    @Test
    fun `loadPage strips backtick field block from path and submits it as var data`() =
        runTest(testDispatcher) {
            // A NomadNet deep link / URL-bar entry can carry request variables
            // after a backtick, e.g. "…/thread.mu`cat=help|thread=how-to-rngit".
            // Those fields must be split off the path and sent as request data
            // (var_-prefixed), not left glued to the requested path.
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/forum/thread.mu"))

            viewModel.loadPage(nodeHash, "/page/forum/thread.mu`cat=help|thread=how-to-rngit")
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            val pathSlot = slot<String>()
            val dataSlot = slot<String>()
            coVerify {
                protocol.requestNomadnetPage(nodeHash, capture(pathSlot), capture(dataSlot), any())
            }
            // Path is the clean page path — the backtick block is gone.
            assertEquals("/page/forum/thread.mu", pathSlot.captured)
            // Fields are submitted as var_-prefixed request data.
            val submitted = org.json.JSONObject(dataSlot.captured)
            assertEquals("help", submitted.getString("var_cat"))
            assertEquals("how-to-rngit", submitted.getString("var_thread"))
        }

    // ── navigateToLink ──

    @Test
    fun `navigateToLink pushes current page to history`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/other.mu"))

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
                Result.success(NomadnetPageResult(simplePage, "/page/result.mu"))

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
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine
            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded)
        }

    // Regression for #917: a page-author-supplied default (e.g. a "display name"
    // pre-filled with the user's nickname) must be in _formFields after parse, so
    // submission sends the default instead of an empty string when the user does
    // not retype the field.
    @Test
    fun `loadPage seeds parser-declared field defaults into form state`() =
        runTest(testDispatcher) {
            val pageWithField = "`<|display_name`Alice>"
            every { pageCache.get(any(), any()) } returns pageWithField

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            assertEquals("Alice", viewModel.formFields.value["display_name"])
        }

    @Test
    fun `loadPage replays saved request data instead of rebuilding empty form values`() =
        runTest(testDispatcher) {
            // A saved search page: the path only names the field, so without
            // replaying the stored request the node would be asked with "".
            every { pageCache.get(any(), any()) } returns null
            val formDataSlot = slot<String>()
            coEvery {
                protocol.requestNomadnetPage(any(), any(), capture(formDataSlot), any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/search.mu"))

            viewModel.loadPage(
                destinationHash = nodeHash,
                path = "/page/search.mu`query",
                requestDataJson = "{\"query\":\"rngit\"}",
            )
            advanceUntilIdle()
            Thread.sleep(200) // the page request runs on Dispatchers.IO
            advanceUntilIdle()

            assertEquals("{\"query\":\"rngit\"}", formDataSlot.captured)
        }

    @Test
    fun `loadPage replays a saved request instead of serving the path's cached page`() =
        runTest(testDispatcher) {
            // The page cache is keyed by path, which only names the submitted
            // fields: it can hold the response to a *different* submission of
            // the same search.
            every { pageCache.get(any(), any()) } returns ">Stale results of another search"
            val formDataSlot = slot<String>()
            coEvery {
                protocol.requestNomadnetPage(any(), any(), capture(formDataSlot), any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/search.mu"))

            viewModel.loadPage(
                destinationHash = nodeHash,
                path = "/page/search.mu`query",
                requestDataJson = "{\"query\":\"rngit\"}",
            )
            advanceUntilIdle()
            Thread.sleep(200) // the page request runs on Dispatchers.IO
            advanceUntilIdle()

            verify(exactly = 0) { pageCache.get(any(), any()) }
            assertEquals("{\"query\":\"rngit\"}", formDataSlot.captured)
        }

    @Test
    fun `navigateToLink submits parser-declared default for unmodified field`() =
        runTest(testDispatcher) {
            // Forum-style page: display_name pre-filled, message empty.
            // User types into message only — display_name must still go through.
            val pageWithFields = "`<|display_name`Alice>\n`<|message`>"
            every { pageCache.get(any(), any()) } returns pageWithFields
            val formDataSlot = slot<String>()
            coEvery {
                protocol.requestNomadnetPage(any(), any(), capture(formDataSlot), any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/result.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.updateField("message", "hello world")
            viewModel.navigateToLink("/page/post.mu", listOf("display_name", "message"))
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            val submitted = org.json.JSONObject(formDataSlot.captured)
            assertEquals("Alice", submitted.getString("display_name"))
            assertEquals("hello world", submitted.getString("message"))
        }

    @Test
    fun `navigateToLink user-typed value wins over field default`() =
        runTest(testDispatcher) {
            val pageWithField = "`<|display_name`Alice>"
            every { pageCache.get(any(), any()) } returns pageWithField
            val formDataSlot = slot<String>()
            coEvery {
                protocol.requestNomadnetPage(any(), any(), capture(formDataSlot), any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/result.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.updateField("display_name", "Bob")
            viewModel.navigateToLink("/page/post.mu", listOf("display_name"))
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            assertEquals(
                "Bob",
                org.json.JSONObject(formDataSlot.captured).getString("display_name"),
            )
        }

    @Test
    fun `navigateToLink user-cleared field submits empty string not default`() =
        runTest(testDispatcher) {
            val pageWithField = "`<|display_name`Alice>"
            every { pageCache.get(any(), any()) } returns pageWithField
            val formDataSlot = slot<String>()
            coEvery {
                protocol.requestNomadnetPage(any(), any(), capture(formDataSlot), any())
            } returns Result.success(NomadnetPageResult(simplePage, "/page/result.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // User explicitly clears the pre-filled default to empty.
            viewModel.updateField("display_name", "")
            viewModel.navigateToLink("/page/post.mu", listOf("display_name"))
            advanceUntilIdle()
            Thread.sleep(100) // Wait for Dispatchers.IO coroutine

            assertEquals(
                "",
                org.json.JSONObject(formDataSlot.captured).getString("display_name"),
            )
        }

    @Test
    fun `navigateToLink with lxmf@ emits OpenConversation event`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            val eventDeferred = async { viewModel.navigationEvent.first() }
            viewModel.navigateToLink("lxmf@deadbeef01234567", emptyList())
            advanceUntilIdle()

            val event = eventDeferred.await()
            assertTrue(event is NomadNetBrowserViewModel.NavigationEvent.OpenConversation)
            assertEquals(
                "deadbeef01234567",
                (event as NomadNetBrowserViewModel.NavigationEvent.OpenConversation).destinationHash,
            )
        }

    @Test
    fun `navigateToLink with lxmf@ does not push history`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // Collect event to avoid suspension
            val eventDeferred = async { viewModel.navigationEvent.first() }
            viewModel.navigateToLink("lxmf@somehash", emptyList())
            advanceUntilIdle()
            eventDeferred.await()

            // Should NOT have pushed to history
            assertFalse(viewModel.canGoBack.value)
        }

    // ── goBack ──

    @Test
    fun `goBack restores previous page and form fields`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(">Second Page", "/page/second.mu"))

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
    fun `goBack resets identification when returning to a different node`() =
        runTest(testDispatcher) {
            // Regression: _isIdentified is one global flag. After identifying to
            // node B and navigating back to an identified node A, the stale flag
            // must be reset on the destination change or A stays "identified".
            val nodeB = "1234567890abcdef1234567890abcdef"
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(any()) } returns Result.success(true)

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.identifyToNode()
            waitUntilIdentified()
            assertTrue(viewModel.isIdentified.value)

            // Navigate to node B and identify to it, then go back to A.
            viewModel.navigateToLink("$nodeB:/page/index.mu", emptyList())
            advanceUntilIdle()
            viewModel.identifyToNode()
            waitUntilIdentified()

            val wentBack = viewModel.goBack()
            assertTrue(wentBack)
            assertFalse(viewModel.isIdentified.value)
        }

    /** Poll the real Dispatchers.IO identify coroutine with a bound. */
    private fun waitUntilIdentified(timeoutMs: Int = 2000) {
        var waitedMs = 0
        while (!viewModel.isIdentified.value && waitedMs < timeoutMs) {
            Thread.sleep(25)
            waitedMs += 25
        }
    }

    /**
     * Wait until the in-flight identify coroutine on the real Dispatchers.IO has
     * fully settled: its finally block clears the in-progress flag after the
     * result (success, failure, or stale-discard) has been handled. Bounded, so a
     * regression that leaves the flag set fails rather than hangs. Preferred over
     * a fixed sleep, which runTest cannot synchronize with the IO dispatcher.
     */
    private fun waitUntilIdentifySettled(timeoutMs: Int = 2000) {
        var waitedMs = 0
        while (viewModel.identifyInProgress.value && waitedMs < timeoutMs) {
            Thread.sleep(25)
            waitedMs += 25
        }
    }

    /**
     * Bounded poll for a condition the test dispatcher cannot synchronize with
     * the real Dispatchers.IO fetch (e.g. browserState reaching PageLoaded after
     * a refresh/fetch). Preferred over a fixed sleep, which still flakes under CI
     * load; the bound makes a regression fail rather than hang.
     */
    private fun waitFor(
        timeoutMs: Int = 2000,
        condition: () -> Boolean,
    ) {
        var waitedMs = 0
        while (!condition() && waitedMs < timeoutMs) {
            Thread.sleep(25)
            waitedMs += 25
        }
    }

    /** Bounded wait for [vm]'s browserState to be a PageLoaded. */
    private fun waitForPageLoaded(
        vm: NomadNetBrowserViewModel,
        timeoutMs: Int = 2000,
    ) {
        waitFor(timeoutMs) { vm.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded }
    }

    /**
     * Bounded poll until [verify] (typically a coVerify on a request that fired
     * on the real Dispatchers.IO) holds. advanceUntilIdle does not advance the IO
     * dispatcher, so a request issued by a fetch/fetchPage may not have fired yet
     * when an assertion runs; polling until the recorded call appears makes the
     * assertion deterministic instead of racing.
     */
    private fun waitForVerify(
        timeoutMs: Int = 2000,
        verify: () -> Unit,
    ) {
        var waitedMs = 0
        var ok = false
        while (!ok && waitedMs <= timeoutMs) {
            try {
                verify()
                ok = true
            } catch (_: AssertionError) {
                Thread.sleep(25)
                waitedMs += 25
            }
        }
        if (!ok) verify() // rethrow for a clean failure message
    }

    @Test
    fun `multiple goBack pops stack correctly`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/two.mu"))

            // Page 1
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // Page 2
            viewModel.navigateToLink("/page/two.mu", emptyList())
            advanceUntilIdle()

            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/three.mu"))

            // Page 3
            viewModel.navigateToLink("/page/three.mu", emptyList())
            advanceUntilIdle()

            assertTrue(viewModel.canGoBack.value)

            viewModel.goBack() // back to page 2
            assertTrue(viewModel.canGoBack.value)

            viewModel.goBack() // back to page 1
            assertFalse(viewModel.canGoBack.value)
        }

    // ── closeSite ──

    @Test
    fun `closeSite resets state clears history and forgets last node`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/second.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            viewModel.updateField("query", "test")
            viewModel.navigateToLink("/page/second.mu", emptyList())
            advanceUntilIdle()
            assertTrue(viewModel.canGoBack.value)

            viewModel.closeSite()
            advanceUntilIdle()

            // View resets to Initial so the tab home can swap to the address prompt.
            assertTrue(
                "Should be Initial, was ${viewModel.browserState.value}",
                viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Initial,
            )
            assertFalse(viewModel.canGoBack.value)
            assertTrue(viewModel.formFields.value.isEmpty())
            // The persisted last-node binding must be forgotten.
            coVerify { settingsRepository.clearNomadNetLastNodeHash() }
        }

    // ── refresh ──

    @Test
    fun `refresh fetches from network bypassing cache`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()
            // The refresh fetch runs on the real Dispatchers.IO; advanceUntilIdle
            // only drains the test dispatcher, so the state can still be Loading
            // when the assertions run. Poll for PageLoaded (bounded) instead of a
            // fixed sleep, which flakes under CI load.
            waitForPageLoaded(viewModel)

            // requestNomadnetPage called for the refresh (cache bypassed)
            coVerify(atLeast = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", null, any()) }
            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.PageLoaded)
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
            assertEquals(UiMessage.Text(R.string.nomadnet_status_cancelled), (state as NomadNetBrowserViewModel.BrowserState.Error).message)
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

    @Test
    fun `setRenderingMode persists the selection`() =
        runTest(testDispatcher) {
            val savedMode = slot<String>()
            coEvery { settingsRepository.saveNomadNetRenderingMode(capture(savedMode)) } just Runs

            viewModel.setRenderingMode(NomadNetBrowserViewModel.RenderingMode.MONOSPACE_ZOOM)
            advanceUntilIdle()

            // Asserts the production enum→name conversion that gets persisted, not just the call.
            assertEquals("MONOSPACE_ZOOM", savedMode.captured)
        }

    @Test
    fun `init restores the persisted rendering mode`() =
        runTest(testDispatcher) {
            every { settingsRepository.nomadNetRenderingModeFlow } returns flowOf("PROPORTIONAL_WRAP")

            val restoredViewModel = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            assertEquals(
                NomadNetBrowserViewModel.RenderingMode.PROPORTIONAL_WRAP,
                restoredViewModel.renderingMode.value,
            )
        }

    @Test
    fun `init falls back to default when no rendering mode persisted`() =
        runTest(testDispatcher) {
            // setUp already stubs nomadNetRenderingModeFlow to flowOf(null)
            advanceUntilIdle()

            assertEquals(
                NomadNetBrowserViewModel.RenderingMode.MONOSPACE_SCROLL,
                viewModel.renderingMode.value,
            )
        }

    @Test
    fun `init restore does not clobber a selection made before the read completes`() =
        runTest(testDispatcher) {
            // A flow whose emission we control, so the init read stays suspended until we choose.
            val controllableFlow = MutableSharedFlow<String?>()
            every { settingsRepository.nomadNetRenderingModeFlow } returns controllableFlow

            // init launches and suspends on first() because nothing has been emitted yet.
            val racingViewModel = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)

            // User picks a mode before the persisted value has been read back.
            racingViewModel.setRenderingMode(NomadNetBrowserViewModel.RenderingMode.MONOSPACE_ZOOM)

            // The persisted value finally arrives, resuming the init coroutine.
            controllableFlow.emit("PROPORTIONAL_WRAP")
            advanceUntilIdle()

            // The guard must keep the user's selection, not the late-arriving persisted value.
            assertEquals(
                NomadNetBrowserViewModel.RenderingMode.MONOSPACE_ZOOM,
                racingViewModel.renderingMode.value,
            )
        }

    // ── identifyToNode ──

    @Test
    fun `identifyToNode succeeds and triggers refresh`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns Result.success(true)
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/index.mu"))

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
            assertEquals(UiMessage.Raw("Link failed"), viewModel.identifyError.value)
            assertFalse(viewModel.identifyInProgress.value)
        }

    @Test
    fun `identifyToNode does nothing when already identified`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns Result.success(true)
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/index.mu"))

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            advanceUntilIdle()

            // identifyToNode() dispatches onto the real Dispatchers.IO (only
            // Dispatchers.Main is replaced in this test), so advanceUntilIdle()
            // cannot observe its completion. Poll for the flag the IO coroutine
            // sets, with a bound, instead of racing an arbitrary sleep.
            var waitedMs = 0
            while (!viewModel.isIdentified.value && waitedMs < 2000) {
                Thread.sleep(25)
                waitedMs += 25
            }

            // Try again — should be a no-op (guarded by _isIdentified)
            viewModel.identifyToNode()
            advanceUntilIdle()

            coVerify(exactly = 1) { protocol.identifyNomadnetLink(any()) }
            assertTrue(viewModel.isIdentified.value)
        }

    @Test
    fun `loadPage to a flagged node syncs the set, bypasses the cache, and fetches fresh`() =
        runTest(testDispatcher) {
            // The persisted set already contains this node. Loading its page
            // must (a) sync the flagged set to the backend and (b) bypass the
            // page cache so a fresh fetch establishes the link - the backend
            // identifies the flagged node at link establishment time, not the
            // ViewModel. This is what removes the old "identify before the link
            // exists" race that surfaced "No active link to this node" as a
            // snackbar.
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns flowOf(setOf(nodeHash))
            // A cache entry exists for this node+path; it must NOT be used.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage

            val autoViewModel = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            autoViewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // The flagged set reached the backend (the backend owns the identify).
            coVerify(exactly = 1) { protocol.setIdentifyOnConnectNodes(setOf(nodeHash)) }
            // The cache was bypassed and a fresh fetch went out (link
            // establishment + backend identify happen inside that fetch).
            // fetchPage runs on Dispatchers.IO, which advanceUntilIdle does not
            // advance, so poll until the request call lands.
            coVerify(exactly = 0) { pageCache.get(nodeHash, "/page/index.mu") }
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            // No identify-error snackbar was raised (the old race's symptom).
            assertNull("flagged-node load must not surface an identify error", autoViewModel.identifyError.value)
        }

    @Test
    fun `loadPage to an unflagged node uses the cache and does not fetch`() =
        runTest(testDispatcher) {
            // Default setUp stub: the persisted set is empty, so a plain page
            // load takes the cache fast-path (no fetch, no link establishment,
            // no identify). The old behavior also fired a racy identify here;
            // it must not.
            every { pageCache.get(nodeHash, "/page/index.mu") } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            coVerify(exactly = 1) { pageCache.get(nodeHash, "/page/index.mu") }
            coVerify(exactly = 0) { protocol.requestNomadnetPage(any(), any(), any(), any()) }
            coVerify(exactly = 0) { protocol.identifyNomadnetLink(any()) }
            assertFalse(viewModel.isIdentified.value)
            assertNull(viewModel.identifyError.value)
        }

    @Test
    fun `identifyToNode does not persist the always-identify opt-in (the toggle owns it)`() =
        runTest(testDispatcher) {
            // Regression: the dialog's Confirm button used to re-persist the
            // "always identify" opt-in from a possibly-stale Compose snapshot,
            // which raced a just-made toggle-off (the DataStore write is async)
            // and could silently restore a node the user had turned off. The
            // persisted set is now owned solely by the toggle, so identifyToNode
            // must never write it.
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.identifyNomadnetLink(nodeHash) } returns Result.success(true)

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            advanceUntilIdle()
            waitUntilIdentifySettled()

            assertTrue(viewModel.isIdentified.value)
            coVerify(exactly = 0) { settingsRepository.setNomadNetAutoIdentifyNode(any(), any()) }
        }

    @Test
    fun `stale identify outcome is dropped when the user navigates to another node`() =
        runTest(testDispatcher) {
            // Regression: identifying to node A while the user navigates to
            // node B before A's request completes used to mark B as identified
            // (suppressing B's auto-identify and showing a false "identified"
            // state). The outcome must be scoped to the node it targeted.
            val nodeB = "1234567890abcdef1234567890abcdef"
            every { pageCache.get(any(), any()) } returns simplePage
            // Gate A's identify call so we can navigate away before it returns.
            val identifyGate = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            coEvery { protocol.identifyNomadnetLink(nodeHash) } coAnswers {
                identifyGate.first()
                Result.success(true)
            }

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            viewModel.identifyToNode()
            // The IO coroutine is now suspended at identifyNomadnetLink(nodeHash).
            advanceUntilIdle()

            // Navigate to a different node while A's identify is still in flight.
            viewModel.loadPage(nodeB)
            advanceUntilIdle()
            assertFalse(viewModel.isIdentified.value)

            // A's identify finally completes (for node A, no longer the current node).
            identifyGate.tryEmit(Unit)
            waitUntilIdentifySettled()

            // The stale outcome for A must not mark B as identified.
            assertFalse(viewModel.isIdentified.value)
            val state = viewModel.browserState.value
            assertTrue(state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals(nodeB, (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).nodeHash)
        }

    @Test
    fun `navigating to a flagged node while a stale identify is in flight fetches fresh`() =
        runTest(testDispatcher) {
            // In the old model, loadPage fired a racy identifyToNode and a
            // finally-block retried the current node when a stale identify
            // completed. That mechanism is gone: the backend now identifies a
            // flagged node's link AT LINK ESTABLISHMENT, which requires a fresh
            // fetch (cache bypass). So the regression to assert here is that
            // navigating to flagged node B - even while an unrelated manual
            // identify for A is still in flight - fetches B fresh (bypassing
            // the cache), which is what lets the backend identify B at link
            // establishment instead of serving cached anonymous content.
            //
            // A fresh ViewModel is constructed AFTER the set stub so the reactive
            // collector populates _autoIdentifyNodes with nodeB (a finite flowOf
            // is consumed once at init, so re-stubbing the setUp ViewModel's flow
            // would not re-collect).
            val nodeB = "1234567890abcdef1234567890abcdef"
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns flowOf(setOf(nodeB))
            every { pageCache.get(any(), any()) } returns simplePage
            // Gate A's identify call so it stays in flight while we navigate to B.
            val identifyGate = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            coEvery { protocol.identifyNomadnetLink(nodeHash) } coAnswers {
                identifyGate.first()
                Result.success(true)
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            // The flagged set reached the backend when this VM initialised.
            coVerify(exactly = 1) { protocol.setIdentifyOnConnectNodes(setOf(nodeB)) }

            vm.loadPage(nodeHash)
            advanceUntilIdle()

            vm.identifyToNode()
            // A's IO coroutine is now suspended at identifyNomadnetLink(nodeHash).
            advanceUntilIdle()

            // Navigate to flagged node B while A's identify is in flight.
            // B must be fetched fresh (cache bypassed) so the backend can
            // identify B at link establishment - this is the fix for "B stays
            // anonymous". fetchPage runs on Dispatchers.IO, which
            // advanceUntilIdle does not advance, so poll until the request
            // call lands.
            vm.loadPage(nodeB)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeB, "/page/index.mu", any(), any()) }
            }
            coVerify(exactly = 0) { pageCache.get(nodeB, "/page/index.mu") }

            // A's identify completes; its stale result is discarded (the staleness
            // guard) and must not surface as an error for the current node B.
            identifyGate.tryEmit(Unit)
            waitUntilIdentifySettled()
            val state = vm.browserState.value
            assertTrue(state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals(nodeB, (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).nodeHash)
            assertNull("A's stale identify must not surface an error for B", vm.identifyError.value)
            // B is a flagged node, so emitPageLoaded reflects the saved choice as
            // identified (manage mode). The identification itself is the backend's
            // responsibility at link establishment, not A's stale result.
            assertTrue("flagged node B is shown as identified via its saved flag", vm.isIdentified.value)
        }

    @Test
    fun `flagging a node after a form page loads does not re-submit the form`() =
        runTest(testDispatcher) {
            // Regression (Greptile round 2, P1): a form page's request data stays
            // in lastFetch* after it loads (only fetchPage clears it). If the
            // node is flagged while the form page is loaded, the reactive
            // collector re-emits and - without the form guard - calls refresh(),
            // which re-submits the form (refresh re-submits when
            // lastFetchFormDataJson is set), double-firing the form's side
            // effects. The identify refresh must never re-submit a form for a
            // cache benefit either: the form's own fetch already establishes
            // the link and identifies at establishment. The guard must skip
            // the identify refresh for form pages so the form submits exactly once.
            val nodesFlow = MutableStateFlow<Set<String>>(emptySet())
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/checkout.mu"))

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            // Load a form-bearing page (no flag yet). The form is submitted once.
            vm.loadPage(nodeHash, "/page/checkout.mu`item=42")
            advanceUntilIdle()
            // The form fetch runs on Dispatchers.IO (advanceUntilIdle does not
            // advance it), so poll until it has fired before asserting.
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/checkout.mu", any(), any()) }
            }
            // Wait for the form page to actually be loaded (not just that the
            // request fired): pushCurrentPageToHistory only runs on PageLoaded,
            // and the flag change below must see the settled page.
            waitForPageLoaded(vm)

            // Now flag the node while the form page is loaded. The collector
            // re-emits; without the form guard it would refresh() and re-submit
            // the form (a second request). With the guard, no refresh.
            nodesFlow.value = setOf(nodeHash)
            advanceUntilIdle()
            coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/checkout.mu", any(), any()) }
            // The form page is still the loaded page (not re-submitted into a new
            // in-flight state by the identify refresh).
            val state = vm.browserState.value
            assertTrue(
                "the form page should remain loaded after the flag",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded,
            )
            assertEquals(nodeHash, (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).nodeHash)
        }

    @Test
    fun `flagging a node while a plain page is loaded re-fetches it`() =
        runTest(testDispatcher) {
            // Rule: flagging a previously-unflagged node must immediately identify
            // and re-fetch the displayed page (identified content). The backend
            // identifies at link establishment (the fresh fetch establishes/reuses
            // the link); the ViewModel's re-fetch delivers the identified page.
            val nodesFlow = MutableStateFlow<Set<String>>(emptySet())
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg<String>()))
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            vm.loadPage(nodeHash)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            // Wait for the page to be PageLoaded before flagging: the collector's
            // identifyRefresh only fires on PageLoaded (it arms pendingIdentifyRefreshFor
            // otherwise), and we want to test the direct re-fetch path.
            waitForPageLoaded(vm)

            // Flag the node: the collector detects newlyFlagged and re-fetches.
            nodesFlow.value = setOf(nodeHash)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 2) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            waitForPageLoaded(vm)
            // The page landed as PageLoaded on the flagged node.
            val state = vm.browserState.value
            assertTrue("flag re-fetch lands on a loaded page",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals(nodeHash, (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).nodeHash)
        }

    @Test
    fun `unflagging a node does not re-fetch the loaded page`() =
        runTest(testDispatcher) {
            // Rule: removing the flag (unflagging) must NOT trigger a re-fetch.
            // The backend tears down the node's identified link (so the next
            // refresh/navigation establishes a fresh, anonymous link and the
            // user stops browsing as identified); the ViewModel's manual
            // refresh button is the path to get new content, and an unflagged
            // node's page stays as-is until re-navigated or explicitly
            // refreshed.
            val nodesFlow = MutableStateFlow<Set<String>>(emptySet())
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg<String>()))
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            vm.loadPage(nodeHash)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            // Settle the page before mutating the flag set, so the flag-ON re-fetch
            // exercises the PageLoaded path deterministically.
            waitForPageLoaded(vm)

            // Flag ON: re-fetch fires (2 total requests).
            nodesFlow.value = setOf(nodeHash)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 2) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            waitForPageLoaded(vm)

            // Flag OFF: no re-fetch (still 2 total requests, not 3). The
            // collector's newlyFlagged is empty, so no coroutine is launched -
            // advanceUntilIdle fully settles the state.
            nodesFlow.value = emptySet()
            advanceUntilIdle()
            coVerify(exactly = 2) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            // The page remains loaded and unchanged after unflagging.
            val state = vm.browserState.value
            assertTrue("page stays loaded after unflag",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals("/page/index.mu", (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).path)
        }

    @Test
    fun `unflagging the displayed node resets isIdentified`() =
        runTest(testDispatcher) {
            // Regression (Greptile P1): _isIdentified is set true when a
            // flagged node's page loads (emitPageLoaded reflects the saved
            // "always identify" flag) and only reset on navigation/load. If the
            // user unflags the node they're currently on, the backend tears down
            // the identified link, but _isIdentified would stay true - the node
            // dialog keeps showing "identified / Done" for a link that no longer
            // carries our identity, and identifyToNode() refuses the action.
            val nodesFlow = MutableStateFlow<Set<String>>(setOf(nodeHash))
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg<String>()))
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.setIdentifyOnConnectNodes(setOf(nodeHash)) }
            }

            // Load the flagged node's page: emitPageLoaded reflects the saved
            // flag as identified.
            vm.loadPage(nodeHash)
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            waitForPageLoaded(vm)
            assertTrue("flagged node's loaded page is shown as identified", vm.isIdentified.value)

            // Unflag the displayed node: the link is torn down (backend) and the
            // identified state must be cleared. (Don't assert the exact
            // setIdentifyOnConnectNodes count: the collector also re-pushes the
            // set on RNS-READY re-sync, so the count is init-dependent. The
            // behavior that matters is isIdentified being cleared.)
            nodesFlow.value = emptySet()
            advanceUntilIdle()
            assertFalse("unflagging the displayed node must clear isIdentified", vm.isIdentified.value)
        }

    @Test
    fun `goBack to a flagged node's stored plain page re-fetches identified content`() =
        runTest(testDispatcher) {
            // Regression (stale Greptile round-1 thread: "Back can still display
            // a stored document"): goBack shows the stored history document
            // directly. A page visited while UNflagged (anonymous content) can
            // sit in history; once the node is flagged, Back must re-fetch it
            // identified, not display the stale anonymous document. This is the
            // flagged-node cache-bypass invariant applied to the Back path, which
            // otherwise never fetches. Unflagged nodes keep instant-back.
            val nodesFlow = MutableStateFlow<Set<String>>(emptySet())
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            // Echo the requested path back in the result so each fetch yields a
            // page for the path actually requested (a hardcoded path would label
            // every page "index" and confuse the flagged-node re-fetch logic).
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg<String>()))
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            // Visit page A (unflagged) then page B on the same node; A is pushed to history.
            vm.loadPage(nodeHash, "/page/index.mu")
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            // Wait for page A to be PageLoaded before navigating to B:
            // pushCurrentPageToHistory only pushes on PageLoaded.
            waitForPageLoaded(vm)
            vm.navigateToLink("/page/second.mu", emptyList())
            advanceUntilIdle()
            waitForPageLoaded(vm)

            // Flag the node (re-fetches the current page B identified), then Back
            // to A: A is flagged and plain, so identifyRefresh re-fetches it
            // (identified) instead of the stale anonymous document.
            nodesFlow.value = setOf(nodeHash)
            advanceUntilIdle()
            waitForPageLoaded(vm)

            assertTrue(vm.goBack())
            advanceUntilIdle()
            waitForPageLoaded(vm)

            // A was re-fetched identified (the Back re-fetch) - 1 original load +
            // 1 identify refresh. The collector does NOT re-fetch A on the flag
            // (it re-fetches the then-current page B), so exactly 2 for A.
            waitForVerify {
                coVerify(exactly = 2) { protocol.requestNomadnetPage(nodeHash, "/page/index.mu", any(), any()) }
            }
            val state = vm.browserState.value
            assertTrue("back navigation lands on the restored plain page",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals("/page/index.mu", (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).path)
        }

    @Test
    fun `goBack to a flagged node's stored form page does not re-submit the form`() =
        runTest(testDispatcher) {
            // Regression: the Back reuse of identifyRefresh must honor the form
            // guard. A form page's stored history entry carries its field tokens;
            // re-fetching it would re-submit the form (double side effects).
            // identifyRefresh keys off the page's OWN tokens and skips form pages.
            val nodesFlow = MutableStateFlow<Set<String>>(emptySet())
            every { settingsRepository.nomadNetAutoIdentifyNodesFlow } returns nodesFlow
            every { pageCache.get(any(), any()) } returns null
            // Echo the requested path back in the result so navigating to a plain
            // page yields that page (not a checkout-labeled one).
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } answers {
                Result.success(NomadnetPageResult(simplePage, secondArg<String>()))
            }

            val vm = NomadNetBrowserViewModel(protocol, pageCache, imageCache, settingsRepository, rnsCore)
            advanceUntilIdle()

            // Submit a form page (unflagged): submitted exactly once. The form
            // fetch runs on Dispatchers.IO, so poll until it has fired.
            vm.loadPage(nodeHash, "/page/checkout.mu`item=42")
            advanceUntilIdle()
            waitForVerify {
                coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/checkout.mu", any(), any()) }
            }
            // Wait for the form page to actually be PageLoaded before navigating
            // away: pushCurrentPageToHistory only pushes on PageLoaded, so a
            // still-Loading checkout page would not be saved to history and the
            // later goBack would restore the wrong page.
            waitForPageLoaded(vm)

            // Navigate to a plain page so the form page is pushed to history.
            // The mock echoes the requested path back in the result, so this is a
            // genuine index page (not a checkout-named one), keeping the collector
            // honest when the node is flagged below.
            vm.navigateToLink("/page/index.mu", emptyList())
            advanceUntilIdle()
            waitForPageLoaded(vm)

            // Flag the node (re-fetches the current index page identified), then
            // Back to the form page.
            nodesFlow.value = setOf(nodeHash)
            advanceUntilIdle()
            waitForPageLoaded(vm)
            vm.goBack()
            advanceUntilIdle()
            // Back re-displays the stored form page synchronously (no re-fetch,
            // see below); wait until it is the loaded page before asserting.
            waitForPageLoaded(vm)

            // The form was NOT re-submitted by the Back re-fetch: identifyRefresh
            // keys off the restored form page's OWN field tokens ([item=42]) and
            // skips it - a re-fetch would double-fire the form. Direct (not
            // retried) assertion: a spurious re-fetch is the regression we are
            // guarding against, so it must fail, not be retried away.
            coVerify(exactly = 1) { protocol.requestNomadnetPage(nodeHash, "/page/checkout.mu", any(), any()) }
            // The restored form page is still displayed (not replaced by a
            // re-fetch attempt).
            val state = vm.browserState.value
            assertTrue("back navigation lands on the restored form page",
                state is NomadNetBrowserViewModel.BrowserState.PageLoaded)
            assertEquals("/page/checkout.mu", (state as NomadNetBrowserViewModel.BrowserState.PageLoaded).path)
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

    // ── getCurrentUrl ──

    @Test
    fun `getCurrentUrl returns hash colon path when PageLoaded`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash, "/page/about.mu")
            advanceUntilIdle()

            assertEquals("$nodeHash:/page/about.mu", viewModel.getCurrentUrl())
        }

    @Test
    fun `getCurrentUrl returns null in Initial state`() {
        assertNull(viewModel.getCurrentUrl())
    }

    @Test
    fun `getCurrentUrl returns null in Loading state`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns null
            // Don't resolve the network call so state stays Loading
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } coAnswers {
                // Never complete
                kotlinx.coroutines.awaitCancellation()
            }

            viewModel.loadPage(nodeHash)
            // State is Loading now
            assertNull(viewModel.getCurrentUrl())
        }

    @Test
    fun `getCurrentUrl returns null in Error state`() =
        runTest(testDispatcher) {
            viewModel.cancelLoading() // Forces Error state
            advanceUntilIdle()

            assertNull(viewModel.getCurrentUrl())
        }

    // ── getShareableUrl ──

    @Test
    fun `getShareableUrl prepends nomadnetwork scheme`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            assertEquals("nomadnetwork://$nodeHash:/page/index.mu", viewModel.getShareableUrl())
        }

    @Test
    fun `getShareableUrl returns null when not loaded`() {
        assertNull(viewModel.getShareableUrl())
    }

    // ── navigateToUrl ──

    @Test
    fun `navigateToUrl with hash colon path navigates to page`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.navigateToUrl("$nodeHash:/page/about.mu")
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, state.nodeHash)
            assertEquals("/page/about.mu", state.path)
        }

    @Test
    fun `navigateToUrl with hash only defaults to index path`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.navigateToUrl(nodeHash)
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, state.nodeHash)
            assertEquals("/page/index.mu", state.path)
        }

    @Test
    fun `navigateToUrl strips nomadnetwork scheme and navigates`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.navigateToUrl("nomadnetwork://$nodeHash:/page/status.mu")
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, state.nodeHash)
            assertEquals("/page/status.mu", state.path)
        }

    @Test
    fun `navigateToUrl with lxmf@ emits OpenConversation event`() =
        runTest(testDispatcher) {
            val eventDeferred = async { viewModel.navigationEvent.first() }
            viewModel.navigateToUrl("lxmf@deadbeef01234567")
            advanceUntilIdle()

            val event = eventDeferred.await()
            assertTrue(event is NomadNetBrowserViewModel.NavigationEvent.OpenConversation)
            assertEquals(
                "deadbeef01234567",
                (event as NomadNetBrowserViewModel.NavigationEvent.OpenConversation).destinationHash,
            )
        }

    @Test
    fun `navigateToUrl lowercases node hash`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.navigateToUrl("ABCDEF01234567890ABCDEF012345678:/page/index.mu")
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals("abcdef01234567890abcdef012345678", state.nodeHash)
        }

    @Test
    fun `navigateToUrl with empty input is no-op`() =
        runTest(testDispatcher) {
            viewModel.navigateToUrl("")
            advanceUntilIdle()

            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Initial)
        }

    @Test
    fun `navigateToUrl trims whitespace`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            viewModel.navigateToUrl("  $nodeHash:/page/test.mu  ")
            advanceUntilIdle()

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, state.nodeHash)
            assertEquals("/page/test.mu", state.path)
        }

    @Test
    fun `navigateToUrl pushes current page to history`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage

            // Load initial page
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()
            assertFalse(viewModel.canGoBack.value)

            // Navigate via URL bar to a different page
            val otherNode = "1234567890abcdef1234567890abcdef"
            viewModel.navigateToUrl("$otherNode:/page/other.mu")
            advanceUntilIdle()

            // Should be able to go back to the first page
            assertTrue(viewModel.canGoBack.value)
            viewModel.goBack()
            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals(nodeHash, state.nodeHash)
            assertEquals("/page/index.mu", state.path)
        }

    // ── retry ──

    @Test
    fun `retry after failed page load retries the same page`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns null
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Connection timed out"))

            viewModel.loadPage(nodeHash, "/page/about.mu")
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Error)

            // Now make the retry succeed
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/about.mu"))

            viewModel.retry()
            advanceUntilIdle()
            Thread.sleep(100)

            val state = viewModel.browserState.value as NomadNetBrowserViewModel.BrowserState.PageLoaded
            assertEquals("/page/about.mu", state.path)
        }

    @Test
    fun `retry after failed form submission resubmits form data`() =
        runTest(testDispatcher) {
            every { pageCache.get(any(), any()) } returns simplePage
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Connection failed"))

            // Load a page first
            viewModel.loadPage(nodeHash)
            advanceUntilIdle()

            // Fill in form and submit (will fail)
            viewModel.updateField("username", "alice")
            viewModel.navigateToLink("/page/login.mu", listOf("username"))
            advanceUntilIdle()
            Thread.sleep(100)

            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Error)

            // Retry should resubmit the form data
            coEvery { protocol.requestNomadnetPage(any(), any(), any(), any()) } returns
                Result.success(NomadnetPageResult(simplePage, "/page/login.mu"))

            viewModel.retry()
            advanceUntilIdle()
            Thread.sleep(100)

            // Verify form data was resubmitted (non-null formDataJson containing "alice")
            coVerify {
                protocol.requestNomadnetPage(
                    nodeHash,
                    "/page/login.mu",
                    match { it != null && it.contains("alice") },
                    any(),
                )
            }
        }

    @Test
    fun `retry with no previous fetch is no-op`() =
        runTest(testDispatcher) {
            viewModel.retry()
            advanceUntilIdle()
            assertTrue(viewModel.browserState.value is NomadNetBrowserViewModel.BrowserState.Initial)
        }

    // ── Page images ──

    @Test
    fun `setImageLoadingMode updates state and persists the choice`() =
        runTest(testDispatcher) {
            viewModel.setImageLoadingMode(network.columba.app.nomadnet.ImageLoadingMode.MANUAL)

            assertEquals(
                network.columba.app.nomadnet.ImageLoadingMode.MANUAL,
                viewModel.imageLoadingMode.value,
            )
            coVerify(exactly = 1) { settingsRepository.saveNomadNetImageLoadingMode("MANUAL") }
        }

    @Test
    fun `clearImageCache wipes the disk cache and resets in-flight image states`() =
        runTest(testDispatcher) {
            viewModel.clearImageCache()
            advanceUntilIdle()

            verify { imageCache.clear() }
            assertTrue(viewModel.imageStates.value.isEmpty())
        }
}
