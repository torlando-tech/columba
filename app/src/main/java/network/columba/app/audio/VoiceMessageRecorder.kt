package network.columba.app.audio

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tech.torlando.lxst.recording.AudioFileRecorder
import tech.torlando.lxst.recording.AudioRecordingException
import tech.torlando.lxst.recording.RecordedAudio
import tech.torlando.lxst.recording.RecorderState
import java.io.File
import java.util.UUID

interface VoiceRecorderBackend : AutoCloseable {
    val state: StateFlow<RecorderState>
    val isSupported: Boolean
    fun start(outputFile: File)
    fun stop(): RecordedAudio
    fun cancel()
}

class LxstVoiceRecorderBackend(
    context: Context,
) : VoiceRecorderBackend {
    private val recorder = AudioFileRecorder(context)
    override val state: StateFlow<RecorderState> = recorder.state
    override val isSupported: Boolean get() = recorder.isSupported()
    override fun start(outputFile: File) = recorder.start(outputFile)
    override fun stop(): RecordedAudio = recorder.stop()
    override fun cancel() = recorder.cancel()
    override fun close() = recorder.close()
}

data class VoiceMessageRecordingState(
    val recorderState: RecorderState = RecorderState.Idle,
    val selectedRecording: RecordedAudio? = null,
    val activeRecordingFile: File? = null,
    val errorMessage: String? = null,
    val elapsedMillis: Long = 0L,
)

class VoiceMessageRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val recorderFactory: (Context) -> VoiceRecorderBackend = { LxstVoiceRecorderBackend(it) },
) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private var recorder: VoiceRecorderBackend? = null
    private val _state = MutableStateFlow(VoiceMessageRecordingState())
    val state: StateFlow<VoiceMessageRecordingState> = _state.asStateFlow()
    private var deadlineJob: Job? = null
    private var mirrorJob: Job? = null
    private var elapsedJob: Job? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && backend().isSupported

    private fun backend(): VoiceRecorderBackend {
        recorder?.let { return it }
        return recorderFactory(appContext).also { created ->
            recorder = created
            mirrorJob =
                scope.launch {
                    created.state.collect { backendState ->
                        val current = _state.value
                        _state.value =
                            when (backendState) {
                                is RecorderState.Recording -> {
                                    startElapsedTicker()
                                    current.copy(
                                        recorderState = backendState,
                                        errorMessage = null,
                                    )
                                }
                                is RecorderState.Finalizing -> current.copy(recorderState = backendState)
                                is RecorderState.Completed -> current.copy(
                                    recorderState = backendState,
                                    selectedRecording = backendState.recording,
                                    activeRecordingFile = null,
                                    elapsedMillis = backendState.recording.durationMillis,
                                    errorMessage = null,
                                )
                                is RecorderState.Failed -> current.copy(
                                    recorderState = backendState,
                                    activeRecordingFile = null,
                                    errorMessage = backendState.cause.message ?: "Recording failed",
                                )
                                else -> current.copy(recorderState = backendState)
                            }
                    }
                }
        }
    }

    fun start(maxDurationMillis: Long = MAX_DURATION_MILLIS): File {
        check(isSupported) { "Voice messages are unsupported on this device" }
        check(_state.value.recorderState !is RecorderState.Recording) { "Recording already active" }
        val backend = backend()
        val previousRecording = _state.value.selectedRecording
        val tempDir =
            File(requireNotNull(appContext.cacheDir) { "Application cache directory is unavailable" }, "voice-notes")
                .apply { mkdirs() }
        val output = File(tempDir, "voice_${UUID.randomUUID()}.ogg")
        try {
            backend.start(output)
        } catch (error: Exception) {
            _state.value = _state.value.copy(errorMessage = error.message ?: "Unable to start recording")
            throw error
        }
        previousRecording?.file?.delete()
        _state.value =
            _state.value.copy(
                recorderState = backend.state.value,
                selectedRecording = null,
                activeRecordingFile = output,
                elapsedMillis = 0L,
            )
        deadlineJob?.cancel()
        deadlineJob =
            scope.launch {
                delay(maxDurationMillis)
                if (backend.state.value is RecorderState.Recording) {
                    stop()
                }
            }
        return output
    }

    fun stop(): RecordedAudio {
        val backend = checkNotNull(recorder) { "Recording has not started" }
        val recording =
            try {
                backend.stop()
            } catch (error: AudioRecordingException) {
                _state.value = _state.value.copy(errorMessage = error.message)
                throw error
            }
        deadlineJob?.cancel()
        elapsedJob?.cancel()
        _state.value =
            _state.value.copy(
                recorderState = backend.state.value,
                selectedRecording = recording,
                activeRecordingFile = null,
                elapsedMillis = recording.durationMillis,
                errorMessage = null,
            )
        return recording
    }

    fun cancel() {
        deadlineJob?.cancel()
        elapsedJob?.cancel()
        _state.value.activeRecordingFile?.delete()
        recorder?.cancel()
        _state.value = VoiceMessageRecordingState()
    }

    fun removeSelected(expectedRecording: RecordedAudio? = null): Boolean {
        val selected = _state.value.selectedRecording ?: return false
        if (expectedRecording != null && selected.file != expectedRecording.file) return false
        selected.file.delete()
        _state.value = _state.value.copy(selectedRecording = null)
        return true
    }

    override fun close() {
        cancel()
        mirrorJob?.cancel()
        recorder?.close()
    }

    private fun startElapsedTicker() {
        if (elapsedJob?.isActive == true) return
        elapsedJob =
            scope.launch {
                while (true) {
                    delay(1000L)
                    val active = recorder?.state?.value
                    if (active !is RecorderState.Recording) return@launch
                    _state.value = _state.value.copy(
                        elapsedMillis = (_state.value.elapsedMillis + 1000L).coerceAtMost(MAX_DURATION_MILLIS),
                    )
                }
            }
    }

    companion object {
        const val MAX_DURATION_MILLIS: Long = 5 * 60 * 1000L
    }
}
