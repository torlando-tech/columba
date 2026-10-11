package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import network.columba.app.data.repository.Announce
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.PreferredPropagationNodeRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.service.PropagationNodeManager
import network.columba.app.service.RelayInfo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@ExperimentalCoroutinesApi
class PropagationNodesViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    private val preferredRepo = mockk<PreferredPropagationNodeRepository>()
    private val announceRepo = mockk<AnnounceRepository>()
    private val settingsRepo = mockk<SettingsRepository>()
    // Strict mock: the VM only reads `manager.currentRelay` (stubbed in @Before)
    // and calls `setManualRelay` (stubbed per-test), so no relaxed fallback is needed.
    private val manager = mockk<PropagationNodeManager>()

    private val orderedNodes = MutableStateFlow<List<PreferredPropagationNodeEntity>>(emptyList())
    private val currentRelay = MutableStateFlow<RelayInfo?>(null)
    private val importOffered = MutableStateFlow(false)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        every { preferredRepo.observeOrdered() } returns orderedNodes
        every { manager.currentRelay } returns currentRelay
        every { settingsRepo.preferredNodesImportOfferedFlow } returns importOffered
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun node(hash: String, restriction: String = "ANY", name: String? = null) =
        PreferredPropagationNodeEntity(
            destinationHash = hash,
            priority = 0,
            networkRestriction = restriction,
            enabled = true,
            displayName = name,
        )

    private fun relay(hash: String, name: String) =
        RelayInfo(
            destinationHash = hash,
            displayName = name,
            hops = 1,
            isAutoSelected = false,
            lastSeenTimestamp = 0L,
        )

    private fun vm() =
        PropagationNodesViewModel(preferredRepo, announceRepo, settingsRepo, manager)

    @Test
    fun `state reflects ordered list, active relay, and import eligibility`() = runTest {
        val model = vm()
        orderedNodes.value = listOf(node("aa11", "ANY", "boat"), node("bb22"))
        currentRelay.value = relay("bb22", "ashore")
        importOffered.value = false

        val state = model.state.first { it.list.size == 2 }
        assertEquals(listOf("aa11", "bb22"), state.list.map { it.destinationHash })
        assertEquals("ashore", state.activeName)
        // The second node matches the active relay -> isActive true; first false.
        assertTrue(state.list[1].isActive)
        assertTrue(!state.list[0].isActive)
        // Non-empty list -> no import CTA even though it was never offered.
        assertTrue(!state.canImportCurrent)
    }

    @Test
    fun `empty list with an active relay and no prior offer shows import CTA`() = runTest {
        val model = vm()
        orderedNodes.value = emptyList()
        currentRelay.value = relay("cc33", "internet")
        importOffered.value = false

        val state = model.state.first { it.activeHash == "cc33" }
        assertTrue(state.canImportCurrent)
        assertEquals("internet", state.activeName)

        // Once offered, the CTA disappears even with an empty list + active relay.
        // Predicate requires the active hash so it can't match the StateFlow initial value.
        importOffered.value = true
        val afterOffer = model.state.first { it.activeHash == "cc33" && !it.canImportCurrent }
        assertTrue(!afterOffer.canImportCurrent)
    }

    @Test
    fun `addFromAnnounce persists the announce with its public key`() = runTest {
        val model = vm()
        val announce =
            Announce(
                destinationHash = "dd44",
                peerName = "relay-d",
                publicKey = byteArrayOf(0x01, 0x02),
                appData = null,
                hops = 2,
                lastSeenTimestamp = 1000L,
                nodeType = "PROPAGATION_NODE",
            )
        val hashSlot = slot<String>()
        val pkSlot = slot<String>()
        coEvery {
            preferredRepo.add(
                destinationHash = capture(hashSlot),
                networkRestriction = any(),
                displayName = any(),
                publicKey = capture(pkSlot),
            )
        } returns Unit

        model.addFromAnnounce(announce)
        runCurrent()

        assertEquals("dd44", hashSlot.captured)
        assertEquals("0102", pkSlot.captured)
        coVerify(exactly = 1) {
            preferredRepo.add(
                destinationHash = "dd44",
                networkRestriction = NetworkRestriction.ANY.name,
                displayName = "relay-d",
                publicKey = "0102",
            )
        }
    }

    @Test
    fun `reorder forwards the ordered key list to the repository`() = runTest {
        val model = vm()
        val orderSlot = slot<List<String>>()
        coEvery { preferredRepo.reorder(capture(orderSlot)) } returns Unit

        model.reorder(listOf("bb22", "aa11"))
        runCurrent()

        assertEquals(listOf("bb22", "aa11"), orderSlot.captured)
    }

    @Test
    fun `setActiveNow forces the manager onto the chosen hash`() = runTest {
        val model = vm()
        val hashSlot = slot<String>()
        coEvery { manager.setManualRelay(capture(hashSlot)) } returns Unit

        model.setActiveNow("ee55")
        runCurrent()

        assertEquals("ee55", hashSlot.captured)
    }

    @Test
    fun `importCurrentRelay adds the active relay and marks the offer used`() = runTest {
        val model = vm()
        val importHashSlot = slot<String>()
        orderedNodes.value = emptyList()
        currentRelay.value = relay("ff66", "home")
        importOffered.value = false
        // Wait for the state to reflect the active relay.
        model.state.first { it.activeHash == "ff66" }

        coEvery {
            preferredRepo.add(
                destinationHash = capture(importHashSlot),
                networkRestriction = any(),
                displayName = any(),
                publicKey = null,
            )
        } returns Unit
        coEvery { settingsRepo.markPreferredNodesImportOffered() } returns Unit

        model.importCurrentRelay()
        runCurrent()

        // The active relay's hash is the one imported into the (empty) list.
        assertEquals("ff66", importHashSlot.captured)
        coVerify(exactly = 1) {
            preferredRepo.add(
                destinationHash = "ff66",
                networkRestriction = NetworkRestriction.ANY.name,
                displayName = "home",
                publicKey = null,
            )
        }
        coVerify(exactly = 1) { settingsRepo.markPreferredNodesImportOffered() }
    }

    @Test
    fun `loadTopCandidates returns the repository announce list`() = runTest {
        val model = vm()
        val announce =
            Announce(
                destinationHash = "9900",
                peerName = "far",
                publicKey = ByteArray(0),
                appData = null,
                hops = 3,
                lastSeenTimestamp = 0L,
                nodeType = "PROPAGATION_NODE",
            )
        coEvery { announceRepo.getTopPropagationNodes(10) } returns flowOf(listOf(announce))

        val result = model.loadTopCandidates()

        assertEquals(1, result.size)
        assertEquals("9900", result[0].destinationHash)
    }
}
