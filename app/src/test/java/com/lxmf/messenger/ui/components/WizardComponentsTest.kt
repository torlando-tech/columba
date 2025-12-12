package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for WizardComponents.kt.
 * Tests CustomSettingsCard and WizardBottomBar composables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WizardComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ========== CustomSettingsCard Tests ==========

    @Test
    fun customSettingsCard_displaysTitle() {
        // Given
        composeTestRule.setContent {
            CustomSettingsCard(
                title = "Custom Settings",
                description = "Enter your own server details",
                isSelected = false,
                onClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Custom Settings").assertIsDisplayed()
    }

    @Test
    fun customSettingsCard_displaysDescription() {
        // Given
        composeTestRule.setContent {
            CustomSettingsCard(
                title = "Custom Settings",
                description = "Enter your own server details",
                isSelected = false,
                onClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Enter your own server details").assertIsDisplayed()
    }

    @Test
    fun customSettingsCard_selected_showsCheckIcon() {
        // Given
        composeTestRule.setContent {
            CustomSettingsCard(
                title = "Custom Settings",
                description = "Enter your own server details",
                isSelected = true,
                onClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Selected").assertIsDisplayed()
    }

    @Test
    fun customSettingsCard_notSelected_doesNotShowCheckIcon() {
        // Given
        composeTestRule.setContent {
            CustomSettingsCard(
                title = "Custom Settings",
                description = "Enter your own server details",
                isSelected = false,
                onClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Selected").assertDoesNotExist()
    }

    @Test
    fun customSettingsCard_onClick_invokesCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            CustomSettingsCard(
                title = "Custom Settings",
                description = "Enter your own server details",
                isSelected = false,
                onClick = { clicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Custom Settings").performClick()

        // Then
        assertTrue(clicked)
    }

    // ========== WizardBottomBar Tests ==========

    @Test
    fun wizardBottomBar_displaysButtonText() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 0,
                totalSteps = 2,
                buttonText = "Next",
                canProceed = true,
                isSaving = false,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun wizardBottomBar_canProceed_buttonEnabled() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 0,
                totalSteps = 2,
                buttonText = "Next",
                canProceed = true,
                isSaving = false,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun wizardBottomBar_cannotProceed_buttonDisabled() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 0,
                totalSteps = 2,
                buttonText = "Next",
                canProceed = false,
                isSaving = false,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun wizardBottomBar_isSaving_buttonDisabled() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 1,
                totalSteps = 2,
                buttonText = "Save",
                canProceed = true,
                isSaving = true,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun wizardBottomBar_isSaving_showsProgressIndicator() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 1,
                totalSteps = 2,
                buttonText = "Save",
                canProceed = true,
                isSaving = true,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun wizardBottomBar_onClick_invokesCallback() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 0,
                totalSteps = 2,
                buttonText = "Next",
                canProceed = true,
                isSaving = false,
                onButtonClick = { clicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Next").performClick()

        // Then
        assertTrue(clicked)
    }

    @Test
    fun wizardBottomBar_displaysSaveButtonText() {
        // Given
        composeTestRule.setContent {
            WizardBottomBar(
                currentStepIndex = 1,
                totalSteps = 2,
                buttonText = "Save",
                canProceed = true,
                isSaving = false,
                onButtonClick = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }
}
