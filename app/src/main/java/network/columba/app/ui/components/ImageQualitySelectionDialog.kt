package network.columba.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import network.columba.app.R
import network.columba.app.data.model.ImageCompressionPreset
import network.columba.app.service.ConversationLinkManager

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
                displayName = stringResource(preset.displayNameRes),
                description = stringResource(preset.descriptionRes),
            )
        }

    val title =
        if (imageCount > 1) {
            pluralStringResource(R.plurals.image_quality_send_images, imageCount, imageCount)
        } else {
            stringResource(R.string.image_quality_choose_title)
        }

    val confirmText =
        if (imageCount > 1) {
            pluralStringResource(R.plurals.image_quality_send_images, imageCount, imageCount)
        } else {
            stringResource(R.string.image_quality_send)
        }

    // For multi-image shares, append " each" to transfer time estimates so the user
    // understands the time shown is per-image, not total.
    val displayEstimates =
        if (imageCount > 1) {
            transferTimeEstimates.mapValues { (_, time) -> time?.let { stringResource(R.string.image_quality_each_suffix, it) } }
        } else {
            transferTimeEstimates
        }

    QualitySelectionDialog(
        title = title,
        options = options,
        initialSelection = recommendedPreset,
        recommendedOption = recommendedPreset,
        linkState = linkState,
        transferTimeEstimates = displayEstimates,
        confirmButtonText = confirmText,
        onConfirm = onSelect,
        onDismiss = onDismiss,
    )
}
