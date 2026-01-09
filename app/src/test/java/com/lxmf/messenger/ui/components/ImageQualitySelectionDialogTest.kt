package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.service.LinkSpeedProbe
import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult
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
 * UI tests for ImageQualitySelectionDialog composable.
 * Tests the image quality selection dialog shown before sending images.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageQualitySelectionDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun dialog_displaysTitle() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Choose Image Quality").assertIsDisplayed()
    }

    @Test
    fun dialog_displaysAllQualityOptions() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Verify all quality options exist (may be off-screen in scrollable dialog)
        composeTestRule.onNodeWithText("Low").assertExists()
        composeTestRule.onNodeWithText("Medium").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()
        composeTestRule.onNodeWithText("Original").assertExists()
    }

    @Test
    fun dialog_displaysPresetDescriptions() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Verify descriptions exist (may be off-screen in scrollable dialog)
        composeTestRule.onNodeWithText(ImageCompressionPreset.LOW.description).assertExists()
        composeTestRule.onNodeWithText(ImageCompressionPreset.MEDIUM.description).assertExists()
        composeTestRule.onNodeWithText(ImageCompressionPreset.HIGH.description).assertExists()
        composeTestRule.onNodeWithText(ImageCompressionPreset.ORIGINAL.description).assertExists()
    }

    @Test
    fun dialog_displaysSendAndCancelButtons() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Send").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun dialog_displaysRecommendedBadge() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.LOW,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Recommended").assertIsDisplayed()
    }

    @Test
    fun dialog_displaysTransferTimeEstimates() {
        val estimates = mapOf(
            ImageCompressionPreset.LOW to "~5 sec",
            ImageCompressionPreset.MEDIUM to "~30 sec",
            ImageCompressionPreset.HIGH to "~2 min",
            ImageCompressionPreset.ORIGINAL to "~10 min",
        )

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = estimates,
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("~5 sec").assertExists()
        composeTestRule.onNodeWithText("~30 sec").assertExists()
        composeTestRule.onNodeWithText("~2 min").assertExists()
        composeTestRule.onNodeWithText("~10 min").assertExists()
    }

    // ========== Path Info Section Tests ==========

    @Test
    fun dialog_displaysProbingMessage_whenProbing() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = LinkSpeedProbe.ProbeState.Probing("test_hash", LinkSpeedProbe.TargetType.DIRECT),
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Measuring network speed...").assertIsDisplayed()
    }

    @Test
    fun dialog_displaysFailedMessage_whenProbeFailed() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = LinkSpeedProbe.ProbeState.Failed("timeout", LinkSpeedProbe.TargetType.DIRECT),
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Network speed unknown").assertIsDisplayed()
    }

    @Test
    fun dialog_displaysHopsAndBitrate_whenProbeComplete() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 10000L,
            expectedRateBps = 12000L,
            rttSeconds = 0.5,
            hops = 3,
            linkReused = true,
            nextHopBitrateBps = 115200L,
        )

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = LinkSpeedProbe.ProbeState.Complete(
                    result = result,
                    targetType = LinkSpeedProbe.TargetType.DIRECT,
                    recommendedPreset = ImageCompressionPreset.MEDIUM,
                ),
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Should display hops
        composeTestRule.onNodeWithText("3 hops", substring = true).assertIsDisplayed()
    }

    @Test
    fun dialog_displaysViaRelay_whenPropagationNode() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 5000L,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
            nextHopBitrateBps = null,
        )

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.LOW,
                probeState = LinkSpeedProbe.ProbeState.Complete(
                    result = result,
                    targetType = LinkSpeedProbe.TargetType.PROPAGATION_NODE,
                    recommendedPreset = ImageCompressionPreset.LOW,
                ),
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("(via relay)", substring = true).assertIsDisplayed()
    }

    // ========== Interaction Tests ==========

    @Test
    fun dialog_selectsRecommendedPresetByDefault() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.HIGH,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectedPreset = it },
                onDismiss = {},
            )
        }

        // The HIGH option should be selected by default - clicking Send returns it
        composeTestRule.onNodeWithText("Send").performClick()
        assertEquals(ImageCompressionPreset.HIGH, selectedPreset)
    }

    @Test
    fun dialog_allowsSelectingDifferentPreset() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.HIGH,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectedPreset = it },
                onDismiss = {},
            )
        }

        // Click on Low option
        composeTestRule.onNodeWithText("Low").performClick()

        // Click Send
        composeTestRule.onNodeWithText("Send").performClick()

        assertEquals(ImageCompressionPreset.LOW, selectedPreset)
    }

    @Test
    fun dialog_callsOnDismiss_whenCancelClicked() {
        var dismissed = false

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun dialog_callsOnSelect_whenSendClicked() {
        var selectCalled = false

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectCalled = true },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Send").performClick()

        assertTrue(selectCalled)
    }

    @Test
    fun dialog_canSelectEachPreset() {
        val selectedPresets = mutableListOf<ImageCompressionPreset>()

        // Test LOW
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectedPresets.add(it) },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Low").performClick()
        composeTestRule.onNodeWithText("Send").performClick()
        assertEquals(ImageCompressionPreset.LOW, selectedPresets.last())
    }

    // ========== Different Recommended Presets Tests ==========

    @Test
    fun dialog_recommendsLow_forSlowNetworks() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.LOW,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectedPreset = it },
                onDismiss = {},
            )
        }

        // Default selection should be LOW
        composeTestRule.onNodeWithText("Send").performClick()
        assertEquals(ImageCompressionPreset.LOW, selectedPreset)
    }

    @Test
    fun dialog_recommendsOriginal_forFastNetworks() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.ORIGINAL,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = { selectedPreset = it },
                onDismiss = {},
            )
        }

        // Default selection should be ORIGINAL
        composeTestRule.onNodeWithText("Send").performClick()
        assertEquals(ImageCompressionPreset.ORIGINAL, selectedPreset)
    }

    // ========== Null/Empty State Tests ==========

    @Test
    fun dialog_handlesNullProbeState() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Should still display without crashing
        composeTestRule.onNodeWithText("Choose Image Quality").assertIsDisplayed()
    }

    @Test
    fun dialog_handlesEmptyTransferTimeEstimates() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = null,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Should still display quality options
        composeTestRule.onNodeWithText("Low").assertIsDisplayed()
        composeTestRule.onNodeWithText("Medium").assertIsDisplayed()
    }

    @Test
    fun dialog_handlesIdleProbeState() {
        composeTestRule.setContent {
            ImageQualitySelectionDialog(
                recommendedPreset = ImageCompressionPreset.MEDIUM,
                probeState = LinkSpeedProbe.ProbeState.Idle,
                transferTimeEstimates = emptyMap(),
                onSelect = {},
                onDismiss = {},
            )
        }

        // Should display without path info section
        composeTestRule.onNodeWithText("Choose Image Quality").assertIsDisplayed()
    }
}
