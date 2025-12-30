package com.lxmf.messenger.reticulum.audio.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin Audio Bridge for Python LXST.
 *
 * Provides Android AudioTrack (playback) and AudioRecord (capture) access to
 * Python's LXST voice call system via Chaquopy. This replaces LXST's Pyjnius-based
 * audio backend with a Chaquopy-compatible implementation.
 *
 * **Thread Safety**: All public methods are thread-safe and can be called from
 * Python threads via Chaquopy.
 *
 * **Audio Configuration**:
 * - Sample rate: 48000 Hz (LXST default)
 * - Encoding: PCM 16-bit (int16)
 * - Channels: Mono (for voice)
 * - Mode: Voice communication (enables AEC, AGC, NS)
 *
 * @property context Application context
 * @property audioManager AudioManager instance (injectable for testing)
 */
@SuppressLint("MissingPermission")
class KotlinAudioBridge(
    private val context: Context,
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
) {
    companion object {
        private const val TAG = "Columba:AudioBridge"

        // Default audio parameters (matching LXST defaults)
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_FRAME_SIZE_MS = 20 // 20ms frames
        const val BUFFER_QUEUE_SIZE = 10 // Number of audio frames to buffer

        @Volatile
        private var instance: KotlinAudioBridge? = null

        /**
         * Get or create singleton instance.
         */
        fun getInstance(context: Context): KotlinAudioBridge {
            return instance ?: synchronized(this) {
                instance ?: KotlinAudioBridge(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Reset singleton instance (for testing).
         */
        internal fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Playback state
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    @Volatile
    private var playbackSampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var playbackChannels = DEFAULT_CHANNELS

    // Recording state
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val recordBuffer = LinkedBlockingQueue<ByteArray>(BUFFER_QUEUE_SIZE)

    @Volatile
    private var recordSampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var recordChannels = DEFAULT_CHANNELS

    @Volatile
    private var recordFrameSize = 960 // 20ms at 48kHz

    // Audio routing
    @Volatile
    private var speakerphoneOn = false

    @Volatile
    private var microphoneMuted = false

    // Python callbacks (set by Python call manager)
    @Volatile
    private var onRecordingError: PyObject? = null

    @Volatile
    private var onPlaybackError: PyObject? = null

    // ===== Playback Methods (called from Python) =====

    /**
     * Start audio playback.
     *
     * Initializes AudioTrack with voice communication attributes for
     * optimized voice call audio (AEC, AGC, noise suppression).
     *
     * @param sampleRate Audio sample rate in Hz (typically 48000)
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param lowLatency Enable low-latency mode (API 26+)
     */
    fun startPlayback(
        sampleRate: Int,
        channels: Int,
        lowLatency: Boolean,
    ) {
        if (isPlaying.get()) {
            Log.w(TAG, "Playback already started")
            return
        }

        Log.i(TAG, "Starting playback: rate=$sampleRate, channels=$channels, lowLatency=$lowLatency")

        playbackSampleRate = sampleRate
        playbackChannels = channels

        val channelConfig =
            if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

        val bufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            onPlaybackError?.callAttr("__call__", "Invalid buffer size: $bufferSize")
            return
        }

        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        val format =
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()

        try {
            val trackBuilder =
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize * 2) // Double buffer for smoothness
                    .setTransferMode(AudioTrack.MODE_STREAM)

            // Enable low-latency mode on supported devices
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = trackBuilder.build()

            // Set audio mode for voice communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Apply speaker routing
            audioManager.isSpeakerphoneOn = speakerphoneOn

            audioTrack?.play()
            isPlaying.set(true)
            Log.i(TAG, "Playback started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onPlaybackError?.callAttr("__call__", e.message ?: "Unknown error")
        }
    }

    /**
     * Write audio data to the output.
     *
     * Data should be PCM 16-bit samples as a byte array.
     *
     * @param audioData Raw PCM 16-bit audio bytes
     */
    fun writeAudio(audioData: ByteArray) {
        if (!isPlaying.get()) {
            return
        }

        val track = audioTrack ?: return

        try {
            val written =
                track.write(
                    audioData,
                    0,
                    audioData.size,
                    AudioTrack.WRITE_BLOCKING,
                )
            if (written < 0) {
                Log.w(TAG, "AudioTrack write error: $written")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }
    }

    /**
     * Stop audio playback.
     */
    fun stopPlayback() {
        if (!isPlaying.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping playback")

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            // Reset audio mode
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "Playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }

    // ===== Recording Methods (called from Python) =====

    /**
     * Start audio recording.
     *
     * Initializes AudioRecord with voice communication source for
     * optimized voice capture (AEC reference if available).
     *
     * @param sampleRate Audio sample rate in Hz (typically 48000)
     * @param channels Number of channels (1 for mono)
     * @param samplesPerFrame Samples per frame (e.g., 960 for 20ms at 48kHz)
     */
    fun startRecording(
        sampleRate: Int,
        channels: Int,
        samplesPerFrame: Int,
    ) {
        if (isRecording.get()) {
            Log.w(TAG, "Recording already started")
            return
        }

        Log.i(TAG, "Starting recording: rate=$sampleRate, channels=$channels, samplesPerFrame=$samplesPerFrame")

        recordSampleRate = sampleRate
        recordChannels = channels
        recordFrameSize = samplesPerFrame

        val channelConfig =
            if (channels == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

        val bufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            onRecordingError?.callAttr("__call__", "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufferSize, samplesPerFrame * channels * 2 * 2), // At least 2 frames
                )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                onRecordingError?.callAttr("__call__", "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            recordBuffer.clear()

            // Start background recording thread
            scope.launch {
                recordingLoop()
            }

            Log.i(TAG, "Recording started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onRecordingError?.callAttr("__call__", e.message ?: "Unknown error")
        }
    }

    /**
     * Background recording loop.
     *
     * Continuously reads from AudioRecord and queues frames for Python to consume.
     */
    private fun recordingLoop() {
        val frameBytes = recordFrameSize * recordChannels * 2 // 16-bit = 2 bytes per sample
        val buffer = ByteArray(frameBytes)

        Log.d(TAG, "Recording loop started, frame size: $frameBytes bytes")

        while (isRecording.get()) {
            val record = audioRecord ?: break

            try {
                val bytesRead = record.read(buffer, 0, frameBytes)

                when {
                    bytesRead > 0 -> {
                        // Queue frame for Python (non-blocking, drops oldest if full)
                        if (!recordBuffer.offer(buffer.copyOf(bytesRead))) {
                            recordBuffer.poll() // Remove oldest
                            recordBuffer.offer(buffer.copyOf(bytesRead))
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord: ERROR_INVALID_OPERATION")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord: ERROR_BAD_VALUE")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                break
            }
        }

        Log.d(TAG, "Recording loop ended")
    }

    /**
     * Read audio data from the recording buffer.
     *
     * Called by Python to retrieve recorded audio frames.
     *
     * @param numSamples Number of samples to read (ignored, returns one frame)
     * @return Audio data as byte array, or null if no data available
     */
    fun readAudio(numSamples: Int): ByteArray? {
        if (!isRecording.get()) {
            return null
        }

        return try {
            // Wait up to 50ms for data (allows for ~20ms frames)
            recordBuffer.poll(50, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }
    }

    /**
     * Stop audio recording.
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping recording")

        try {
            recordBuffer.clear()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    // ===== Audio Routing (called from Python) =====

    /**
     * Enable or disable speakerphone.
     *
     * @param enabled True for speaker, false for earpiece
     */
    fun setSpeakerphoneOn(enabled: Boolean) {
        speakerphoneOn = enabled
        audioManager.isSpeakerphoneOn = enabled
        Log.d(TAG, "Speakerphone: $enabled")
    }

    /**
     * Mute or unmute the microphone.
     *
     * @param muted True to mute, false to unmute
     */
    fun setMicrophoneMute(muted: Boolean) {
        microphoneMuted = muted
        audioManager.isMicrophoneMute = muted
        Log.d(TAG, "Microphone mute: $muted")
    }

    /**
     * Check if speakerphone is enabled.
     */
    fun isSpeakerphoneOn(): Boolean = audioManager.isSpeakerphoneOn

    /**
     * Check if microphone is muted.
     */
    fun isMicrophoneMuted(): Boolean = audioManager.isMicrophoneMute

    // ===== Device Enumeration (for LXST compatibility) =====

    /**
     * Get list of available output devices.
     *
     * Returns a list of device info maps for LXST device selection.
     *
     * @return List of device info as JSON-like maps
     */
    fun getOutputDevices(): List<Map<String, Any>> {
        val devices = mutableListOf<Map<String, Any>>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
                devices.add(deviceToMap(device, isSink = true))
            }
        } else {
            // Fallback for older devices
            devices.add(
                mapOf(
                    "id" to 0,
                    "name" to "Default Speaker",
                    "type" to "speaker",
                    "is_sink" to true,
                ),
            )
        }

        return devices
    }

    /**
     * Get list of available input devices.
     *
     * @return List of device info as JSON-like maps
     */
    fun getInputDevices(): List<Map<String, Any>> {
        val devices = mutableListOf<Map<String, Any>>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
                devices.add(deviceToMap(device, isSink = false))
            }
        } else {
            devices.add(
                mapOf(
                    "id" to 0,
                    "name" to "Default Microphone",
                    "type" to "microphone",
                    "is_source" to true,
                ),
            )
        }

        return devices
    }

    @SuppressLint("NewApi")
    private fun deviceToMap(
        device: AudioDeviceInfo,
        isSink: Boolean,
    ): Map<String, Any> {
        val typeName =
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Internal Earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Internal Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                else -> "Unknown"
            }

        return mapOf(
            "id" to device.id,
            "name" to (device.productName?.toString() ?: typeName),
            "type" to device.type,
            "type_description" to typeName,
            "is_sink" to isSink,
            "is_source" to !isSink,
            "sample_rates" to device.sampleRates.toList(),
            "channel_counts" to device.channelCounts.toList(),
        )
    }

    // ===== Python Callback Setters =====

    /**
     * Set callback for recording errors.
     */
    fun setOnRecordingError(callback: PyObject) {
        onRecordingError = callback
    }

    /**
     * Set callback for playback errors.
     */
    fun setOnPlaybackError(callback: PyObject) {
        onPlaybackError = callback
    }

    // ===== Lifecycle =====

    /**
     * Check if playback is active.
     */
    fun isPlaybackActive(): Boolean = isPlaying.get()

    /**
     * Check if recording is active.
     */
    fun isRecordingActive(): Boolean = isRecording.get()

    /**
     * Shutdown the audio bridge.
     *
     * Stops all audio operations and releases resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down audio bridge")
        stopPlayback()
        stopRecording()
        scope.cancel()
    }
}
