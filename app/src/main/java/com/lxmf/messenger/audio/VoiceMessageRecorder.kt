package com.lxmf.messenger.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.torlando.lxst.codec.Opus
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records voice messages using [AudioRecord] at 24kHz mono, encodes to Opus
 * via LXST-kt [Opus] encoder, and accumulates 2-byte big-endian length-prefixed
 * frames in a [ByteArrayOutputStream].
 *
 * Features:
 * - 300ms minimum duration: recordings shorter than this are silently discarded
 * - 30s maximum duration: auto-stops and returns the recording
 * - Audio focus: acquires [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE] to
 *   silence notifications during recording
 * - Waveform peaks: captures per-frame peak amplitudes for visualization
 * - Thread-safe: [stop] dispatches blocking [AudioRecord.stop]/[AudioRecord.release]
 *   to [Dispatchers.IO] to avoid ANR on the main thread
 *
 * Lifecycle: call [start] to begin recording, [stop] to end, and [release] when
 * the recorder is no longer needed. Permission must be checked before calling [start].
 */
class VoiceMessageRecorder(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:VoiceRecorder"

        /** Sample rate matching Opus.profileSamplerate(PROFILE_VOICE_MEDIUM). */
        const val SAMPLE_RATE = 24000

        /** 20ms frame at 24kHz -- valid Opus frame duration. */
        const val FRAME_SIZE = 480

        /** Auto-stop after 30 seconds to keep messages mesh-friendly (~30KB). */
        const val MAX_DURATION_MS = 30_000L

        /** Taps shorter than 300ms are silently discarded. */
        const val MIN_DURATION_MS = 300L

        /** Codec identifier written alongside audio bytes for decoding. */
        const val CODEC_ID = "opus_vm"
    }

    // -- Opus encoder --
    private val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)

    // -- Coroutine scope for recording loop and auto-stop --
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -- Observable recording state --
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    // -- Recording state --
    @Volatile
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val encodedOutput = ByteArrayOutputStream()
    private val waveformPeaks = mutableListOf<Float>()
    private var startTimeMs = 0L
    private var autoStopJob: Job? = null

    // -- Audio focus --
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Start recording. Permission must be checked before calling this method;
     * the [SuppressLint] annotation reflects that RECORD_AUDIO is verified at
     * the UI layer before reaching here.
     *
     * If already recording, this is a no-op.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.getAndSet(true)) return

        encodedOutput.reset()
        waveformPeaks.clear()

        // Acquire audio focus to silence notifications during recording
        val focusGranted = acquireAudioFocus(context)
        if (!focusGranted) {
            Log.w(TAG, "Audio focus not granted; continuing anyway")
        }

        val minBuf =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE * 2 * 2),
            )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            isRecording.set(false)
            _state.value = _state.value.copy(error = "Microphone unavailable")
            releaseAudioFocus(context)
            return
        }

        audioRecord = record
        startTimeMs = System.currentTimeMillis()
        record.startRecording()
        _state.value = _state.value.copy(isRecording = true, durationMs = 0L, error = null)

        // Launch recording loop on IO dispatcher
        scope.launch { recordingLoop(record) }

        // Auto-stop at MAX_DURATION_MS
        autoStopJob =
            scope.launch {
                delay(MAX_DURATION_MS)
                if (isRecording.get()) {
                    stop(autoSend = true)
                }
            }
    }

    /**
     * Stop recording and return the captured audio.
     *
     * This is a suspend function because [AudioRecord.stop] and [AudioRecord.release]
     * are blocking native calls (50-200ms). They are dispatched to [Dispatchers.IO]
     * to prevent ANR when called from the main/composition thread.
     *
     * @param autoSend true when called by the 30s auto-stop timer (bypasses 300ms check)
     * @return [VoiceRecording] with encoded Opus bytes and metadata, or null if:
     *   - Not currently recording
     *   - Recording was shorter than [MIN_DURATION_MS] and [autoSend] is false (silent discard)
     */
    suspend fun stop(autoSend: Boolean = false): VoiceRecording? {
        if (!isRecording.getAndSet(false)) return null
        autoStopJob?.cancel()

        val elapsed = System.currentTimeMillis() - startTimeMs

        // Dispatch blocking AudioRecord calls to IO thread to avoid ANR
        withContext(Dispatchers.IO) {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }

        // CRITICAL: Release audio focus BEFORE the discard check.
        // This ensures tap-discards (< 300ms) still release focus so other apps
        // are not permanently muted.
        releaseAudioFocus(context)

        // Silent discard for taps shorter than 300ms
        if (elapsed < MIN_DURATION_MS && !autoSend) {
            _state.value = RecordingUiState()
            return null
        }

        val audioBytes = encodedOutput.toByteArray()
        val peaks = waveformPeaks.toList()
        _state.value = RecordingUiState()

        return VoiceRecording(audioBytes, CODEC_ID, elapsed, peaks)
    }

    /**
     * Recording loop: reads PCM frames from [AudioRecord], converts to float32,
     * encodes to Opus, and accumulates length-prefixed frames.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun recordingLoop(record: AudioRecord) {
        val shortBuf = ShortArray(FRAME_SIZE)

        while (isRecording.get()) {
            // ShortArray overload: reads samples (not bytes)
            val read = record.read(shortBuf, 0, FRAME_SIZE)
            if (read <= 0) {
                delay(5)
                continue
            }

            // Convert int16 to float32 in [-1.0, 1.0] range
            val floats = FloatArray(read) { i -> shortBuf[i] / 32768f }

            // Only encode complete frames -- partial final frame is discarded
            // (padding with zeros would add audible silence; Opus requires exact frame sizes)
            if (floats.size != FRAME_SIZE) continue

            val encoded = opus.encode(floats)

            // 2-byte big-endian length prefix
            encodedOutput.write((encoded.size shr 8) and 0xFF)
            encodedOutput.write(encoded.size and 0xFF)
            encodedOutput.write(encoded)

            // Capture peak amplitude for waveform visualization
            val peak = floats.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            waveformPeaks.add(peak)

            // Update UI state
            val currentDuration = System.currentTimeMillis() - startTimeMs
            _state.value =
                _state.value.copy(
                    durationMs = currentDuration,
                    latestPeak = peak,
                )
        }
    }

    /**
     * Acquire transient exclusive audio focus to silence notifications during recording.
     *
     * @return true if focus was granted
     */
    private fun acquireAudioFocus(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                        ) {
                            if (scope.isActive && isRecording.get()) {
                                scope.launch { stop() }
                            }
                        }
                    }.build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        if (scope.isActive && isRecording.get()) {
                            scope.launch { stop() }
                        }
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Release audio focus acquired during recording.
     * Called in [stop] BEFORE the 300ms discard check to ensure focus is released
     * in all paths (both send and discard).
     */
    private fun releaseAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * Release all resources. Call when the recorder is no longer needed
     * (e.g., ViewModel onCleared).
     */
    fun release() {
        scope.launch { stop() }
        opus.release()
        scope.cancel()
    }
}

/**
 * Completed voice recording with encoded Opus audio and metadata.
 *
 * @property audioBytes 2-byte big-endian length-prefixed Opus frames
 * @property codecId codec identifier (e.g., "opus_vm")
 * @property durationMs recording duration in milliseconds
 * @property waveformPeaks per-frame peak amplitudes for waveform visualization
 */
data class VoiceRecording(
    val audioBytes: ByteArray,
    val codecId: String,
    val durationMs: Long,
    val waveformPeaks: List<Float>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceRecording) return false
        return audioBytes.contentEquals(other.audioBytes) &&
            codecId == other.codecId &&
            durationMs == other.durationMs &&
            waveformPeaks == other.waveformPeaks
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + codecId.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + waveformPeaks.hashCode()
        return result
    }
}

/**
 * Observable recording state for the UI layer.
 *
 * @property isRecording true while actively recording
 * @property durationMs elapsed recording time in milliseconds
 * @property latestPeak most recent frame's peak amplitude (0.0-1.0)
 * @property error non-null if recording failed to start
 */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val durationMs: Long = 0L,
    val latestPeak: Float = 0f,
    val error: String? = null,
)
