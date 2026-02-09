package com.lxmf.messenger.ui.components

import androidx.compose.runtime.Composable
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.service.ConversationLinkManager

/**
 * Dialog for selecting image quality/compression level before sending.
 *
 * Shows all available presets with:
 * - Recommended preset highlighted based on link state
 * - Estimated transfer times for each option
 * - Path information (hops, rate, etc.)
 *
 * Uses the generic QualitySelectionDialog for consistent UI with
 * other quality selection dialogs (e.g., voice call codec).
 *
 * @param recommendedPreset The preset recommended based on network speed
 * @param linkState The current link state with speed measurements (null if no link)
 * @param transferTimeEstimates Map of preset to estimated transfer time string
 * @param onSelect Called when user selects a preset
 * @param onDismiss Called when dialog is dismissed
 */
@Composable
fun ImageQualitySelectionDialog(
    recommendedPreset: ImageCompressionPreset,
    linkState: ConversationLinkManager.LinkState?,
    transferTimeEstimates: Map<ImageCompressionPreset, String?>,
    onSelect: (ImageCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
    imageCount: Int = 1,
) {
    // Show presets in order: LOW, MEDIUM, HIGH, ORIGINAL (skip AUTO)
    val presets =
        listOf(
            ImageCompressionPreset.LOW,
            ImageCompressionPreset.MEDIUM,
            ImageCompressionPreset.HIGH,
            ImageCompressionPreset.ORIGINAL,
        )

    val options =
        presets.map { preset ->
            QualityOption(
                value = preset,
                displayName = preset.displayName,
                description = preset.description,
            )
        }

    val title =
        if (imageCount > 1) {
            "Send $imageCount Images"
        } else {
            "Choose Image Quality"
        }

    val confirmText =
        if (imageCount > 1) {
            "Send $imageCount Images"
        } else {
            "Send"
        }

    QualitySelectionDialog(
        title = title,
        options = options,
        initialSelection = recommendedPreset,
        recommendedOption = recommendedPreset,
        linkState = linkState,
        transferTimeEstimates = transferTimeEstimates,
        confirmButtonText = confirmText,
        onConfirm = onSelect,
        onDismiss = onDismiss,
    )
}
