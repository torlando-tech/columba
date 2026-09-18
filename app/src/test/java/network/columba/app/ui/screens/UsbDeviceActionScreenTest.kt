package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsbDeviceActionScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `detected Pyxis shows identity and only Pyxis action`() {
        setContent(pyxisVersion = "0.2.2-157-g9d5cc32", isEsp32S3Candidate = true)

        composeRule.onNodeWithText("Pyxis detected").assertIsDisplayed()
        composeRule.onNodeWithText("Firmware 0.2.2-157-g9d5cc32").assertIsDisplayed()
        composeRule.onNodeWithText("Update Pyxis").assertIsDisplayed()
        composeRule.onNodeWithText("Flash RNode Firmware").assertDoesNotExist()
        composeRule.onNodeWithText("Configure RNode").assertDoesNotExist()
    }

    @Test
    fun `unidentified ESP32-S3 offers Pyxis update and clearly separated RNode choices`() {
        setContent(pyxisVersion = null, isEsp32S3Candidate = true)

        composeRule.onNodeWithText("ESP32-S3 device connected").assertIsDisplayed()
        composeRule.onNodeWithText("Update Pyxis").assertIsDisplayed()
        composeRule.onNodeWithText("RNode options").assertExists()
        composeRule.onNodeWithText("Flash RNode Firmware").assertExists()
    }

    @Test
    fun `non ESP device retains RNode actions without claiming Pyxis`() {
        setContent(pyxisVersion = null, isEsp32S3Candidate = false)

        composeRule.onNodeWithText("Update Pyxis").assertDoesNotExist()
        composeRule.onNodeWithText("Flash RNode Firmware").assertExists()
        composeRule.onNodeWithText("Configure RNode").assertExists()
    }

    @Test
    fun `Pyxis action invokes updater navigation`() {
        var invoked = false
        setContent(
            pyxisVersion = "dev",
            isEsp32S3Candidate = true,
            onUpdatePyxis = { invoked = true },
        )

        composeRule.onNodeWithText("Update Pyxis").performClick()

        assertTrue(invoked)
    }

    private fun setContent(
        pyxisVersion: String?,
        isEsp32S3Candidate: Boolean,
        onUpdatePyxis: () -> Unit = {},
    ) {
        composeRule.setContent {
            UsbDeviceActionScreen(
                deviceName = "USB device",
                pyxisVersion = pyxisVersion,
                isEsp32S3Candidate = isEsp32S3Candidate,
                onNavigateBack = {},
                onUpdatePyxis = onUpdatePyxis,
                onFlashFirmware = {},
                onConfigureRNode = {},
                onConfigureTransport = {},
                onDisableTransport = {},
            )
        }
    }
}
