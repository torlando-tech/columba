package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.reticulum.model.NodeType
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    @Test
    fun announceStreamScreen_displaysFilterButton() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Filter node types").assertIsDisplayed()
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
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Announce now").performClick()

        verify { mockViewModel.triggerAnnounce() }
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

    // ========== Filter Dialog Tests ==========

    @Test
    fun filterButton_click_opensFilterDialog() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Click filter button
        composeTestRule.onNodeWithContentDescription("Filter node types").performClick()

        // Filter dialog should appear
        composeTestRule.onNodeWithText("Filter Node Types").assertIsDisplayed()
    }

    @Test
    fun filterDialog_displaysNodeTypeOptions() {
        val mockViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            AnnounceStreamScreen(viewModel = mockViewModel)
        }

        // Open filter dialog
        composeTestRule.onNodeWithContentDescription("Filter node types").performClick()

        // Verify node type options are displayed
        composeTestRule.onNodeWithText("Node").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relay").assertIsDisplayed()
    }

    @Test
    fun nodeTypeFilterDialog_standalone_displaysCorrectly() {
        composeTestRule.setContent {
            NodeTypeFilterDialog(
                selectedTypes = setOf(NodeType.PEER),
                showAudio = true,
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Filter Node Types").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select which node types to display:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Node").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relay").assertIsDisplayed()
    }

    @Test
    fun nodeTypeFilterDialog_displaysAudioOption() {
        composeTestRule.setContent {
            NodeTypeFilterDialog(
                selectedTypes = setOf(NodeType.PEER),
                showAudio = true,
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        // Audio option may require scrolling, so check existence rather than displayed
        composeTestRule.onNodeWithText("Audio").assertExists()
        composeTestRule.onNodeWithText("Show audio call announces").assertExists()
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
        val mockViewModel = mockk<AnnounceStreamViewModel>(relaxed = true)

        every { mockViewModel.announces } returns flowOf(PagingData.empty<Announce>())
        every { mockViewModel.reachableAnnounceCount } returns MutableStateFlow(reachableCount)
        every { mockViewModel.searchQuery } returns MutableStateFlow(searchQuery)
        every { mockViewModel.selectedNodeTypes } returns MutableStateFlow(selectedNodeTypes)
        every { mockViewModel.showAudioAnnounces } returns MutableStateFlow(showAudioAnnounces)
        every { mockViewModel.isAnnouncing } returns MutableStateFlow(isAnnouncing)
        every { mockViewModel.announceSuccess } returns MutableStateFlow(announceSuccess)
        every { mockViewModel.announceError } returns MutableStateFlow(announceError)

        return mockViewModel
    }
}
