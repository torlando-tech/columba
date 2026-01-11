package com.lxmf.messenger.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive UI tests for WelcomePage composable.
 * Tests all UI elements, callbacks, and privacy bullet points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WelcomePageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Category A: Initial Render Tests ==========

    @Test
    fun welcomePage_displaysAppIcon() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Columba").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysTitle() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Welcome to Columba").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysSubtitle() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("A private messenger that requires:").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysIdentityExplanation() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText(
            "Your identity is generated and stored securely on your device. You control it completely.",
        ).assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysGetStartedButton() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Get Started").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysRestoreFromBackupLink() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Restore from backup").performScrollTo().assertIsDisplayed()
    }

    // ========== Category B: Privacy Bullet Points Tests ==========

    @Test
    fun welcomePage_displaysNoPhoneNumberBullet() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("No phone number").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysNoEmailAddressBullet() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("No email address").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysNoSignUpBullet() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("No sign-up or accounts").assertIsDisplayed()
    }

    @Test
    fun welcomePage_displaysAllPrivacyFeatures() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then - all three privacy features are visible
        composeTestRule.onNodeWithText("No phone number").assertIsDisplayed()
        composeTestRule.onNodeWithText("No email address").assertIsDisplayed()
        composeTestRule.onNodeWithText("No sign-up or accounts").assertIsDisplayed()
    }

    // ========== Category C: Button Click Tests ==========

    @Test
    fun welcomePage_getStartedButton_invokesCallback() {
        // Given
        var getStartedClicked = false
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = { getStartedClicked = true },
                onRestoreFromBackup = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Get Started").performScrollTo().performClick()

        // Then
        assertTrue(getStartedClicked)
    }

    @Test
    fun welcomePage_restoreFromBackupLink_invokesCallback() {
        // Given
        var restoreClicked = false
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = { restoreClicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Restore from backup").performScrollTo().performClick()

        // Then
        assertTrue(restoreClicked)
    }

    @Test
    fun welcomePage_getStartedButton_multipleClicks_callsCallbackEachTime() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = { clickCount++ },
                onRestoreFromBackup = {},
            )
        }

        // When
        repeat(3) {
            composeTestRule.onNodeWithText("Get Started").performScrollTo().performClick()
        }

        // Then
        assertEquals(3, clickCount)
    }

    @Test
    fun welcomePage_restoreFromBackupLink_multipleClicks_callsCallbackEachTime() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = { clickCount++ },
            )
        }

        // When
        repeat(3) {
            composeTestRule.onNodeWithText("Restore from backup").performScrollTo().performClick()
        }

        // Then
        assertEquals(3, clickCount)
    }

    // ========== Category D: Callback Independence Tests ==========

    @Test
    fun welcomePage_getStartedClick_doesNotTriggerRestoreCallback() {
        // Given
        var getStartedClicked = false
        var restoreClicked = false
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = { getStartedClicked = true },
                onRestoreFromBackup = { restoreClicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Get Started").performScrollTo().performClick()

        // Then
        assertTrue(getStartedClicked)
        assertTrue(!restoreClicked)
    }

    @Test
    fun welcomePage_restoreClick_doesNotTriggerGetStartedCallback() {
        // Given
        var getStartedClicked = false
        var restoreClicked = false
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = { getStartedClicked = true },
                onRestoreFromBackup = { restoreClicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithText("Restore from backup").performScrollTo().performClick()

        // Then
        assertTrue(!getStartedClicked)
        assertTrue(restoreClicked)
    }

    // ========== Category E: Complete UI Layout Tests ==========

    @Test
    fun welcomePage_allUIElementsDisplayed() {
        // Given
        composeTestRule.setContent {
            WelcomePage(
                onGetStarted = {},
                onRestoreFromBackup = {},
            )
        }

        // Then - verify all major UI elements are present
        composeTestRule.onNodeWithContentDescription("Columba").assertIsDisplayed()
        composeTestRule.onNodeWithText("Welcome to Columba").assertIsDisplayed()
        composeTestRule.onNodeWithText("A private messenger that requires:").assertIsDisplayed()
        composeTestRule.onNodeWithText("No phone number").assertIsDisplayed()
        composeTestRule.onNodeWithText("No email address").assertIsDisplayed()
        composeTestRule.onNodeWithText("No sign-up or accounts").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your identity is generated and stored securely on your device. You control it completely.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Get Started").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore from backup").performScrollTo().assertIsDisplayed()
    }
}
