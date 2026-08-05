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
import tech.torlando.lxst.recording.RecordedAudio
import tech.torlando.lxst.recording.RecorderState
import java.io.File

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
                onRemove = {},
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
                onRemove = {},
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
                state = VoiceMessageRecordingState(recorderState = RecorderState.Recording(0L)),
                hasPermission = true,
                isSupported = true,
                onRequestPermission = {},
                onStart = {},
                onStop = { stopped = true },
                onCancel = { cancelled = true },
                onRemove = {},
            )
        }

        composeRule.onNodeWithText("Stop recording").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Cancel recording").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Tap start to begin, stop to save, or cancel to discard.").assertDoesNotExist()
        assertTrue(stopped)
        assertTrue(cancelled)
    }

    @Test
    fun `selected recording exposes duration and remove action`() {
        var removed = false
        val recording = RecordedAudio(File("selected.ogg"), 65_000L, 10L)
        composeRule.setContent {
            VoiceRecordingControls(
                state =
                    VoiceMessageRecordingState(
                        recorderState = RecorderState.Completed(recording),
                        selectedRecording = recording,
                    ),
                hasPermission = true,
                isSupported = true,
                onRequestPermission = {},
                onStart = {},
                onStop = {},
                onCancel = {},
                onRemove = { removed = true },
            )
        }

        composeRule.onNodeWithText("Duration 1:05").assertIsDisplayed()
        composeRule.onNodeWithText("Remove recording").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Cancel recording").assertDoesNotExist()
        assertTrue(removed)
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
