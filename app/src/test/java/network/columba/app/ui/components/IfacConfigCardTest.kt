package network.columba.app.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for [IfacConfigCard], the reusable IFAC (network access) input card
 * shared by every interface wizard + the interface-config dialog. Focus is on
 * the contract: label text, state hoisting, and the eye-icon toggle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IfacConfigCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun ifacConfigCard_rendersHeaderAndDefaultDescription() {
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
            )
        }

        composeRule.onNodeWithText("IFAC (Network Access)").assertIsDisplayed()
        // Default description pinned by DEFAULT_IFAC_DESCRIPTION so every
        // wizard gets the same guidance text unless it explicitly overrides.
        composeRule.onNodeWithText(DEFAULT_IFAC_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Network Name").assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase").assertIsDisplayed()
    }

    @Test
    fun ifacConfigCard_rendersCustomDescriptionWhenProvided() {
        val custom = "Only matching credentials can connect."
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
                description = custom,
            )
        }

        composeRule.onNodeWithText(custom).assertIsDisplayed()
    }

    @Test
    fun ifacConfigCard_routesNetworkNameEditsToCallback() {
        var captured = ""
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "",
                passphraseVisible = false,
                onNetworkNameChange = { captured = it },
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
            )
        }

        composeRule.onNodeWithText("Network Name").performTextInput("secret-net")

        assertEquals("secret-net", captured)
    }

    @Test
    fun ifacConfigCard_routesPassphraseEditsToCallback() {
        var captured = ""
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "",
                passphraseVisible = true,
                onNetworkNameChange = {},
                onPassphraseChange = { captured = it },
                onPassphraseVisibilityToggle = {},
            )
        }

        composeRule.onNodeWithText("Passphrase").performTextInput("hunter2")

        assertEquals("hunter2", captured)
    }

    @Test
    fun ifacConfigCard_togglesPassphraseVisibilityViaEyeIcon() {
        var toggled = false
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "hunter2",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = { toggled = true },
            )
        }

        // When masked, the trailing icon offers "Show passphrase" — click it and
        // confirm the visibility-toggle callback fires.
        composeRule.onNodeWithContentDescription("Show passphrase").performClick()

        assertTrue(toggled)
    }

    @Test
    fun ifacConfigCard_eyeIconReflectsVisibilityState() {
        var visible by mutableStateOf(false)
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "hunter2",
                passphraseVisible = visible,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = { visible = !visible },
            )
        }

        // Masked → "Show passphrase"
        composeRule.onNodeWithContentDescription("Show passphrase").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Show passphrase").performClick()

        // Flipped to plaintext → "Hide passphrase"
        composeRule.onNodeWithContentDescription("Hide passphrase").assertIsDisplayed()
    }

    @Test
    fun ifacConfigCard_surfacesNetworkNameError() {
        val errorText = "Network name must be shorter"
        composeRule.setContent {
            IfacConfigCard(
                networkName = "too-long-net-name",
                passphrase = "",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
                networkNameError = errorText,
            )
        }

        composeRule.onNodeWithText(errorText).assertIsDisplayed()
    }

    @Test
    fun ifacConfigCard_surfacesPassphraseError() {
        val errorText = "Passphrase too short"
        composeRule.setContent {
            IfacConfigCard(
                networkName = "",
                passphrase = "x",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
                passphraseError = errorText,
            )
        }

        composeRule.onNodeWithText(errorText).assertIsDisplayed()
    }

    @Test
    fun ifacConfigCard_rendersWhenNoErrorsProvided() {
        composeRule.setContent {
            IfacConfigCard(
                networkName = "net",
                passphrase = "pass",
                passphraseVisible = false,
                onNetworkNameChange = {},
                onPassphraseChange = {},
                onPassphraseVisibilityToggle = {},
            )
        }

        // With null errors both fields are still rendered with their labels,
        // and the passphrase row still exposes the eye-icon trailing affordance.
        composeRule.onNodeWithText("Network Name").assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show passphrase").assertIsDisplayed()
    }
}
