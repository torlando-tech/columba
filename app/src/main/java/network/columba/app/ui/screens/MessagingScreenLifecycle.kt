package network.columba.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
internal fun ConversationVisibilityEffect(
    destinationHash: String,
    onConversationVisible: (String) -> Unit,
) {
    LifecycleResumeEffect(destinationHash) {
        onConversationVisible(destinationHash)
        onPauseOrDispose { }
    }
}

internal val LocalMessagingImeBottomInsetOverride = staticCompositionLocalOf<Int?> { null }

internal enum class InputPanelMode { NONE, KEYBOARD, PANEL }

internal fun inputPanelModeAfterSend(
    currentMode: InputPanelMode,
    wasVoiceMessage: Boolean,
    imeIsVisible: Boolean,
): InputPanelMode =
    when {
        wasVoiceMessage -> currentMode
        imeIsVisible -> InputPanelMode.KEYBOARD
        else -> InputPanelMode.NONE
    }
