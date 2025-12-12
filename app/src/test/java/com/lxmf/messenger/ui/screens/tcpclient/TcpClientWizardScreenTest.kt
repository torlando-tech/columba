package com.lxmf.messenger.ui.screens.tcpclient

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.TcpClientWizardTestFixtures
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for TcpClientWizardScreen.kt.
 * Tests the main wizard orchestration including navigation and dialogs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TcpClientWizardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ========== TopAppBar Title Tests ==========

    @Test
    fun serverSelectionStep_displaysChooseServerTitle() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then - "Choose Server" appears in TopAppBar and in step header
        composeTestRule.onAllNodesWithText("Choose Server").assertCountEquals(2)
    }

    @Test
    fun reviewConfigureStep_displaysReviewSettingsTitle() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Review Settings").assertIsDisplayed()
    }

    // ========== Back Button Tests ==========

    @Test
    fun backButton_onFirstStep_callsNavigateBack() {
        // Given
        var backClicked = false
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = { backClicked = true },
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // Then
        assertTrue(backClicked)
    }

    @Test
    fun backButton_onSecondStep_callsPreviousStep() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )
        every { mockViewModel.canProceed() } returns true

        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // Then
        verify { mockViewModel.goToPreviousStep() }
    }

    // ========== BottomBar Button Tests ==========

    @Test
    fun serverSelectionStep_showsNextButton() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(
                    selectedServer = TcpClientWizardTestFixtures.testServer,
                ),
            )
        every { mockViewModel.canProceed() } returns true
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun reviewConfigureStep_showsSaveButton() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun nextButton_click_goesToNextStep() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(
                    selectedServer = TcpClientWizardTestFixtures.testServer,
                ),
            )
        every { mockViewModel.canProceed() } returns true
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Next").performClick()

        // Then
        verify { mockViewModel.goToNextStep() }
    }

    @Test
    fun saveButton_click_callsSaveConfiguration() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.reviewConfigureState(),
            )
        every { mockViewModel.canProceed() } returns true

        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("Save").performClick()

        // Then
        verify { mockViewModel.saveConfiguration() }
    }

    @Test
    fun nextButton_disabled_whenCannotProceed() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        // No server selected
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    // ========== Error Dialog Tests ==========

    @Test
    fun saveError_displaysErrorDialog() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.errorState("Connection failed"),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connection failed").assertIsDisplayed()
    }

    @Test
    fun errorDialog_okButton_clearsError() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.errorState("Connection failed"),
            )
        every { mockViewModel.canProceed() } returns true

        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // When
        composeTestRule.onNodeWithText("OK").performClick()

        // Then
        verify { mockViewModel.clearSaveError() }
    }

    // ========== Save Success Tests ==========

    @Test
    fun saveSuccess_callsOnComplete() {
        // Given
        var completeCalled = false
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.successState(),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = { completeCalled = true },
                viewModel = mockViewModel,
            )
        }

        // Then - LaunchedEffect triggers onComplete when saveSuccess is true
        assertTrue(completeCalled)
    }

    // ========== Saving State Tests ==========

    @Test
    fun savingState_buttonShowsLoadingIndicator() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.savingState(),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            TcpClientWizardScreen(
                onNavigateBack = {},
                onComplete = {},
                viewModel = mockViewModel,
            )
        }

        // Then - Button should be disabled during save
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }
}
