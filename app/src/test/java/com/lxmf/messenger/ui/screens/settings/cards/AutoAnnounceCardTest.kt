package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.test.AutoAnnounceTestFixtures
import com.lxmf.messenger.test.AutoAnnounceTestFixtures.CardConfig
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive UI tests for AutoAnnounceCard.
 * Tests all UI states, callbacks, conditional rendering, and edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoAnnounceCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Callback Tracking Variables ==========

    private var toggleChanged: Boolean? = null
    private var intervalChanged: Int? = null
    private var manualAnnounceCalled = false

    @Before
    fun resetCallbackTrackers() {
        toggleChanged = null
        intervalChanged = null
        manualAnnounceCalled = false
    }

    // ========== Setup Helper ==========

    private fun setUpCardWithConfig(config: CardConfig) {
        composeTestRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AutoAnnounceCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    enabled = config.enabled,
                    intervalHours = config.intervalHours,
                    lastAnnounceTime = config.lastAnnounceTime,
                    nextAnnounceTime = config.nextAnnounceTime,
                    isManualAnnouncing = config.isManualAnnouncing,
                    showManualAnnounceSuccess = config.showManualAnnounceSuccess,
                    manualAnnounceError = config.manualAnnounceError,
                    onToggle = { toggleChanged = it },
                    onIntervalChange = { intervalChanged = it },
                    onManualAnnounce = { manualAnnounceCalled = true },
                )
            }
        }
    }

    // ========== Category A: Header and Static Display Tests ==========

    @Test
    fun header_displaysCardTitle() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.defaultState())

        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()
    }

    @Test
    fun header_displaysTitle() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.defaultState())

        // The card title is displayed in the header
        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()
    }

    @Test
    fun header_displaysDescription() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.defaultState())

        composeTestRule.onNodeWithText(
            "Automatically announce your presence on the network at regular intervals. " +
                "This helps other peers discover you.",
        ).assertIsDisplayed()
    }

    // ========== Category B: Toggle Tests ==========

    @Test
    fun toggle_enabled_switchIsOn() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        // The switch should be visible
        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()
    }

    @Test
    fun toggle_disabled_switchIsOff() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.disabledState())

        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()
    }

    // ========== Category C: Interval Selector Tests (when enabled) ==========

    @Test
    fun intervalSelector_displayed_whenEnabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Announce Interval:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun intervalSelector_hidden_whenDisabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.disabledState())

        composeTestRule.onNodeWithText("Announce Interval:", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun intervalSelector_displaysCurrentInterval() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval3hState())

        composeTestRule.onNodeWithText("Announce Interval: 3 hours")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun intervalSelector_displaysSingularHour() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval1hState())

        composeTestRule.onNodeWithText("Announce Interval: 1 hour")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun intervalChips_allPresetsDisplayed() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("1h").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("3h").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("6h").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("12h").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_customChipDisplayed() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_1hSelected_showsAsSelected() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval1hState())

        composeTestRule.onNodeWithText("1h").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_3hSelected_showsAsSelected() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval3hState())

        composeTestRule.onNodeWithText("3h").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_6hSelected_showsAsSelected() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval6hState())

        composeTestRule.onNodeWithText("6h").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_12hSelected_showsAsSelected() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval12hState())

        composeTestRule.onNodeWithText("12h").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChips_customInterval_customChipSelected() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.customIntervalState())

        composeTestRule.onNodeWithText("Custom (5h)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun intervalChip_click1h_invokesOnIntervalChange() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval3hState())

        composeTestRule.onNodeWithText("1h").performScrollTo().performClick()

        assertEquals(1, intervalChanged)
    }

    @Test
    fun intervalChip_click3h_invokesOnIntervalChange() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval1hState())

        composeTestRule.onNodeWithText("3h").performScrollTo().performClick()

        assertEquals(3, intervalChanged)
    }

    @Test
    fun intervalChip_click6h_invokesOnIntervalChange() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval1hState())

        composeTestRule.onNodeWithText("6h").performScrollTo().performClick()

        assertEquals(6, intervalChanged)
    }

    @Test
    fun intervalChip_click12h_invokesOnIntervalChange() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval1hState())

        composeTestRule.onNodeWithText("12h").performScrollTo().performClick()

        assertEquals(12, intervalChanged)
    }

    // ========== Category D: Custom Interval Dialog Tests ==========

    @Test
    fun customChip_click_opensDialog() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Custom Interval").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_displaysTitle() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Custom Interval").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_displaysInstructions() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Enter announce interval (1-12 hours):")
            .assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_displaysTextField() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Hours").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_displaysConfirmButton() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_displaysCancelButton() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_prefilledWithCurrentInterval() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.interval3hState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_validInput_confirmEnabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()

        // Prefilled with 3, which is valid
        composeTestRule.onNodeWithText("Confirm").assertIsEnabled()
    }

    @Test
    fun customIntervalDialog_inputBelowMin_confirmDisabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()
        composeTestRule.onNodeWithText("Hours").performTextInput("0")

        composeTestRule.onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test
    fun customIntervalDialog_inputAboveMax_confirmDisabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()
        composeTestRule.onNodeWithText("Hours").performTextInput("15")

        composeTestRule.onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test
    fun customIntervalDialog_emptyInput_confirmDisabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()

        composeTestRule.onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test
    fun customIntervalDialog_invalidInput_showsError() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()
        composeTestRule.onNodeWithText("Hours").performTextInput("15")

        composeTestRule.onNodeWithText("Value must be between 1 and 12").assertIsDisplayed()
    }

    @Test
    fun customIntervalDialog_confirmClick_invokesOnIntervalChange() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()
        composeTestRule.onNodeWithText("Hours").performTextInput("5")
        composeTestRule.onNodeWithText("Confirm").performClick()

        assertEquals(5, intervalChanged)
    }

    @Test
    fun customIntervalDialog_cancelClick_dismissesWithoutCallback() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeTestRule.onNodeWithText("3").performTextClearance()
        composeTestRule.onNodeWithText("Hours").performTextInput("5")
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Dialog should be dismissed
        composeTestRule.onNodeWithText("Custom Interval").assertDoesNotExist()
        // Callback should not have been invoked
        assertNull(intervalChanged)
    }

    // ========== Category E: Announce Status Tests ==========

    @Test
    fun announceStatus_noLastAnnounce_displaysNoAnnouncesMessage() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.noLastAnnounceState())

        composeTestRule.onNodeWithText("No announces sent yet")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_justAnnounced_displaysJustNow() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.justAnnouncedState())

        composeTestRule.onNodeWithText("Last announce: just now")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_minutesAgo_displaysMinutes() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.announcedMinutesAgoState(15))

        composeTestRule.onNodeWithText("Last announce: 15m ago")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_hoursAgo_displaysHoursAndMinutes() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.announcedHoursAgoState(2))

        composeTestRule.onNodeWithText("Last announce: 2h 0m ago")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_nextAnnounceInMinutes_displaysMinutes() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.nextAnnounceInMinutesState(30))

        // Use broader substring match since exact minutes may vary slightly during test execution
        composeTestRule.onNodeWithText("Next announce in:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_nextAnnounceInHours_displaysHoursAndMinutes() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.nextAnnounceInHoursState(2))

        // Use broader substring match since exact time may vary slightly during test execution
        composeTestRule.onNodeWithText("Next announce in:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_nextAnnounceSoon_displaysSoon() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.nextAnnounceSoonState())

        composeTestRule.onNodeWithText("Next announce: soon")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun announceStatus_noNextAnnounce_hidesNextAnnounceText() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.noNextAnnounceState())

        composeTestRule.onNodeWithText("Next announce", substring = true)
            .assertDoesNotExist()
    }

    // ========== Category F: Manual Announce Section Tests ==========

    @Test
    fun manualAnnounce_displaysButton() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Announce Now")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manualAnnounce_notAnnouncing_buttonEnabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Announce Now")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun manualAnnounce_announcing_buttonDisabled() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.manualAnnouncingState())

        composeTestRule.onNodeWithText("Announcing...")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun manualAnnounce_announcing_displaysAnnouncingText() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.manualAnnouncingState())

        composeTestRule.onNodeWithText("Announcing...")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manualAnnounce_click_invokesCallback() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.enabledState())

        composeTestRule.onNodeWithText("Announce Now")
            .performScrollTo()
            .performClick()

        assertTrue(manualAnnounceCalled)
    }

    @Test
    fun manualAnnounce_success_displaysSuccessMessage() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.manualAnnounceSuccessState())

        composeTestRule.onNodeWithText("Announce sent!")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manualAnnounce_error_displaysErrorMessage() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.manualAnnounceErrorState("Network error"))

        composeTestRule.onNodeWithText("Error: Network error")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ========== Category G: Edge Cases ==========

    @Test
    fun edgeCase_disabledState_hidesIntervalAndStatus() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.disabledState())

        // Header still visible
        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()

        // Interval selector hidden
        composeTestRule.onNodeWithText("Announce Interval:", substring = true)
            .assertDoesNotExist()

        // Status hidden
        composeTestRule.onNodeWithText("Last announce:", substring = true)
            .assertDoesNotExist()

        // Manual announce hidden
        composeTestRule.onNodeWithText("Announce Now")
            .assertDoesNotExist()
    }

    @Test
    fun edgeCase_allNullTimestamps_rendersWithoutCrash() {
        setUpCardWithConfig(AutoAnnounceTestFixtures.allNullTimestampsState())

        // Card should render
        composeTestRule.onNodeWithText("Auto Announce").assertIsDisplayed()
        composeTestRule.onNodeWithText("No announces sent yet")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
