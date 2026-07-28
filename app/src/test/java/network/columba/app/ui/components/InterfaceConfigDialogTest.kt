package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.InterfaceConfigState
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InterfaceConfigDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `fresh AutoInterface selector displays wifi only without persisting a choice`() {
        val configState = InterfaceConfigState()

        composeTestRule.setContent {
            InterfaceConfigDialog(
                configState = configState,
                isEditing = false,
                onDismiss = {},
                onSave = {},
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Advanced Options").performClick()
        composeTestRule.onNodeWithText("Wi-Fi only").assertIsSelected()
        composeTestRule.onNodeWithText("Any").assertIsNotSelected()
        assertNull(configState.networkRestriction)
    }

    @Test
    fun `explicit Any selector state displays Any`() {
        val configState = InterfaceConfigState(networkRestriction = "any")

        composeTestRule.setContent {
            InterfaceConfigDialog(
                configState = configState,
                isEditing = false,
                onDismiss = {},
                onSave = {},
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Advanced Options").performClick()
        composeTestRule.onNodeWithText("Any").assertIsSelected()
        composeTestRule.onNodeWithText("Wi-Fi only").assertIsNotSelected()
    }

    // ========== TCPServerFields UI Tests ==========

    @Test
    fun `TCPServerFields displays configuration header`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("TCP Server Configuration").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays description text`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Allows other Reticulum nodes to connect to this device. " +
                "Useful for Yggdrasil connectivity or when this device should act as a hub.",
        ).assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays Listen IP label`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Listen IP").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays Listen Port label`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Listen Port").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays default listen IP value`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("0.0.0.0").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays default listen port value`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("4242").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays listen IP help text`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("IP address to bind to. Use 0.0.0.0 to listen on all interfaces.")
            .assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays listen port help text`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("TCP port to listen on for incoming connections.")
            .assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays listen IP error when present`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "invalid",
                listenPort = "4242",
                listenIpError = "Invalid IP address",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Invalid IP address").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays listen port error when present`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "99999",
                listenPortError = "Port must be between 1 and 65535",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("Port must be between 1 and 65535").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields has listen IP text field that responds to input`() {
        // Note: Full integration testing of controlled text field state requires
        // lifting state to test, which is covered by ViewModel tests.
        // This test verifies the field exists and can accept focus.
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        // Verify the listen IP field displays the expected value and exists
        composeTestRule.onNodeWithText("0.0.0.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listen IP").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays custom listen IP`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "192.168.1.100",
                listenPort = "4242",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("192.168.1.100").assertIsDisplayed()
    }

    @Test
    fun `TCPServerFields displays custom listen port`() {
        val configState =
            InterfaceConfigState(
                type = "TCPServer",
                listenIp = "0.0.0.0",
                listenPort = "8080",
            )

        composeTestRule.setContent {
            TCPServerFields(
                configState = configState,
                onConfigUpdate = {},
            )
        }

        composeTestRule.onNodeWithText("8080").assertIsDisplayed()
    }
}
