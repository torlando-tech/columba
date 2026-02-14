package com.lxmf.messenger.ui.components

import androidx.compose.runtime.Composable
import com.lxmf.messenger.service.ConversationLinkManager
import com.lxmf.messenger.ui.model.CodecProfile

/**
 * Dialog for selecting an audio codec profile before initiating a voice call.
 *
 * Displays all available codec profiles with their descriptions,
 * allowing the user to choose based on their network conditions.
 * Uses the generic QualitySelectionDialog for consistent UI with
 * other quality selection dialogs (e.g., image quality).
 *
 * @param recommendedProfile The recommended profile based on link speed (default: QUALITY_MEDIUM)
 * @param linkState Current link state for displaying path info (null to hide)
 * @param onDismiss Called when the dialog is dismissed without selection
 * @param onProfileSelected Called with the selected profile when user confirms
 */
@Composable
fun CodecSelectionDialog(
    recommendedProfile: CodecProfile = CodecProfile.DEFAULT,
    linkState: ConversationLinkManager.LinkState? = null,
    onDismiss: () -> Unit,
    onProfileSelected: (CodecProfile) -> Unit,
) {
    val options =
        CodecProfile.entries.map { profile ->
            QualityOption(
                value = profile,
                displayName = profile.displayName,
                description = profile.description,
                isExperimental = profile.isExperimental,
            )
        }

    QualitySelectionDialog(
        title = "Select Call Quality",
        subtitle = "Choose a codec profile based on your connection speed",
        options = options,
        initialSelection = recommendedProfile,
        recommendedOption = recommendedProfile,
        linkState = linkState,
        confirmButtonText = "Call",
        onConfirm = onProfileSelected,
        onDismiss = onDismiss,
    )
}
