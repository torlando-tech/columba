package com.lxmf.messenger.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.torlando.lxst.audio.NativePlaybackEngine
import tech.torlando.lxst.codec.Opus
import java.nio.ByteBuffer

/**
 * Plays back voice messages encoded as 2-byte big-endian length-prefixed Opus frames
 * using Oboe native audio output via [NativePlaybackEngine].
 *
 * Features:
 * - Single-playback enforcement: playing a new message automatically stops the current one
 * - Position preservation: stopping a message saves progress; resuming plays from that position
 * - Audio focus: acquires [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT] before playback starts,
 *   releases on stop or completion
 * - Oboe native output: low-latency audio playback via lock-free SPSC ring buffer
 *
 * Each [play] call is associated with a [messageId] for tracking and single-playback enforcement.
 * Two players in the same scope each manage their own [NativePlaybackEngine] lifecycle.
 *
 * Thread-safe: [play] dispatches all blocking calls to [Dispatchers.IO].
 */
class VoiceMessagePlayer(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:VoicePlayer"
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    @Volatile
    private var engineActive = false

    // Audio focus
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Play audio for the given message ID.
     *
     * - If a DIFFERENT message is playing: saves its position and stops it, then starts new.
     * - If the SAME message is playing: stops it (toggle behavior).
     * - If nothing is playing: starts playback from saved position (or beginning).
     *
     * This is a suspend function that blocks until playback completes or [stop] is called.
     *
     * @param messageId Unique identifier for the message being played
     * @param audioBytes 2-byte big-endian length-prefixed Opus frames
     * @param durationMs Total audio duration (for future reference)
     */
    suspend fun play(
        messageId: String,
        audioBytes: ByteArray,
        durationMs: Long,
    ) {
        val currentState = _state.value

        if (currentState.isPlaying) {
            if (currentState.currentMessageId == messageId) {
                // Toggle: same message is playing — stop it, save position
                stopInternal(savePosition = true)
                return
            } else {
                // Different message — save current position and stop
                stopInternal(savePosition = true)
            }
        }

        withContext(Dispatchers.IO) {
            val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
            try {
                // Check if telephony is using the native engine
                try {
                    if (NativePlaybackEngine.isPlaying()) {
                        Log.w(TAG, "NativePlaybackEngine already active (telephony?); deferring voice playback")
                        _state.value =
                            _state.value.copy(
                                error = "Audio busy — call in progress",
                            )
                        return@withContext
                    }
                } catch (e: UnsatisfiedLinkError) {
                    // Native library not loaded yet; safe to proceed
                }

                // Acquire audio focus before starting
                val focusGranted = acquireAudioFocus()
                if (!focusGranted) {
                    Log.w(TAG, "Audio focus not granted; continuing anyway")
                }

                // Decode all Opus frames to float PCM
                val decodedFrames = decodeFrames(audioBytes, opus)
                if (decodedFrames.isEmpty()) {
                    Log.w(TAG, "No frames decoded for message $messageId")
                    releaseAudioFocus()
                    return@withContext
                }

                val totalFrames = decodedFrames.size

                // Determine start frame from saved position
                val savedFraction = _state.value.savedPositions[messageId] ?: 0f
                val startFrame = (savedFraction * totalFrames).toInt().coerceIn(0, totalFrames - 1)
                val startFraction = if (totalFrames > 0) startFrame.toFloat() / totalFrames else 0f

                // Frames that will be written to the engine (from start to end)
                val totalFramesWritten = totalFrames - startFrame

                // Create the native engine
                val created =
                    NativePlaybackEngine.create(
                        sampleRate = VoiceMessageRecorder.SAMPLE_RATE,
                        channels = 1,
                        frameSamples = VoiceMessageRecorder.FRAME_SIZE,
                        maxBufferFrames = totalFramesWritten + 1,
                        prebufferFrames = 1,
                    )
                if (!created) {
                    Log.e(TAG, "NativePlaybackEngine.create() returned false")
                    releaseAudioFocus()
                    _state.value = _state.value.copy(error = "Audio engine unavailable")
                    return@withContext
                }

                engineActive = true

                // Write frames from the saved position onwards
                for (i in startFrame until totalFrames) {
                    val frame = decodedFrames[i]
                    val shorts =
                        ShortArray(frame.size) { j ->
                            (frame[j].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    NativePlaybackEngine.writeSamples(shorts)
                }

                val streamStarted = NativePlaybackEngine.startStream()
                if (!streamStarted) {
                    Log.e(TAG, "NativePlaybackEngine.startStream() returned false")
                    NativePlaybackEngine.destroy()
                    engineActive = false
                    releaseAudioFocus()
                    _state.value = _state.value.copy(error = "Failed to start audio stream")
                    return@withContext
                }

                // Update UI state to playing
                _state.value =
                    _state.value.copy(
                        isPlaying = true,
                        progressFraction = startFraction,
                        currentMessageId = messageId,
                        error = null,
                    )

                // Progress polling loop (50ms interval)
                while (isActive && _state.value.isPlaying) {
                    val callbackFrames = NativePlaybackEngine.getCallbackFrameCount()
                    val progress =
                        if (totalFramesWritten > 0) {
                            (startFraction + (callbackFrames.toFloat() / totalFramesWritten) * (1f - startFraction))
                                .coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                    _state.value = _state.value.copy(progressFraction = progress)

                    // Natural completion: callback has consumed all written frames
                    if (callbackFrames >= totalFramesWritten) break

                    delay(50)
                }

                // Playback complete (natural or stopped)
                if (_state.value.isPlaying) {
                    // Natural completion — remove saved position
                    val updatedPositions = _state.value.savedPositions - messageId
                    NativePlaybackEngine.stopStream()
                    NativePlaybackEngine.destroy()
                    engineActive = false
                    releaseAudioFocus()
                    _state.value =
                        _state.value.copy(
                            isPlaying = false,
                            progressFraction = 0f,
                            currentMessageId = null,
                            savedPositions = updatedPositions,
                        )
                }
                // If not isPlaying, stop() was called externally and already cleaned up
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed for message $messageId", e)
                try {
                    if (engineActive) {
                        NativePlaybackEngine.stopStream()
                        NativePlaybackEngine.destroy()
                        engineActive = false
                    }
                } catch (_: Exception) {
                    // best effort
                }
                releaseAudioFocus()
                _state.value =
                    _state.value.copy(
                        isPlaying = false,
                        currentMessageId = null,
                        error = e.message,
                    )
            } finally {
                opus.release()
            }
        }
    }

    /**
     * Stop playback immediately, saving the current position for later resumption.
     */
    fun stop() {
        stopInternal(savePosition = true)
    }

    /**
     * Clear all saved positions (e.g., when navigating away from the conversation).
     */
    fun clearSavedPositions() {
        _state.value = _state.value.copy(savedPositions = emptyMap())
    }

    private fun stopInternal(savePosition: Boolean) {
        val current = _state.value
        if (!current.isPlaying && !engineActive) return

        val updatedPositions =
            if (savePosition && current.currentMessageId != null) {
                current.savedPositions + (current.currentMessageId to current.progressFraction)
            } else {
                current.savedPositions
            }

        // Signal the coroutine loop to exit
        _state.value = current.copy(isPlaying = false)

        // Stop and destroy the native engine
        if (engineActive) {
            try {
                NativePlaybackEngine.stopStream()
                NativePlaybackEngine.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native engine", e)
            }
            engineActive = false
        }

        releaseAudioFocus()

        _state.value =
            _state.value.copy(
                isPlaying = false,
                currentMessageId = null,
                savedPositions = updatedPositions,
            )
    }

    /**
     * Acquire transient audio focus for playback (other apps will duck).
     *
     * Supports both API 26+ (AudioFocusRequest) and the deprecated path for API 24-25.
     *
     * @return true if focus was granted
     */
    private fun acquireAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setOnAudioFocusChangeListener { /* no-op for short playback */ }
                    .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { /* no-op */ },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Release audio focus previously acquired by [acquireAudioFocus].
     */
    private fun releaseAudioFocus() {
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
     * Decode length-prefixed Opus frames into a list of float32 PCM arrays.
     * Each element corresponds to one decoded Opus frame.
     *
     * @return List of FloatArray (one per frame), empty if no valid frames found.
     */
    private fun decodeFrames(
        audioBytes: ByteArray,
        opus: Opus,
    ): List<FloatArray> {
        val buf = ByteBuffer.wrap(audioBytes)
        val frames = mutableListOf<FloatArray>()

        while (buf.remaining() >= 2) {
            val frameLen = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
            if (frameLen <= 0 || frameLen > buf.remaining()) break

            val frameBytes = ByteArray(frameLen)
            buf.get(frameBytes)
            try {
                frames.add(opus.decode(frameBytes))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode frame (${frameBytes.size} bytes)", e)
            }
        }

        return frames
    }
}

/**
 * Observable playback state for the UI layer.
 *
 * @property isPlaying true while audio is actively playing
 * @property progressFraction playback progress in [0.0, 1.0]
 * @property currentMessageId message ID of the currently playing message
 * @property savedPositions map of messageId -> fractional position for position preservation
 * @property error non-null if playback failed
 */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val progressFraction: Float = 0f,
    val currentMessageId: String? = null,
    val savedPositions: Map<String, Float> = emptyMap(),
    val error: String? = null,
)
