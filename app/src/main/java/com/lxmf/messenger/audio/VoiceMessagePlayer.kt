package com.lxmf.messenger.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.torlando.lxst.codec.Opus
import java.nio.ByteBuffer

/**
 * Plays back voice messages encoded as 2-byte big-endian length-prefixed Opus frames.
 *
 * This is the inverse of [VoiceMessageRecorder]'s encoding format:
 * each frame is `[2-byte length][opus bytes]`, decoded with [Opus.decode], and
 * written to [AudioTrack] for speaker output.
 *
 * Thread-safe: [play] and [stop] dispatch blocking AudioTrack calls to [Dispatchers.IO].
 */
class VoiceMessagePlayer {
    companion object {
        private const val TAG = "Columba:VoicePlayer"
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

    /**
     * Play the given Opus-encoded audio bytes (length-prefixed frame format).
     * If already playing, stops the current playback first.
     *
     * This is a suspend function that blocks until playback completes or [stop] is called.
     */
    suspend fun play(
        audioBytes: ByteArray,
        durationMs: Long,
    ) {
        stop()

        withContext(Dispatchers.IO) {
            val opus = Opus(Opus.PROFILE_VOICE_MEDIUM)
            try {
                // Decode all frames to PCM
                val pcmSamples = decodeFrames(audioBytes, opus)
                if (pcmSamples.isEmpty()) {
                    Log.w(TAG, "No frames decoded")
                    return@withContext
                }

                // Convert float32 to int16 for AudioTrack
                val shortSamples =
                    ShortArray(pcmSamples.size) { i ->
                        (pcmSamples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }

                val bufSize =
                    AudioTrack.getMinBufferSize(
                        VoiceMessageRecorder.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )

                val track =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        ).setAudioFormat(
                            AudioFormat
                                .Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(VoiceMessageRecorder.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        ).setBufferSizeInBytes(maxOf(bufSize, shortSamples.size * 2))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                track.write(shortSamples, 0, shortSamples.size)
                audioTrack = track
                isPlaying = true
                _state.value = PlaybackUiState(isPlaying = true, progressFraction = 0f)

                track.play()

                // Update progress while playing
                val totalFrames = shortSamples.size
                while (isActive && isPlaying) {
                    val head = track.playbackHeadPosition
                    if (head >= totalFrames) break
                    val fraction = head.toFloat() / totalFrames
                    _state.value = _state.value.copy(progressFraction = fraction)
                    kotlinx.coroutines.delay(50)
                }

                // Playback complete
                isPlaying = false
                track.stop()
                track.release()
                audioTrack = null
                _state.value = PlaybackUiState()
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
                isPlaying = false
                _state.value = PlaybackUiState(error = e.message)
            } finally {
                opus.release()
            }
        }
    }

    /**
     * Stop playback immediately.
     */
    fun stop() {
        isPlaying = false
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping playback", e)
            }
        }
        audioTrack = null
        _state.value = PlaybackUiState()
    }

    /**
     * Decode length-prefixed Opus frames to a single float32 PCM array.
     */
    private fun decodeFrames(
        audioBytes: ByteArray,
        opus: Opus,
    ): FloatArray {
        val buf = ByteBuffer.wrap(audioBytes)
        val allSamples = mutableListOf<FloatArray>()

        while (buf.remaining() >= 2) {
            val frameLen = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
            if (frameLen <= 0 || frameLen > buf.remaining()) break

            val frameBytes = ByteArray(frameLen)
            buf.get(frameBytes)
            try {
                allSamples.add(opus.decode(frameBytes))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode frame (${frameBytes.size} bytes)", e)
            }
        }

        // Concatenate all decoded frames
        val totalSize = allSamples.sumOf { it.size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (samples in allSamples) {
            samples.copyInto(result, offset)
            offset += samples.size
        }
        return result
    }
}

/**
 * Observable playback state for the UI layer.
 */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val progressFraction: Float = 0f,
    val error: String? = null,
)
