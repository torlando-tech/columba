package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.data.model.ImageCompressionPreset
import network.columba.app.service.ConversationLinkManager
import network.columba.app.ui.model.CodecProfile
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for dialog components and their shared building blocks:
 * add-contact confirmation, codec selection, color picker, image quality
 * selection, the generic quality selection dialog, path info, option rows,
 * chips, and contact selection rows.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DialogTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== AddContactConfirmationDialog ==========

    @Test
    fun `add contact confirmation dialog truncated hash`() {
        composeRule.setContent {
            MaterialTheme {
                AddContactConfirmationDialog(
                    destinationHash = FIXED_DESTINATION_HASH,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        TextGoldens.assertGolden("add_contact_confirmation_dialog_truncated_hash", composeRule)
    }

    // ========== CodecSelectionDialog ==========

    @Test
    fun `codec selection dialog default`() {
        composeRule.setContent {
            MaterialTheme {
                CodecSelectionDialog(
                    onDismiss = {},
                    onProfileSelected = {},
                )
            }
        }
        TextGoldens.assertGolden("codec_selection_dialog_default", composeRule)
    }

    @Test
    fun `codec selection dialog filtered profiles`() {
        composeRule.setContent {
            MaterialTheme {
                CodecSelectionDialog(
                    profiles = listOf(
                        CodecProfile.BANDWIDTH_ULTRA_LOW,
                        CodecProfile.QUALITY_HIGH,
                        CodecProfile.LATENCY_LOW,
                    ),
                    recommendedProfile = CodecProfile.BANDWIDTH_ULTRA_LOW,
                    onDismiss = {},
                    onProfileSelected = {},
                )
            }
        }
        TextGoldens.assertGolden("codec_selection_dialog_filtered_profiles", composeRule)
    }

    // ========== ColorPickerDialog ==========

    @Test
    fun `color picker dialog blue initial`() {
        composeRule.setContent {
            MaterialTheme {
                ColorPickerDialog(
                    initialColor = INITIAL_PICKER_COLOR,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("color_picker_dialog_blue_initial", composeRule)
    }

    // ========== ImageQualitySelectionDialog ==========

    @Test
    fun `image quality selection dialog single no link`() {
        composeRule.setContent {
            MaterialTheme {
                ImageQualitySelectionDialog(
                    recommendedPreset = ImageCompressionPreset.MEDIUM,
                    linkState = null,
                    transferTimeEstimates = IMAGE_TRANSFER_ESTIMATES,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("image_quality_selection_dialog_single_no_link", composeRule)
    }

    @Test
    fun `image quality selection dialog multiple with link`() {
        composeRule.setContent {
            MaterialTheme {
                ImageQualitySelectionDialog(
                    recommendedPreset = ImageCompressionPreset.LOW,
                    linkState = ACTIVE_LINK_STATE,
                    transferTimeEstimates = IMAGE_TRANSFER_ESTIMATES,
                    onSelect = {},
                    onDismiss = {},
                    imageCount = 3,
                )
            }
        }
        TextGoldens.assertGolden("image_quality_selection_dialog_multiple_with_link", composeRule)
    }

    // ========== QualitySelectionDialog ==========

    @Test
    fun `quality selection dialog default`() {
        composeRule.setContent {
            MaterialTheme {
                QualitySelectionDialog(
                    title = "Select Quality",
                    subtitle = "Choose a quality level for this transfer",
                    options = QUALITY_OPTIONS,
                    initialSelection = "medium",
                    recommendedOption = "medium",
                    linkState = ACTIVE_LINK_STATE,
                    transferTimeEstimates = mapOf(
                        "low" to "~5 sec",
                        "medium" to "~30 sec",
                        "high" to "~2 min",
                    ),
                    confirmButtonText = "Send",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("quality_selection_dialog_default", composeRule)
    }

    // ========== PathInfoSection ==========

    @Test
    fun `path info section null link state`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = null)
            }
        }
        TextGoldens.assertGolden("path_info_section_null_link_state", composeRule)
    }

    @Test
    fun `path info section active`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = ACTIVE_LINK_STATE)
            }
        }
        TextGoldens.assertGolden("path_info_section_active", composeRule)
    }

    @Test
    fun `path info section probing`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = null, isProbing = true)
            }
        }
        TextGoldens.assertGolden("path_info_section_probing", composeRule)
    }

    @Test
    fun `path info section establishing`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = ConversationLinkManager.LinkState(isActive = false, isEstablishing = true))
            }
        }
        TextGoldens.assertGolden("path_info_section_establishing", composeRule)
    }

    @Test
    fun `path info section error`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = ConversationLinkManager.LinkState(isActive = false, error = "Link establishment timed out"))
            }
        }
        TextGoldens.assertGolden("path_info_section_error", composeRule)
    }

    @Test
    fun `path info section inactive`() {
        composeRule.setContent {
            MaterialTheme {
                PathInfoSection(linkState = ConversationLinkManager.LinkState(isActive = false))
            }
        }
        TextGoldens.assertGolden("path_info_section_inactive", composeRule)
    }

    // ========== QualityOptionRow ==========

    @Test
    fun `quality option row selected`() {
        composeRule.setContent {
            MaterialTheme {
                QualityOptionRow(
                    displayName = "Medium Quality",
                    description = "Opus - Good balance of quality and bandwidth",
                    isSelected = true,
                    isRecommended = true,
                    transferTime = "~30 sec",
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("quality_option_row_selected", composeRule)
    }

    @Test
    fun `quality option row unselected`() {
        composeRule.setContent {
            MaterialTheme {
                QualityOptionRow(
                    displayName = "High Quality",
                    description = "Opus - Higher fidelity audio",
                    isSelected = false,
                    isRecommended = false,
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("quality_option_row_unselected", composeRule)
    }

    // ========== Chips ==========

    @Test
    fun `recommended chip`() {
        composeRule.setContent {
            MaterialTheme {
                RecommendedChip()
            }
        }
        TextGoldens.assertGolden("recommended_chip", composeRule)
    }

    @Test
    fun `experimental chip`() {
        composeRule.setContent {
            MaterialTheme {
                ExperimentalChip()
            }
        }
        TextGoldens.assertGolden("experimental_chip", composeRule)
    }

    // ========== ContactSelectionRow ==========

    @Test
    fun `contact selection row selected`() {
        composeRule.setContent {
            MaterialTheme {
                ContactSelectionRow(
                    displayName = "Alice Mesh",
                    destinationHash = FIXED_DESTINATION_HASH,
                    isSelected = true,
                    onSelectionChanged = {},
                )
            }
        }
        TextGoldens.assertGolden("contact_selection_row_selected", composeRule)
    }

    @Test
    fun `contact selection row unselected`() {
        composeRule.setContent {
            MaterialTheme {
                ContactSelectionRow(
                    displayName = "Alice Mesh",
                    destinationHash = FIXED_DESTINATION_HASH,
                    isSelected = false,
                    onSelectionChanged = {},
                )
            }
        }
        TextGoldens.assertGolden("contact_selection_row_unselected", composeRule)
    }

    private companion object {
        /** Fixed 32-hex-character LXMF destination hash. */
        const val FIXED_DESTINATION_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

        /** Fixed initial color for the color picker (Material blue 500). */
        val INITIAL_PICKER_COLOR = Color(0xFF2196F3)

        /** Active link with hops, throughput and MTU so all path metrics render. */
        val ACTIVE_LINK_STATE = ConversationLinkManager.LinkState(
            isActive = true,
            expectedRateBps = 128_000L,
            hops = 3,
            linkMtu = 512,
            lastActivityTimestamp = TextGoldens.FIXED_NOW_MILLIS,
        )

        /** Fixed per-preset transfer time estimates. */
        val IMAGE_TRANSFER_ESTIMATES = mapOf(
            ImageCompressionPreset.LOW to "~5 sec",
            ImageCompressionPreset.MEDIUM to "~30 sec",
            ImageCompressionPreset.HIGH to "~2 min",
            ImageCompressionPreset.ORIGINAL to "~10 min",
        )

        /** Fixed string-valued options for the generic quality dialog. */
        val QUALITY_OPTIONS = listOf(
            QualityOption(value = "low", displayName = "Low", description = "Smallest file, slowest links"),
            QualityOption(value = "medium", displayName = "Medium", description = "Balanced size and quality"),
            QualityOption(
                value = "high",
                displayName = "High",
                description = "Best quality, needs a fast link",
                isExperimental = true,
            ),
        )
    }
}
