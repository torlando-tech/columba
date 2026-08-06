package network.columba.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
    fun `voice bubble does not add card edge padding`() {
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(durationMs = 5_000),
                onToggle = {},
                modifier = Modifier.width(268.dp).testTag("voice-bubble"),
            )
        }

        val bubbleBounds = composeRule.onNodeWithTag("voice-bubble").getUnclippedBoundsInRoot()
        val titleBounds = composeRule.onNodeWithText("Voice message").getUnclippedBoundsInRoot()
        assertTrue(titleBounds.left - bubbleBounds.left < 1.dp)
        assertTrue(bubbleBounds.bottom - bubbleBounds.top < 120.dp)
    }

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
    fun `permanently denied microphone permission opens app settings`() {
        var openedSettings = false
        composeRule.setContent {
            VoiceRecordingControls(
                state = VoiceMessageRecordingState(),
                hasPermission = false,
                permissionPermanentlyDenied = true,
                isSupported = true,
                onRequestPermission = {},
                onOpenPermissionSettings = { openedSettings = true },
                onStart = {},
                onStop = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText("Microphone access is disabled. Enable it in app settings.").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun `active call explains that voice recording is unavailable`() {
        composeRule.setContent {
            VoiceRecordingControls(
                state = VoiceMessageRecordingState(),
                hasPermission = true,
                isSupported = true,
                isBlockedByCall = true,
                onRequestPermission = {},
                onStart = {},
                onStop = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithText("Voice recording is unavailable during a call.").assertIsDisplayed()
        composeRule.onNodeWithText("Start recording").assertDoesNotExist()
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

        val playNode = composeRule.onNodeWithContentDescription("Play voice message")
        val playBounds = playNode.getUnclippedBoundsInRoot()
        assertTrue(playBounds.right - playBounds.left >= 48.dp)
        assertTrue(playBounds.bottom - playBounds.top >= 48.dp)
        playNode.performClick()
        assertTrue(toggled)
    }

    @Test
    fun `voice bubble remains readable and compact at two hundred percent font scale`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                VoiceMessageBubble(
                    title = "Voice message",
                    state = VoiceMessagePlayerState(durationMs = 65_000),
                    onToggle = {},
                    modifier = Modifier.width(268.dp).testTag("large-font-voice-bubble"),
                )
            }
        }

        composeRule.onNodeWithText("Voice message").assertIsDisplayed()
        composeRule.onNodeWithText("0:00 of 1:05").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play voice message").assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag("large-font-voice-bubble").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top < 160.dp)
    }

    @Test
    fun `voice bubble shows probed duration before playback`() {
        composeRule.setContent {
            VoiceMessageBubble(
                title = "Voice message",
                state = VoiceMessagePlayerState(),
                durationMillis = 65_000,
                waveformLevels = listOf(0.2f, 0.6f, 1f, 0.4f),
                onToggle = {},
            )
        }

        composeRule.onNodeWithText("0:00 of 1:05").assertIsDisplayed()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f),
                ),
            ).assertIsDisplayed()
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
