package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.audio.VoiceMessagePlayerState
import network.columba.app.audio.VoiceMessageRecordingState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for voice message components: recording controls, draft
 * preview and message bubble.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `voice recording controls ready`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceRecordingControls(
                    state = VoiceMessageRecordingState(),
                    hasPermission = true,
                    isSupported = true,
                    onRequestPermission = {},
                    onStart = {},
                    onStop = {},
                    onCancel = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_recording_controls_ready", composeRule)
    }

    @Test
    fun `voice recording controls permission needed`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceRecordingControls(
                    state = VoiceMessageRecordingState(),
                    hasPermission = false,
                    isSupported = true,
                    onRequestPermission = {},
                    onStart = {},
                    onStop = {},
                    onCancel = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_recording_controls_permission_needed", composeRule)
    }

    @Test
    fun `voice recording controls permission permanently denied`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceRecordingControls(
                    state = VoiceMessageRecordingState(),
                    hasPermission = false,
                    permissionPermanentlyDenied = true,
                    isSupported = true,
                    onRequestPermission = {},
                    onOpenPermissionSettings = {},
                    onStart = {},
                    onStop = {},
                    onCancel = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_recording_controls_permission_permanently_denied", composeRule)
    }

    @Test
    fun `voice draft preview idle`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceDraftPreview(
                    durationMillis = DRAFT_DURATION_MILLIS,
                    state = VoiceMessagePlayerState(),
                    onToggle = {},
                    onRemove = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_draft_preview_idle", composeRule)
    }

    @Test
    fun `voice draft preview playing`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceDraftPreview(
                    durationMillis = DRAFT_DURATION_MILLIS,
                    state = VoiceMessagePlayerState(
                        playing = true,
                        progressMs = DRAFT_PROGRESS_MILLIS,
                        durationMs = DRAFT_DURATION_MILLIS.toInt(),
                    ),
                    onToggle = {},
                    onRemove = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_draft_preview_playing", composeRule)
    }

    @Test
    fun `voice message bubble idle`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    title = BUBBLE_TITLE,
                    state = VoiceMessagePlayerState(durationMs = BUBBLE_DURATION_MILLIS),
                    onToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_message_bubble_idle", composeRule)
    }

    @Test
    fun `voice message bubble playing`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    title = BUBBLE_TITLE,
                    state = VoiceMessagePlayerState(
                        playing = true,
                        progressMs = BUBBLE_PROGRESS_MILLIS,
                        durationMs = BUBBLE_DURATION_MILLIS,
                    ),
                    onToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_message_bubble_playing", composeRule)
    }

    @Test
    fun `voice message bubble loading`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    title = BUBBLE_TITLE,
                    state = VoiceMessagePlayerState(loading = true),
                    onToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_message_bubble_loading", composeRule)
    }

    @Test
    fun `voice message bubble unsupported`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    title = BUBBLE_TITLE,
                    state = VoiceMessagePlayerState(error = "unsupported"),
                    onToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_message_bubble_unsupported", composeRule)
    }

    @Test
    fun `voice message bubble unavailable`() {
        composeRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    title = BUBBLE_TITLE,
                    state = VoiceMessagePlayerState(error = "file missing"),
                    onToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("voice_message_bubble_unavailable", composeRule)
    }

    private companion object {
        const val DRAFT_DURATION_MILLIS = 65_000L
        const val DRAFT_PROGRESS_MILLIS = 12_000
        const val BUBBLE_TITLE = "Voice message"
        const val BUBBLE_DURATION_MILLIS = 45_000
        const val BUBBLE_PROGRESS_MILLIS = 30_000
    }
}
