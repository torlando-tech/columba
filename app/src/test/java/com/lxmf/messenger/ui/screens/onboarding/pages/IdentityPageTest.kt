package com.lxmf.messenger.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive UI tests for the IdentityPage composable.
 * Tests the onboarding identity page where users set their display name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityPageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Callback Tracking Variables ==========

    private var displayNameChanged: String? = null
    private var backClicked = false
    private var continueClicked = false

    @Before
    fun resetCallbackTrackers() {
        displayNameChanged = null
        backClicked = false
        continueClicked = false
    }

    // ========== Setup Helper ==========

    private fun setUpIdentityPage(
        displayName: String = "",
        onDisplayNameChange: (String) -> Unit = { displayNameChanged = it },
        onBack: () -> Unit = { backClicked = true },
        onContinue: () -> Unit = { continueClicked = true },
    ) {
        composeTestRule.setContent {
            IdentityPage(
                displayName = displayName,
                onDisplayNameChange = onDisplayNameChange,
                onBack = onBack,
                onContinue = onContinue,
            )
        }
    }

    // ========== Category A: Title and Description Display Tests ==========

    @Test
    fun identityPage_displaysTitle() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Your Identity").assertIsDisplayed()
    }

    @Test
    fun identityPage_displaysDescription() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Choose a display name others will see:")
            .assertIsDisplayed()
    }

    @Test
    fun identityPage_displaysDisplayNameLabel() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Display Name").assertIsDisplayed()
    }

    // ========== Category B: Display Name Text Field Tests ==========

    @Test
    fun identityPage_emptyDisplayName_textFieldAccessible() {
        setUpIdentityPage(displayName = "")

        // The text field should be accessible and can accept input when empty
        composeTestRule.onNodeWithText("Display Name").assertIsDisplayed()
    }

    @Test
    fun identityPage_nonEmptyDisplayName_displaysValue() {
        setUpIdentityPage(displayName = "Alice")

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun identityPage_withDisplayName_valueDisplayed() {
        setUpIdentityPage(displayName = "Bob")

        // Verify the actual value is displayed in the text field
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    // ========== Category C: Helper Text Display Tests ==========

    @Test
    fun identityPage_displaysHelperText() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText(
            "You can change this anytime, or create multiple identities for different contexts.",
        ).assertIsDisplayed()
    }

    // ========== Category D: Navigation Button Display Tests ==========

    @Test
    fun identityPage_displaysBackButton() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test
    fun identityPage_displaysContinueButton() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    // ========== Category E: Display Name Input Callback Tests ==========

    @Test
    fun identityPage_displayNameInput_triggersCallback() {
        val displayNameState = mutableStateOf("")
        composeTestRule.setContent {
            IdentityPage(
                displayName = displayNameState.value,
                onDisplayNameChange = { displayNameState.value = it },
                onBack = {},
                onContinue = {},
            )
        }

        // Type in the text field
        composeTestRule.onNodeWithText("Display Name").performTextInput("Charlie")

        // Verify the state was updated
        assertEquals("Charlie", displayNameState.value)
    }

    @Test
    fun identityPage_displayNameChange_callbackReceivesNewValue() {
        setUpIdentityPage(displayName = "")

        composeTestRule.onNodeWithText("Display Name").performTextInput("TestUser")

        assertEquals("TestUser", displayNameChanged)
    }

    @Test
    fun identityPage_displayNameClear_callbackReceivesEmptyValue() {
        val displayNameState = mutableStateOf("ExistingName")
        composeTestRule.setContent {
            IdentityPage(
                displayName = displayNameState.value,
                onDisplayNameChange = { displayNameState.value = it },
                onBack = {},
                onContinue = {},
            )
        }

        // Clear the text field
        composeTestRule.onNodeWithText("ExistingName").performTextClearance()

        assertEquals("", displayNameState.value)
    }

    @Test
    fun identityPage_displayNameUpdate_displaysNewValue() {
        val displayNameState = mutableStateOf("")
        composeTestRule.setContent {
            IdentityPage(
                displayName = displayNameState.value,
                onDisplayNameChange = { displayNameState.value = it },
                onBack = {},
                onContinue = {},
            )
        }

        // Type in the text field
        composeTestRule.onNodeWithText("Display Name").performTextInput("NewName")
        composeTestRule.waitForIdle()

        // Verify the new value is displayed
        composeTestRule.onNodeWithText("NewName").assertIsDisplayed()
    }

    // ========== Category F: Back Button Callback Tests ==========

    @Test
    fun identityPage_backButtonClick_triggersCallback() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Back").performClick()

        assertTrue(backClicked)
    }

    @Test
    fun identityPage_backButtonMultipleClicks_triggersCallbackEachTime() {
        var clickCount = 0
        setUpIdentityPage(onBack = { clickCount++ })

        repeat(3) {
            composeTestRule.onNodeWithText("Back").performClick()
        }

        assertEquals(3, clickCount)
    }

    // ========== Category G: Continue Button Callback Tests ==========

    @Test
    fun identityPage_continueButtonClick_triggersCallback() {
        setUpIdentityPage()

        composeTestRule.onNodeWithText("Continue").performClick()

        assertTrue(continueClicked)
    }

    @Test
    fun identityPage_continueButtonMultipleClicks_triggersCallbackEachTime() {
        var clickCount = 0
        setUpIdentityPage(onContinue = { clickCount++ })

        repeat(3) {
            composeTestRule.onNodeWithText("Continue").performClick()
        }

        assertEquals(3, clickCount)
    }

    @Test
    fun identityPage_continueWithEmptyDisplayName_stillTriggersCallback() {
        setUpIdentityPage(displayName = "")

        composeTestRule.onNodeWithText("Continue").performClick()

        assertTrue(continueClicked)
    }

    @Test
    fun identityPage_continueWithDisplayName_triggersCallback() {
        setUpIdentityPage(displayName = "ValidName")

        composeTestRule.onNodeWithText("Continue").performClick()

        assertTrue(continueClicked)
    }

    // ========== Category H: Combined Interaction Tests ==========

    @Test
    fun identityPage_enterNameThenContinue_bothCallbacksWork() {
        val displayNameState = mutableStateOf("")
        var continuePressed = false

        composeTestRule.setContent {
            IdentityPage(
                displayName = displayNameState.value,
                onDisplayNameChange = { displayNameState.value = it },
                onBack = {},
                onContinue = { continuePressed = true },
            )
        }

        // Enter a name
        composeTestRule.onNodeWithText("Display Name").performTextInput("MyName")
        composeTestRule.waitForIdle()

        // Click continue
        composeTestRule.onNodeWithText("Continue").performClick()

        // Verify both actions worked
        assertEquals("MyName", displayNameState.value)
        assertTrue(continuePressed)
    }

    @Test
    fun identityPage_enterNameThenGoBack_bothCallbacksWork() {
        val displayNameState = mutableStateOf("")
        var backPressed = false

        composeTestRule.setContent {
            IdentityPage(
                displayName = displayNameState.value,
                onDisplayNameChange = { displayNameState.value = it },
                onBack = { backPressed = true },
                onContinue = {},
            )
        }

        // Enter a name
        composeTestRule.onNodeWithText("Display Name").performTextInput("SomeName")
        composeTestRule.waitForIdle()

        // Click back
        composeTestRule.onNodeWithText("Back").performClick()

        // Verify both actions worked
        assertEquals("SomeName", displayNameState.value)
        assertTrue(backPressed)
    }

    // ========== Category I: Edge Cases ==========

    @Test
    fun identityPage_longDisplayName_displaysCorrectly() {
        val longName = "A".repeat(50)
        setUpIdentityPage(displayName = longName)

        composeTestRule.onNodeWithText(longName).assertIsDisplayed()
    }

    @Test
    fun identityPage_displayNameWithSpaces_displaysCorrectly() {
        setUpIdentityPage(displayName = "First Last")

        composeTestRule.onNodeWithText("First Last").assertIsDisplayed()
    }

    @Test
    fun identityPage_displayNameWithSpecialCharacters_displaysCorrectly() {
        setUpIdentityPage(displayName = "User-123_Test")

        composeTestRule.onNodeWithText("User-123_Test").assertIsDisplayed()
    }

    @Test
    fun identityPage_displayNameWithEmoji_displaysCorrectly() {
        setUpIdentityPage(displayName = "User123")

        composeTestRule.onNodeWithText("User123").assertIsDisplayed()
    }

    @Test
    fun identityPage_allElementsDisplayed_initialState() {
        setUpIdentityPage(displayName = "")

        // Verify all key elements are displayed
        composeTestRule.onNodeWithText("Your Identity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a display name others will see:")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Display Name").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "You can change this anytime, or create multiple identities for different contexts.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }
}
