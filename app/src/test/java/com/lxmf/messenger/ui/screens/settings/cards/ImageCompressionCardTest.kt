package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ImageCompressionCard.
 *
 * Tests:
 * - UI display for all presets
 * - Preset selection callbacks
 * - Auto mode detected preset display
 * - Slow interface warning visibility
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageCompressionCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== UI Display Tests ==========

    @Test
    fun `card displays title`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Image Compression").assertIsDisplayed()
    }

    @Test
    fun `card displays description text`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Select compression level for image attachments. " +
                "Auto mode detects your network type and selects the optimal preset.",
        ).assertIsDisplayed()
    }

    @Test
    fun `card displays all preset chips`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.HIGH,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Auto").assertIsDisplayed()
        composeTestRule.onNodeWithText("Low").assertIsDisplayed()
        composeTestRule.onNodeWithText("Medium").assertIsDisplayed()
        composeTestRule.onNodeWithText("High").assertIsDisplayed()
        composeTestRule.onNodeWithText("Original").assertIsDisplayed()
    }

    @Test
    fun `LOW preset shows correct description`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.LOW,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("32KB max - optimized for LoRa and BLE").assertIsDisplayed()
    }

    @Test
    fun `MEDIUM preset shows correct description`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.MEDIUM,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("128KB max - balanced for mixed networks").assertIsDisplayed()
    }

    @Test
    fun `HIGH preset shows correct description`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.HIGH,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("512KB max - good quality for general use").assertIsDisplayed()
    }

    @Test
    fun `ORIGINAL preset shows correct description`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.ORIGINAL,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("25MB max - minimal compression for fast networks").assertIsDisplayed()
    }

    // ========== Auto Mode Tests ==========

    @Test
    fun `AUTO preset with detected LOW shows Auto Low chip`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = ImageCompressionPreset.LOW,
                hasSlowInterface = true,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Auto (Low)").assertIsDisplayed()
    }

    @Test
    fun `AUTO preset with detected MEDIUM shows Auto Medium chip`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = ImageCompressionPreset.MEDIUM,
                hasSlowInterface = true,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Auto (Medium)").assertIsDisplayed()
    }

    @Test
    fun `AUTO preset with detected HIGH shows Auto High chip`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = ImageCompressionPreset.HIGH,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Auto (High)").assertIsDisplayed()
    }

    @Test
    fun `AUTO preset shows detected preset description`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = ImageCompressionPreset.LOW,
                hasSlowInterface = true,
                onPresetChange = {},
            )
        }

        // Should show LOW preset description, not AUTO
        composeTestRule.onNodeWithText("32KB max - optimized for LoRa and BLE").assertIsDisplayed()
    }

    // ========== Slow Interface Warning Tests ==========

    @Test
    fun `warning shown when ORIGINAL selected with slow interface`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.ORIGINAL,
                detectedPreset = null,
                hasSlowInterface = true,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Slow interfaces (LoRa/BLE) are enabled. " +
                "Sending large images may take a very long time or fail.",
        ).assertIsDisplayed()
    }

    @Test
    fun `warning not shown when ORIGINAL selected without slow interface`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.ORIGINAL,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Slow interfaces (LoRa/BLE) are enabled. " +
                "Sending large images may take a very long time or fail.",
        ).assertDoesNotExist()
    }

    @Test
    fun `warning not shown for LOW preset with slow interface`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.LOW,
                detectedPreset = null,
                hasSlowInterface = true,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Slow interfaces (LoRa/BLE) are enabled. " +
                "Sending large images may take a very long time or fail.",
        ).assertDoesNotExist()
    }

    // ========== Click Callback Tests ==========

    @Test
    fun `clicking Low chip triggers callback with LOW preset`() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = { selectedPreset = it },
            )
        }

        composeTestRule.onNodeWithText("Low").performClick()

        assertEquals(ImageCompressionPreset.LOW, selectedPreset)
    }

    @Test
    fun `clicking Medium chip triggers callback with MEDIUM preset`() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = { selectedPreset = it },
            )
        }

        composeTestRule.onNodeWithText("Medium").performClick()

        assertEquals(ImageCompressionPreset.MEDIUM, selectedPreset)
    }

    @Test
    fun `clicking High chip triggers callback with HIGH preset`() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = { selectedPreset = it },
            )
        }

        composeTestRule.onNodeWithText("High").performClick()

        assertEquals(ImageCompressionPreset.HIGH, selectedPreset)
    }

    @Test
    fun `clicking Original chip triggers callback with ORIGINAL preset`() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.AUTO,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = { selectedPreset = it },
            )
        }

        composeTestRule.onNodeWithText("Original").performClick()

        assertEquals(ImageCompressionPreset.ORIGINAL, selectedPreset)
    }

    @Test
    fun `clicking Auto chip triggers callback with AUTO preset`() {
        var selectedPreset: ImageCompressionPreset? = null

        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.HIGH,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = { selectedPreset = it },
            )
        }

        composeTestRule.onNodeWithText("Auto").performClick()

        assertEquals(ImageCompressionPreset.AUTO, selectedPreset)
    }

    // ========== Dimension and Size Display Tests ==========

    @Test
    fun `LOW preset shows max dimensions`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.LOW,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Max: 320px, 32 KB").assertIsDisplayed()
    }

    @Test
    fun `MEDIUM preset shows max dimensions`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.MEDIUM,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Max: 800px, 128 KB").assertIsDisplayed()
    }

    @Test
    fun `HIGH preset shows max dimensions`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.HIGH,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Max: 2048px, 512 KB").assertIsDisplayed()
    }

    @Test
    fun `ORIGINAL preset shows unlimited dimensions`() {
        composeTestRule.setContent {
            ImageCompressionCard(
                selectedPreset = ImageCompressionPreset.ORIGINAL,
                detectedPreset = null,
                hasSlowInterface = false,
                onPresetChange = {},
            )
        }

        composeTestRule.onNodeWithText("Max: unlimitedpx, 250 MB").assertIsDisplayed()
    }
}
