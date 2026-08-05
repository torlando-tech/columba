package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.audio.VoiceMessagePlayerState
import network.columba.app.audio.VoiceMessageRecordingState
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.torlando.lxst.recording.RecorderState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceMessageComponentsTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `unsupported recorder explains that voice messages are unavailable`() {
        composeRule.setContent {
            VoiceRecordingControls(
                state = VoiceMessageRecordingState(),
                hasPermission = true,
                isSupported = false,
                onRequestPermission = {},
                onStart = {},
                onStop = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText("Voice messages are not supported on this device.").assertIsDisplayed()
    }

    @Test
    fun `missing microphone permission exposes grant action`() {
        var requested = false
        composeRule.setContent {
            VoiceRecordingControls(
                state = VoiceMessageRecordingState(),
                hasPermission = false,
                isSupported = true,
                onRequestPermission = { requested = true },
                onStart = {},
                onStop = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText("Grant microphone access").performClick()
        assertTrue(requested)
    }

    @Test
    fun `recording state exposes stop and cancel actions`() {
        var stopped = false
        var cancelled = false
        composeRule.setContent {
            VoiceRecordingControls(
                state = VoiceMessageRecordingState(recorderState = RecorderState.Recording(0L), elapsedMillis = 5_000L),
                hasPermission = true,
                isSupported = true,
                onRequestPermission = {},
                onStart = {},
                onStop = { stopped = true },
                onCancel = { cancelled = true },
            )
        }

        composeRule.onNodeWithText("Recording").assertIsDisplayed()
        composeRule.onNodeWithText("0:05").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop recording").performClick()
        composeRule.onNodeWithContentDescription("Cancel recording").performClick()
        composeRule.onNodeWithText("Tap start to begin, stop to save, or cancel to discard.").assertDoesNotExist()
        assertTrue(stopped)
        assertTrue(cancelled)
    }

    @Test
    fun `selected recording preview exposes playback duration and remove action`() {
        var removed = false
        var previewed = false
        composeRule.setContent {
            VoiceDraftPreview(
                durationMillis = 65_000L,
                state = VoiceMessagePlayerState(),
                onToggle = { previewed = true },
                onRemove = { removed = true },
            )
        }

        composeRule.onNodeWithText("Voice message").assertIsDisplayed()
        composeRule.onNodeWithText("0:00 of 1:05").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play voice message").performClick()
        composeRule.onNodeWithContentDescription("Remove recording").performClick()
        assertTrue(removed)
        assertTrue(previewed)
    }

    @Test
    fun `voice bubble exposes play semantics`() {
        var toggled = false
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(durationMs = 5_000),
                onToggle = { toggled = true },
            )
        }

        composeRule.onNodeWithContentDescription("Play voice message").performClick()
        assertTrue(toggled)
    }

    @Test
    fun `playing voice bubble exposes pause and progress semantics`() {
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(playing = true, progressMs = 1_000, durationMs = 5_000),
                onToggle = {},
            )
        }
        composeRule.onNodeWithContentDescription("Pause voice message").assertIsDisplayed()
        composeRule.onNodeWithText("0:01 of 0:05").assertIsDisplayed()
    }

    @Test
    fun `voice bubble identifies unsupported audio`() {
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(error = "unsupported"),
                onToggle = {},
            )
        }
        composeRule.onNodeWithText("Unsupported voice message").assertIsDisplayed()
    }

    @Test
    fun `voice bubble identifies unavailable audio`() {
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(error = "unavailable"),
                onToggle = {},
            )
        }
        composeRule.onNodeWithText("Voice message unavailable").assertIsDisplayed()
    }
}
