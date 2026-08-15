package network.columba.app.ui.components

import androidx.compose.runtime.Composable
import network.columba.app.service.ConversationLinkManager
import network.columba.app.ui.model.CodecProfile

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
 * @param isProbing True while a link probe is in flight; the dialog renders
 *   immediately on open with a spinner inside the PathInfoSection, then the
 *   spinner is replaced with the probe result when the suspend completes.
 * @param onDismiss Called when the dialog is dismissed without selection
 * @param onProfileSelected Called with the selected profile when user confirms
 */
@Composable
fun CodecSelectionDialog(
    title: String = "Select Call Quality",
    subtitle: String = "Choose a codec profile based on your connection speed",
    profiles: List<CodecProfile> = CodecProfile.entries,
    initialProfile: CodecProfile? = null,
    recommendedProfile: CodecProfile = CodecProfile.DEFAULT,
    linkState: ConversationLinkManager.LinkState? = null,
    isProbing: Boolean = false,
    confirmButtonText: String = "Call",
    onDismiss: () -> Unit,
    onProfileSelected: (CodecProfile) -> Unit,
) {
    val options =
        profiles.map { profile ->
            QualityOption(
                value = profile,
                displayName = profile.displayName,
                description = profile.description,
                isExperimental = profile.isExperimental,
            )
        }

    QualitySelectionDialog(
        title = title,
        subtitle = subtitle,
        options = options,
        initialSelection = (initialProfile ?: recommendedProfile).takeIf(profiles::contains) ?: profiles.first(),
        recommendedOption = recommendedProfile,
        linkState = linkState,
        isProbing = isProbing,
        confirmButtonText = confirmButtonText,
        onConfirm = onProfileSelected,
        onDismiss = onDismiss,
    )
}
