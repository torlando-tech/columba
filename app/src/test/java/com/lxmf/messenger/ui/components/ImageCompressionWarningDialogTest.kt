package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.CompressionWarning
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ImageCompressionWarningDialog.
 *
 * Tests:
 * - Dialog displays all warning information correctly
 * - Confirm and dismiss button callbacks work
 * - Size formatting displays correctly
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageCompressionWarningDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private fun createWarning(
        compressedSizeBytes: Long = 600_000L,
        targetSizeBytes: Long = 128_000L,
        estimatedTransferTime: String = "5m 30s",
        interfaceDescription: String = "BLE Mesh",
    ) = CompressionWarning(
        compressedSizeBytes = compressedSizeBytes,
        targetSizeBytes = targetSizeBytes,
        estimatedTransferTime = estimatedTransferTime,
        interfaceDescription = interfaceDescription,
        pendingImageData = byteArrayOf(0x00),
        pendingImageFormat = "jpg",
        preset = ImageCompressionPreset.MEDIUM,
    )

    // ========== Dialog Title Tests ==========

    @Test
    fun `dialog displays title`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Large Image Warning").assertIsDisplayed()
    }

    // ========== Size Information Tests ==========

    @Test
    fun `dialog displays compressed size in KB`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(compressedSizeBytes = 150_000L),
                onDismiss = {},
                onConfirm = {},
            )
        }

        // 150000 bytes = 146 KB
        composeTestRule.onNodeWithText("This image (146 KB) exceeds the target size (125 KB) for your network.")
            .assertIsDisplayed()
    }

    @Test
    fun `dialog displays compressed size in MB for large files`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning =
                    createWarning(
                        compressedSizeBytes = 2_500_000L,
                        targetSizeBytes = 512_000L,
                    ),
                onDismiss = {},
                onConfirm = {},
            )
        }

        // 2500000 bytes = 2.4 MB
        composeTestRule.onNodeWithText("This image (2.4 MB) exceeds the target size (500 KB) for your network.")
            .assertIsDisplayed()
    }

    @Test
    fun `dialog displays bytes for very small files`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning =
                    createWarning(
                        compressedSizeBytes = 500L,
                        targetSizeBytes = 100L,
                    ),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("This image (500 B) exceeds the target size (100 B) for your network.")
            .assertIsDisplayed()
    }

    // ========== Transfer Details Tests ==========

    @Test
    fun `dialog displays interface description`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(interfaceDescription = "LoRa RNode"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Detected Interface").assertIsDisplayed()
        composeTestRule.onNodeWithText("LoRa RNode").assertIsDisplayed()
    }

    @Test
    fun `dialog displays estimated transfer time`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(estimatedTransferTime = "12m 45s"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Estimated Transfer Time").assertIsDisplayed()
        composeTestRule.onNodeWithText("12m 45s").assertIsDisplayed()
    }

    // ========== Advisory Text Tests ==========

    @Test
    fun `dialog displays advisory text`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Sending large images over slow networks may take a long time " +
                "and could fail if the connection is interrupted.",
        ).assertIsDisplayed()
    }

    // ========== Button Tests ==========

    @Test
    fun `dialog displays Send Anyway button`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Send Anyway").assertIsDisplayed()
    }

    @Test
    fun `dialog displays Cancel button`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `clicking Send Anyway triggers onConfirm callback`() {
        var confirmClicked = false

        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = {},
                onConfirm = { confirmClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Send Anyway").performClick()

        assertTrue(confirmClicked)
    }

    @Test
    fun `clicking Cancel triggers onDismiss callback`() {
        var dismissClicked = false

        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(),
                onDismiss = { dismissClicked = true },
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissClicked)
    }

    // ========== Various Interface Types ==========

    @Test
    fun `dialog displays BLE interface correctly`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(interfaceDescription = "BLE Mesh Network"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("BLE Mesh Network").assertIsDisplayed()
    }

    @Test
    fun `dialog displays TCP interface correctly`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(interfaceDescription = "TCP Client"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("TCP Client").assertIsDisplayed()
    }

    // ========== Transfer Time Variations ==========

    @Test
    fun `dialog displays short transfer time`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(estimatedTransferTime = "30s"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("30s").assertIsDisplayed()
    }

    @Test
    fun `dialog displays long transfer time with hours`() {
        composeTestRule.setContent {
            ImageCompressionWarningDialog(
                warning = createWarning(estimatedTransferTime = "2h 15m"),
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("2h 15m").assertIsDisplayed()
    }
}
