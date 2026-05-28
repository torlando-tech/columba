package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.PagingData
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import network.columba.app.data.model.InterfaceType
import network.columba.app.data.repository.Announce
import network.columba.app.rns.api.model.NodeType
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.AnnounceStreamViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for AnnounceStreamScreen.kt.
 * Tests the announce button functionality added to the top bar.
 * Uses Robolectric + Compose for local testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnounceStreamScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Top App Bar Tests ==========

    @Test
    fun announceStreamScreen_displaysTitle() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Discovered Nodes").assertIsDisplayed()
    }

    @Test
    fun announceStreamScreen_displaysReachableCount() {
        val mockViewModel = createMockAnnounceStreamViewModel(reachableCount = 5)

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("5 nodes in range (active paths)").assertIsDisplayed()
    }

    @Test
    fun announceStreamScreen_displaysAnnounceButton() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Announce now").assertIsDisplayed()
    }

    // ========== Announce Button Tests ==========

    @Test
    fun announceButton_whenNotAnnouncing_isEnabled() {
        val mockViewModel = createMockAnnounceStreamViewModel(isAnnouncing = false)

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Announce now").assertIsEnabled()
    }

    @Test
    fun announceButton_whenAnnouncing_showsProgressIndicator() {
        val mockViewModel = createMockAnnounceStreamViewModel(isAnnouncing = true)

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // When announcing, the Campaign icon is replaced with a progress indicator
        // so "Announce now" content description should not exist
        composeTestRule.onNodeWithContentDescription("Announce now").assertDoesNotExist()
    }

    @Test
    fun announceButton_click_triggersTriggerAnnounce() {
        var triggerAnnounceCalled = false
        val mockViewModel = createMockAnnounceStreamViewModel()
        every { mockViewModel.triggerAnnounce() } answers { triggerAnnounceCalled = true }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Announce now").performClick()

        verify { mockViewModel.triggerAnnounce() }
        assertTrue("triggerAnnounce() should be called when button is clicked", triggerAnnounceCalled)
    }

    // ========== Search Tests ==========

    @Test
    fun announceStreamScreen_displaysSearchButton() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun announceStreamScreen_searchButtonClick_togglesSearchBar() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Initially search bar is hidden
        composeTestRule.onNodeWithText("Search by name or hash...").assertDoesNotExist()

        // Click search button
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Search bar appears
        composeTestRule.onNodeWithText("Search by name or hash...").assertIsDisplayed()
    }

    // ========== Empty State Tests ==========

    @Test
    fun announceStreamScreen_emptyList_displaysEmptyState() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("No nodes discovered yet").assertIsDisplayed()
    }

    @Test
    fun announceStreamScreen_emptyList_displaysEmptyStateDescription() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Listening for announces...").assertIsDisplayed()
    }

    @Test
    fun emptyAnnounceState_displaysCorrectText() {
        composeTestRule.setContent {
            EmptyAnnounceState()
        }

        composeTestRule.onNodeWithText("No nodes discovered yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening for announces...").assertIsDisplayed()
    }

    // ========== Filter Chip Tests ==========

    @Test
    fun filterChips_alwaysVisibleAboveList() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Chip rows should render without any interaction — aspect row labels.
        composeTestRule.onNodeWithText("Peers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relays").assertIsDisplayed()
        composeTestRule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun filterChips_displayInterfaceOptions() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Interface chip row is a LazyRow; later chips ("Other") sit past the
        // default test viewport. Scroll the row to surface each chip before
        // asserting. `hasScrollAction()` matches both chip rows (aspect + interface)
        // — index [1] selects the interface row, which is second in the column.
        val interfaceRow = composeTestRule.onAllNodes(hasScrollAction())[1]
        listOf("Local", "TCP", "TCP Server", "Bluetooth", "RNode", "Other").forEach { label ->
            interfaceRow.performScrollToNode(hasText(label))
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun filterChips_tappingAspect_fromAllNarrowsToSingle() {
        // Default = canonical All state ({PEER, NODE, PROPAGATION_NODE} + audio).
        // Exclusive: tap "Sites" should narrow to just NODE and drop audio.
        val mockViewModel = createMockAnnounceStreamViewModel()
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }
        every { mockViewModel.updateShowAudioAnnounces(any()) } answers { capturedShowAudio = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Sites").performClick()

        assertEquals(
            "Exclusive: tapping Sites from All should narrow to NODE only, not append",
            setOf(NodeType.NODE),
            capturedTypes,
        )
        assertEquals(
            "Exclusive: selecting a non-audio aspect should deselect audio",
            false,
            capturedShowAudio,
        )
    }

    @Test
    fun filterChips_tappingAudioAspect_dropsNodeTypes() {
        // Default = canonical All. Audio is exclusive too: tapping it should
        // drop every node type so only PHONE/audio announces remain visible.
        val mockViewModel = createMockAnnounceStreamViewModel()
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }
        every { mockViewModel.updateShowAudioAnnounces(any()) } answers { capturedShowAudio = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Audio").performClick()

        assertEquals(emptySet<NodeType>(), capturedTypes)
        assertEquals(true, capturedShowAudio)
    }

    @Test
    fun filterChips_tappingAll_restoresFullSetFromSingle() {
        // Start with a single aspect (Peers); tapping the All chip directly
        // must restore all 3 node types + re-enable audio. Greptile flagged
        // that the All-click path had no direct coverage.
        val mockViewModel =
            createMockAnnounceStreamViewModel(
                selectedNodeTypes = setOf(NodeType.PEER),
                showAudioAnnounces = false,
            )
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }
        every { mockViewModel.updateShowAudioAnnounces(any()) } answers { capturedShowAudio = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Aspect row's "All" is the first node; interface row has its own "All".
        // Match the aspect-row All by its position (first occurrence at row 0).
        composeTestRule.onAllNodes(hasText("All"))[0].performClick()

        assertEquals(
            "Tapping All should restore the full node-type set",
            setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
            capturedTypes,
        )
        assertEquals(
            "Tapping All should re-enable audio",
            true,
            capturedShowAudio,
        )
    }

    @Test
    fun filterChips_tappingAspect_normalisesMessyMultiState() {
        // Pre-PR persisted state could carry over a partial set like
        // {PEER, NODE} with audio=false — neither the canonical All nor a
        // single-chip state. Tapping any chip from that state should still
        // narrow cleanly via the exclusive replace path (proving the
        // handler isn't sensitive to the input shape).
        val mockViewModel =
            createMockAnnounceStreamViewModel(
                selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE),
                showAudioAnnounces = false,
            )
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }
        every { mockViewModel.updateShowAudioAnnounces(any()) } answers { capturedShowAudio = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Relays").performClick()

        assertEquals(setOf(NodeType.PROPAGATION_NODE), capturedTypes)
        assertEquals(false, capturedShowAudio)
    }

    @Test
    fun filterChips_tappingActiveAspect_snapsBackToAll() {
        // Single aspect selected: PEER only, audio off → activeAspects = {PEER}.
        // Tapping the only-active chip should snap to All instead of leaving
        // an empty filter (which would render a blank list).
        val mockViewModel =
            createMockAnnounceStreamViewModel(
                selectedNodeTypes = setOf(NodeType.PEER),
                showAudioAnnounces = false,
            )
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }
        every { mockViewModel.updateShowAudioAnnounces(any()) } answers { capturedShowAudio = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Peers").performClick()

        assertEquals(
            "Re-tapping the only active aspect should snap to all node types",
            setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
            capturedTypes,
        )
        assertEquals(
            "Snap-back should also re-enable showAudioAnnounces to match All-chip semantics",
            true,
            capturedShowAudio,
        )
    }

    @Test
    fun filterChips_tappingInterface_selectsExclusively() {
        // No interface restriction initially. Tapping TCP picks just TCP.
        val mockViewModel = createMockAnnounceStreamViewModel()
        var capturedInterfaces: Set<InterfaceType>? = null
        every {
            mockViewModel.updateSelectedInterfaceTypes(any())
        } answers { capturedInterfaces = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("TCP").performClick()

        verify { mockViewModel.updateSelectedInterfaceTypes(setOf(InterfaceType.TCP_CLIENT)) }
        assertEquals(setOf(InterfaceType.TCP_CLIENT), capturedInterfaces)
    }

    @Test
    fun filterChips_tappingInterface_replacesPriorSelection() {
        // Start with RNode active. Exclusive: tapping TCP must REPLACE,
        // not add — capturing the same regression issue #862 calls out.
        val mockViewModel =
            createMockAnnounceStreamViewModel(
                selectedInterfaceTypes = setOf(InterfaceType.RNODE),
            )
        var capturedInterfaces: Set<InterfaceType>? = null
        every {
            mockViewModel.updateSelectedInterfaceTypes(any())
        } answers { capturedInterfaces = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("TCP").performClick()

        assertEquals(
            "Exclusive: tapping TCP while RNode was active should replace, not combine",
            setOf(InterfaceType.TCP_CLIENT),
            capturedInterfaces,
        )
    }

    @Test
    fun filterChips_tappingActiveInterface_snapsBackToAll() {
        // Only TCP active; tapping TCP again should clear the restriction.
        val mockViewModel =
            createMockAnnounceStreamViewModel(
                selectedInterfaceTypes = setOf(InterfaceType.TCP_CLIENT),
            )
        var capturedInterfaces: Set<InterfaceType>? = null
        every {
            mockViewModel.updateSelectedInterfaceTypes(any())
        } answers { capturedInterfaces = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("TCP").performClick()

        assertEquals(
            "Tapping the only-active interface chip should snap back to All (empty)",
            emptySet<InterfaceType>(),
            capturedInterfaces,
        )
    }

    // ========== Test Helpers ==========

    // This is a mock builder that mirrors the StateFlow surface of
    // AnnounceStreamViewModel — every parameter maps 1:1 to a public StateFlow
    // on the real ViewModel. Grouping these into a config object would obscure
    // that mirror; @Suppress is the right call per the no-baseline-bumps rule.
    @Suppress("detekt:LongParameterList")
    private fun createMockAnnounceStreamViewModel(
        reachableCount: Int = 0,
        isAnnouncing: Boolean = false,
        announceSuccess: Boolean = false,
        announceError: String? = null,
        // Default = canonical All state under the new exclusive contract:
        // all 3 node types + audio active. Tests that need a single-chip or
        // partial state override these explicitly.
        selectedNodeTypes: Set<NodeType> =
            setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
        showAudioAnnounces: Boolean = true,
        selectedInterfaceTypes: Set<InterfaceType> = emptySet(),
        searchQuery: String = "",
    ): AnnounceStreamViewModel {
        val mockViewModel = mockk<AnnounceStreamViewModel>()

        // Stub StateFlow properties
        every { mockViewModel.announces } returns flowOf(PagingData.empty<Announce>())
        every { mockViewModel.reachableAnnounceCount } returns MutableStateFlow(reachableCount)
        every { mockViewModel.searchQuery } returns MutableStateFlow(searchQuery)
        every { mockViewModel.selectedNodeTypes } returns MutableStateFlow(selectedNodeTypes)
        every { mockViewModel.showAudioAnnounces } returns MutableStateFlow(showAudioAnnounces)
        every { mockViewModel.selectedInterfaceTypes } returns MutableStateFlow(selectedInterfaceTypes)
        every { mockViewModel.isAnnouncing } returns MutableStateFlow(isAnnouncing)
        every { mockViewModel.announceSuccess } returns MutableStateFlow(announceSuccess)
        every { mockViewModel.announceError } returns MutableStateFlow(announceError)

        // Stub action methods that may be called
        every { mockViewModel.triggerAnnounce() } just Runs
        every { mockViewModel.updateSelectedNodeTypes(any()) } just Runs
        every { mockViewModel.updateShowAudioAnnounces(any()) } just Runs
        every { mockViewModel.updateSelectedInterfaceTypes(any()) } just Runs
        every { mockViewModel.clearAnnounceStatus() } just Runs

        return mockViewModel
    }
}
