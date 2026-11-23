package network.columba.app.ui.components

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.rns.api.model.CallAudioRoute
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for the in-call audio output picker ([CallAudioRouteSheetContent]).
 *
 * Covers what the call screen relies on: builtin devices are labelled from
 * resources, connected headsets by their product name, the platform's current
 * route is the selected row, and tapping a row reports that device id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallAudioRouteSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    private fun resText(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

    private val earpiece =
        CallAudioRoute(
            deviceId = 1,
            type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            name = "Earpiece",
            isActive = false,
        )
    private val speaker =
        CallAudioRoute(
            deviceId = 2,
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            name = "Speaker",
            isActive = true,
        )
    private val headset =
        CallAudioRoute(
            deviceId = 42,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            name = "Galaxy Buds Pro",
            isActive = false,
        )

    @Test
    fun `picker lists builtin devices by resource label and headsets by product name`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteSheetContent(routes = listOf(earpiece, speaker, headset), onRouteSelected = {})
            }
        }

        composeTestRule.onNodeWithText(resText(R.string.call_audio_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(resText(R.string.call_earpiece)).assertExists()
        composeTestRule.onNodeWithText(resText(R.string.call_speaker)).assertExists()
        composeTestRule.onNodeWithText("Galaxy Buds Pro").assertExists()
    }

    @Test
    fun `picker marks the platform active route as selected`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteSheetContent(routes = listOf(earpiece, speaker, headset), onRouteSelected = {})
            }
        }

        composeTestRule.onNodeWithTag("callAudioRoute-2").assertIsSelected()
        composeTestRule.onNodeWithTag("callAudioRoute-1").assertIsNotSelected()
        composeTestRule.onNodeWithTag("callAudioRoute-42").assertIsNotSelected()
    }

    @Test
    fun `tapping a row reports its device id`() {
        var selectedDeviceId: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteSheetContent(
                    routes = listOf(earpiece, speaker, headset),
                    onRouteSelected = { selectedDeviceId = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("callAudioRoute-42").performClick()

        assertEquals(42, selectedDeviceId)
    }

    @Test
    fun `call control label uses the device type rather than a long product name`() {
        composeTestRule.setContent {
            MaterialTheme {
                Text(callAudioRouteShortLabel(headset))
            }
        }

        composeTestRule.onNodeWithText(resText(R.string.call_audio_bluetooth)).assertExists()
        composeTestRule.onNodeWithText("Galaxy Buds Pro").assertDoesNotExist()
    }

    @Test
    fun `blank product names fall back to the generic device label`() {
        val unnamed =
            CallAudioRoute(
                deviceId = 7,
                type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                name = "",
                isActive = false,
            )
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteSheetContent(routes = listOf(unnamed), onRouteSelected = {})
            }
        }

        composeTestRule.onNodeWithText(resText(R.string.call_audio_unknown_device)).assertExists()
    }
}
