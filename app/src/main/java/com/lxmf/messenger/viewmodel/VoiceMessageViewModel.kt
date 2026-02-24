package com.lxmf.messenger.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.audio.RecordingUiState
import com.lxmf.messenger.audio.VoiceMessageRecorder
import com.lxmf.messenger.audio.VoiceRecording
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin ViewModel that owns the [VoiceMessageRecorder] lifecycle and exposes
 * recording state to the UI layer.
 *
 * The composable launches a coroutine to call [stopRecording] (which is a suspend
 * fun because AudioRecord.stop()/release() are blocking native calls dispatched to
 * Dispatchers.IO) and passes the resulting [VoiceRecording] to
 * [MessagingViewModel.sendMessage].
 */
@HiltViewModel
class VoiceMessageViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val recorder = VoiceMessageRecorder(context)

        /** Observable recording state for the UI (isRecording, durationMs, latestPeak, error). */
        val recordingState: StateFlow<RecordingUiState> = recorder.state

        /**
         * Start recording. Permission must be checked at the UI layer before calling this.
         * If already recording, this is a no-op.
         */
        fun startRecording() {
            recorder.start()
        }

        /**
         * Stop recording and return the captured audio.
         *
         * This is a suspend fun because [VoiceMessageRecorder.stop] dispatches
         * AudioRecord.stop()/release() to Dispatchers.IO to avoid ANR when called
         * from the main/composition thread.
         *
         * @return [VoiceRecording] with encoded Opus bytes and metadata, or null if:
         *   - Not currently recording
         *   - Recording was shorter than 300ms (silent discard)
         */
        suspend fun stopRecording(): VoiceRecording? = recorder.stop()

        /**
         * Cancel recording without sending. Launches a coroutine to call the suspend
         * [VoiceMessageRecorder.stop] and discards the result.
         */
        fun cancelRecording() {
            viewModelScope.launch { recorder.stop() }
        }

        override fun onCleared() {
            recorder.release()
            super.onCleared()
        }
    }
