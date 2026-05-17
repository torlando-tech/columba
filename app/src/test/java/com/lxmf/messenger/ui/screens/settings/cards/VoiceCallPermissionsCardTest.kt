package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for VoiceCallPermissionsCard.
 * Tests the master "Allow voice calls" toggle (Feature 2) and the
 * banner rendered when the toggle is OFF.
 *
 * The card itself early-returns on SDK < Q, so all tests run under SDK 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceCallPermissionsCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var allowVoiceCallsChanged: Boolean? = null

    @Before
    fun resetCallbackTrackers() {
        allowVoiceCallsChanged = null
    }

    private fun setUpCard(
        isExpanded: Boolean = true,
        allowVoiceCalls: Boolean = true,
    ) {
        composeTestRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                VoiceCallPermissionsCard(
                    isExpanded = isExpanded,
                    onExpandedChange = {},
                    allowVoiceCalls = allowVoiceCalls,
                    onAllowVoiceCallsChange = { allowVoiceCallsChanged = it },
                )
            }
        }
    }

    @Test
    fun voiceCallPermissionsCard_displaysCardTitle() {
        setUpCard()

        composeTestRule.onNodeWithText("Voice Call Permissions").assertIsDisplayed()
    }

    @Test
    fun voiceCallPermissionsCard_displaysDisabledBanner_whenAllowVoiceCallsOff() {
        // Skip on pre-Q where the card returns nothing
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        setUpCard(isExpanded = true, allowVoiceCalls = false)

        composeTestRule.onNodeWithText(
            "Incoming voice calls are currently disabled. Outgoing calls still work.",
        ).assertIsDisplayed()
    }

    @Test
    fun voiceCallPermissionsCard_hidesDisabledBanner_whenAllowVoiceCallsOn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        setUpCard(isExpanded = true, allowVoiceCalls = true)

        // No banner when toggle is ON — assert by negative match: the disabled
        // text is NOT shown.
        composeTestRule
            .onNodeWithText(
                "Incoming voice calls are currently disabled. Outgoing calls still work.",
            ).assertDoesNotExist()
    }
}
