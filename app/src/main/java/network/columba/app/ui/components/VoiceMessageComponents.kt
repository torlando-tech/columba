package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.audio.VoiceMessagePlayerState
import network.columba.app.audio.VoiceMessageRecordingState
import tech.torlando.lxst.recording.RecorderState
import java.util.concurrent.TimeUnit

@Composable
fun VoiceRecordingControls(
    state: VoiceMessageRecordingState,
    hasPermission: Boolean,
    isSupported: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.attachment_voice_panel_title), style = MaterialTheme.typography.titleMedium)
        when {
            !isSupported -> Text(stringResource(R.string.attachment_voice_panel_unsupported))
            !hasPermission -> Button(onClick = onRequestPermission) { Text(stringResource(R.string.attachment_voice_panel_request_permission)) }
            else -> {
                val status =
                    when {
                        state.recorderState is RecorderState.Recording -> R.string.attachment_voice_panel_recording
                        state.recorderState == RecorderState.Finalizing -> R.string.attachment_voice_panel_finalizing
                        state.selectedRecording != null -> R.string.attachment_voice_panel_selected
                        else -> R.string.attachment_voice_panel_ready
                    }
                val statusDescription = stringResource(R.string.attachment_voice_panel_status_label)
                Text(
                    stringResource(status),
                    modifier = Modifier.semantics { contentDescription = statusDescription },
                )
                state.errorMessage?.let { Text(stringResource(R.string.attachment_voice_panel_error, it), color = MaterialTheme.colorScheme.error) }
                when {
                    state.selectedRecording != null -> {
                        Text(stringResource(R.string.attachment_voice_panel_duration, formatMs(state.selectedRecording.durationMillis)))
                    }
                    state.recorderState !is RecorderState.Recording && state.recorderState != RecorderState.Finalizing -> {
                        Text(stringResource(R.string.attachment_voice_panel_instructions))
                    }
                }
                when (state.recorderState) {
                    is RecorderState.Recording -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStop) { Text(stringResource(R.string.attachment_voice_panel_stop)) }
                            Button(onClick = onCancel) { Text(stringResource(R.string.attachment_voice_panel_cancel)) }
                        }
                    }
                    RecorderState.Finalizing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStart) { Text(stringResource(R.string.attachment_voice_panel_start)) }
                            if (state.selectedRecording != null) {
                                Button(onClick = onRemove) { Text(stringResource(R.string.attachment_voice_panel_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceMessageBubble(
    title: String,
    state: VoiceMessagePlayerState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        when {
            state.loading -> Text(stringResource(R.string.message_voice_loading))
            state.error == "unsupported" -> Text(stringResource(R.string.message_voice_unsupported))
            state.error != null -> Text(stringResource(R.string.message_voice_unavailable))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = onToggle) {
                        androidx.compose.material3.Icon(
                            imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) stringResource(R.string.message_voice_pause) else stringResource(R.string.message_voice_play),
                        )
                    }
                    Text(stringResource(R.string.message_voice_progress, formatMs(state.progressMs.toLong()), formatMs(state.durationMs.toLong())))
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%d:%02d".format(total / 60, total % 60)
}
