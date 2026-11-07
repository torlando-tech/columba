package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.nomadnet.NomadNetSavedSite
import network.columba.app.nomadnet.NomadNetShortcuts
import network.columba.app.repository.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [NomadNetSitesViewModel]: the saved list mirrors DataStore, node
 * labels prefer the user's contact nickname over the announce name, and the
 * write actions go through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NomadNetSitesViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var savedSitesFlow: MutableStateFlow<List<NomadNetSavedSite>>
    private lateinit var viewModel: NomadNetSitesViewModel

    private val nodeA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private val site = NomadNetSavedSite(nodeA, "/page/index.mu", "Index", 1_000L)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk()
        contactRepository = mockk()
        announceRepository = mockk()
        savedSitesFlow = MutableStateFlow(listOf(site))

        every { settingsRepository.nomadNetSavedSitesFlow } returns savedSitesFlow
        every { contactRepository.getContactFlow(any()) } returns flowOf(null)
        every { announceRepository.getAnnounceFlow(any()) } returns flowOf(null)
        // Write through the same flow the view model observes, so the tests
        // assert the resulting list rather than only that a call happened.
        coEvery { settingsRepository.toggleNomadNetSavedSite(any(), any(), any()) } answers {
            val saved = NomadNetSavedSite(firstArg(), secondArg(), thirdArg<String?>(), SAVED_AT)
            savedSitesFlow.value = listOf(saved) + savedSitesFlow.value.filterNot { it.key == saved.key }
            true
        }
        coEvery { settingsRepository.removeNomadNetSavedSite(any(), any()) } answers {
            val key = NomadNetSavedSite(firstArg(), secondArg(), null, 0L).key
            savedSitesFlow.value = savedSitesFlow.value.filterNot { it.key == key }
        }
        coEvery { settingsRepository.restoreNomadNetSavedSite(any()) } answers {
            val restored = firstArg<NomadNetSavedSite>()
            savedSitesFlow.value = listOf(restored) + savedSitesFlow.value.filterNot { it.key == restored.key }
        }

        viewModel = NomadNetSitesViewModel(settingsRepository, contactRepository, announceRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `savedSites mirrors the stored list`() =
        runTest {
            assertEquals(listOf(site), viewModel.savedSites.first())

            savedSitesFlow.value = emptyList()

            assertEquals(emptyList<NomadNetSavedSite>(), viewModel.savedSites.first())
        }

    @Test
    fun `savedKeys exposes page keys for the browser bookmark state`() =
        runTest {
            assertTrue(NomadNetShortcuts.shortcutId(nodeA, "/page/index.mu") in viewModel.savedKeys.first())
        }

    @Test
    fun `contact nickname wins over the announce name`() =
        runTest {
            every { contactRepository.getContactFlow(nodeA) } returns flowOf(contact(nickname = "My Node"))
            every { announceRepository.getAnnounceFlow(nodeA) } returns flowOf(announce(name = "Announced"))

            val summary = viewModel.nodeSummary(nodeA).first()

            assertEquals("My Node", summary?.displayName)
            assertEquals("NODE", summary?.nodeType)
            assertEquals("language", summary?.iconName)
        }

    @Test
    fun `announce name is used when there is no nickname`() =
        runTest {
            every { contactRepository.getContactFlow(nodeA) } returns flowOf(contact(nickname = null))
            every { announceRepository.getAnnounceFlow(nodeA) } returns flowOf(announce(name = "Announced"))

            assertEquals("Announced", viewModel.nodeSummary(nodeA).first()?.displayName)
        }

    @Test
    fun `an unknown node has no summary`() =
        runTest {
            assertNull(viewModel.nodeSummary(nodeA).first())
        }

    @Test
    fun `a blank node has no summary`() =
        runTest {
            assertNull(viewModel.nodeSummary("").first())
        }

    @Test
    fun `toggleSaved saves the page and removeSaved drops it again`() =
        runTest {
            savedSitesFlow.value = emptyList()
            val nodePath = "/page/forum/thread.mu"

            viewModel.toggleSaved(nodeA, nodePath, "Thread")
            val saved = viewModel.savedSites.first().single()

            assertEquals(nodeA, saved.nodeHash)
            assertEquals(nodePath, saved.persistPath)
            assertEquals("Thread", saved.label)

            viewModel.removeSaved(nodeA, nodePath)

            assertTrue(viewModel.savedSites.first().isEmpty())
        }

    @Test
    fun `restoreSaved puts the exact stored page back`() =
        runTest {
            savedSitesFlow.value = emptyList()

            viewModel.restoreSaved(site)

            assertEquals(
                "undo must restore the stored timestamp, not now()",
                listOf(site),
                viewModel.savedSites.first(),
            )
        }

    private fun contact(nickname: String?): ContactEntity =
        ContactEntity(
            destinationHash = nodeA,
            identityHash = LOCAL_IDENTITY,
            publicKey = null,
            customNickname = nickname,
            addedTimestamp = 0L,
            addedVia = "ANNOUNCE",
        )

    private fun announce(name: String): Announce =
        Announce(
            destinationHash = nodeA,
            peerName = name,
            publicKey = ByteArray(16),
            appData = null,
            hops = 1,
            lastSeenTimestamp = 0L,
            nodeType = "NODE",
            iconName = "language",
            iconForegroundColor = "FFFFFF",
            iconBackgroundColor = "1E88E5",
        )

    private companion object {
        const val LOCAL_IDENTITY = "ffffffffffffffffffffffffffffffff"
        const val SAVED_AT = 5_000L
    }
}
