package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import network.columba.app.reticulum.model.NodeType
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

        composeTestRule.onNodeWithText("Local").assertIsDisplayed()
        composeTestRule.onNodeWithText("TCP").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth").assertIsDisplayed()
        composeTestRule.onNodeWithText("RNode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Other").assertIsDisplayed()
    }

    @Test
    fun filterChips_tappingAspect_callsViewModel() {
        val mockViewModel = createMockAnnounceStreamViewModel()
        var capturedTypes: Set<NodeType>? = null
        every { mockViewModel.updateSelectedNodeTypes(any()) } answers { capturedTypes = firstArg() }

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Sites").performClick()

        verify { mockViewModel.updateSelectedNodeTypes(match { it.contains(NodeType.NODE) }) }
        assertTrue(
            "updateSelectedNodeTypes should have been called with a set containing NODE",
            capturedTypes?.contains(NodeType.NODE) == true,
        )
    }

    @Test
    fun filterChips_deselectingLastAspect_snapsBackToAll() {
        // Single aspect selected: PEER only, audio off → activeAspects = {PEER}.
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

        // Tapping the only active chip would leave no aspect selected;
        // the component should snap to ALL (node types + audio) instead of producing an empty filter.
        composeTestRule.onNodeWithText("Peers").performClick()

        assertEquals(
            "Deselecting the last active aspect should snap to all node types, not empty",
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
    fun filterChips_tappingInterface_callsViewModel() {
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
        assertTrue(
            "updateSelectedInterfaceTypes should have been called with TCP_CLIENT",
            capturedInterfaces == setOf(InterfaceType.TCP_CLIENT),
        )
    }

    // ========== Test Helpers ==========

    private fun createMockAnnounceStreamViewModel(
        reachableCount: Int = 0,
        isAnnouncing: Boolean = false,
        announceSuccess: Boolean = false,
        announceError: String? = null,
        selectedNodeTypes: Set<NodeType> = setOf(NodeType.PEER),
        showAudioAnnounces: Boolean = true,
        searchQuery: String = "",
    ): AnnounceStreamViewModel {
        val mockViewModel = mockk<AnnounceStreamViewModel>()

        // Stub StateFlow properties
        every { mockViewModel.announces } returns flowOf(PagingData.empty<Announce>())
        every { mockViewModel.reachableAnnounceCount } returns MutableStateFlow(reachableCount)
        every { mockViewModel.searchQuery } returns MutableStateFlow(searchQuery)
        every { mockViewModel.selectedNodeTypes } returns MutableStateFlow(selectedNodeTypes)
        every { mockViewModel.showAudioAnnounces } returns MutableStateFlow(showAudioAnnounces)
        every { mockViewModel.selectedInterfaceTypes } returns MutableStateFlow(emptySet<InterfaceType>())
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
