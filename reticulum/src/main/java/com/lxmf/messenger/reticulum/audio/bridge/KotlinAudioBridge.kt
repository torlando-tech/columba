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
@Suppress("TooManyFunctions")
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
    private var speakerphoneOn = false // Default to earpiece

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

            // Log AudioTrack state
            val track = audioTrack
            Log.i(TAG, "📞 AudioTrack created: state=${track?.state} (INITIALIZED=${AudioTrack.STATE_INITIALIZED})")
            Log.i(TAG, "📞 AudioTrack: sampleRate=${track?.sampleRate}, channelCount=${track?.channelCount}, bufferSize=$bufferSize")

            // Set audio mode for voice communication
            // Note: MODE_IN_COMMUNICATION enables earpiece by default
            val previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "📞 Audio mode: $previousMode -> ${audioManager.mode} (MODE_IN_COMMUNICATION=${AudioManager.MODE_IN_COMMUNICATION})")

            // Log volume levels BEFORE setting speaker
            val streamVoiceCall = AudioManager.STREAM_VOICE_CALL
            val currentVol = audioManager.getStreamVolume(streamVoiceCall)
            val maxVol = audioManager.getStreamMaxVolume(streamVoiceCall)
            Log.i(TAG, "📞 Voice call volume: $currentVol / $maxVol")

            // Apply audio routing using modern API on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setCommunicationDeviceByType(speakerphoneOn)
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerphoneOn
                Log.i(TAG, "📞 Legacy speakerphone set to: ${audioManager.isSpeakerphoneOn}")
            }

            // Log available output devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                Log.i(TAG, "📞 Available outputs: ${outputs.map { "${it.type}:${it.productName}" }}")
            }

            audioTrack?.play()
            val playState = audioTrack?.playState
            Log.i(TAG, "📞 AudioTrack.play() called, playState=$playState (PLAYING=${AudioTrack.PLAYSTATE_PLAYING})")
            isPlaying.set(true)
            Log.i(TAG, "Playback started successfully, speaker=${audioManager.isSpeakerphoneOn}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onPlaybackError?.callAttr("__call__", e.message ?: "Unknown error")
        }
    }

    private var writeAudioCount = 0L
    private var writeNonZeroCount = 0L

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
            writeAudioCount++
            // Check if data has non-zero samples
            var hasNonZero = false
            for (i in 0 until audioData.size step 2) {
                if (i + 1 < audioData.size) {
                    val sample = (audioData[i].toInt() and 0xFF) or (audioData[i + 1].toInt() shl 8)
                    if (sample != 0 && sample != -1) {
                        hasNonZero = true
                        break
                    }
                }
            }
            if (hasNonZero) writeNonZeroCount++

            if (writeAudioCount % 100L == 1L) {
                val trackState = track.playState
                Log.d(TAG, "📞 writeAudio #$writeAudioCount: ${audioData.size} bytes, nonzero=$writeNonZeroCount, hasAudio=$hasNonZero, trackState=$trackState")
            }

            val written =
                track.write(
                    audioData,
                    0,
                    audioData.size,
                    AudioTrack.WRITE_BLOCKING,
                )
            if (written < 0) {
                Log.w(
                    TAG,
                    "📞 AudioTrack write error: $written (ERROR_INVALID_OPERATION=${AudioTrack.ERROR_INVALID_OPERATION}, ERROR_BAD_VALUE=${AudioTrack.ERROR_BAD_VALUE})",
                )
            } else if (writeAudioCount % 100L == 1L) {
                Log.d(TAG, "📞 writeAudio #$writeAudioCount: wrote $written bytes successfully")
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

            // Clear communication device on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "📞 Communication device cleared")
            }

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

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBufferSize = calculateMinBufferSize(sampleRate, channelConfig) ?: return

        try {
            ensureAudioModeForRecording()
            val record = createAudioRecord(sampleRate, channelConfig, minBufferSize, samplesPerFrame, channels) ?: return
            audioRecord = record
            configurePreferredInputDevice(record)
            startRecordingAndLaunchLoop(record)
            Log.i(TAG, "Recording started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onRecordingError?.callAttr("__call__", e.message ?: "Unknown error")
        }
    }

    /** Calculate minimum buffer size for AudioRecord, returns null on error. */
    private fun calculateMinBufferSize(
        sampleRate: Int,
        channelConfig: Int,
    ): Int? {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            onRecordingError?.callAttr("__call__", "Invalid buffer size: $bufferSize")
            return null
        }
        return bufferSize
    }

    /**
     * Set audio mode before creating AudioRecord.
     * On Samsung devices, mic routing depends on mode being set first.
     */
    private fun ensureAudioModeForRecording() {
        val previousMode = audioManager.mode
        if (previousMode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.i(TAG, "📞 Pre-recording: Audio mode $previousMode -> ${audioManager.mode}")
        }
    }

    /** Create and initialize AudioRecord, returns null on error. */
    private fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        minBufferSize: Int,
        samplesPerFrame: Int,
        channels: Int,
    ): AudioRecord? {
        // Use MIC source - VOICE_COMMUNICATION may not be available on all devices
        // The key fix is setting MODE_IN_COMMUNICATION before creating AudioRecord
        val audioSource = MediaRecorder.AudioSource.MIC
        Log.i(TAG, "📞 Using audio source: $audioSource (MIC=${MediaRecorder.AudioSource.MIC}, VOICE_COMM=${MediaRecorder.AudioSource.VOICE_COMMUNICATION})")

        val record =
            AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                // At least 2 frames buffer
                maxOf(minBufferSize, samplesPerFrame * channels * 2 * 2),
            )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize, state=${record.state}")
            onRecordingError?.callAttr("__call__", "AudioRecord failed to initialize")
            record.release()
            return null
        }
        return record
    }

    /** On Android 6+, set preferred input device to built-in mic. */
    private fun configurePreferredInputDevice(record: AudioRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val builtinMic = inputDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtinMic != null) {
                val success = record.setPreferredDevice(builtinMic)
                Log.i(TAG, "📞 Set preferred input device to ${builtinMic.productName}: $success")
            } else {
                Log.w(TAG, "📞 No built-in mic found in ${inputDevices.map { "${it.type}:${it.productName}" }}")
            }
        }
    }

    /** Start the AudioRecord and launch the recording loop coroutine. */
    private fun startRecordingAndLaunchLoop(record: AudioRecord) {
        record.startRecording()
        Log.i(TAG, "📞 AudioRecord state after startRecording: ${record.recordingState} (RECORDSTATE_RECORDING=${AudioRecord.RECORDSTATE_RECORDING})")

        logAudioRoutingState()

        // Check and fix mic mute state
        if (audioManager.isMicrophoneMute) {
            Log.w(TAG, "📞 System mic was MUTED! Unmuting...")
            audioManager.isMicrophoneMute = false
        }
        Log.i(TAG, "📞 System mic mute: ${audioManager.isMicrophoneMute}, mode: ${audioManager.mode}")

        isRecording.set(true)
        recordBuffer.clear()

        // Start background recording thread
        scope.launch {
            recordingLoop()
        }
    }

    /** Log audio routing state for debugging (Android S+). */
    private fun logAudioRoutingState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            Log.i(TAG, "📞 Communication device for recording: ${commDevice?.productName} (type=${commDevice?.type})")
            val availableInputs = audioManager.availableCommunicationDevices.filter { !it.isSink }
            Log.i(TAG, "📞 Available input devices: ${availableInputs.map { "${it.type}:${it.productName}" }}")
        }
    }

    /**
     * Background recording loop.
     *
     * Continuously reads from AudioRecord and queues frames for Python to consume.
     */
    private var recordFrameCount = 0L
    private var recordNonZeroCount = 0L

    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun recordingLoop() {
        val frameBytes = recordFrameSize * recordChannels * 2 // 16-bit = 2 bytes per sample
        val buffer = ByteArray(frameBytes)
        recordFrameCount = 0
        recordNonZeroCount = 0

        Log.d(TAG, "Recording loop started, frame size: $frameBytes bytes")

        while (isRecording.get()) {
            val record = audioRecord ?: break

            try {
                val bytesRead = record.read(buffer, 0, frameBytes)

                when {
                    bytesRead > 0 -> {
                        recordFrameCount++
                        // Check if frame has any non-zero samples and find max amplitude
                        var hasNonZero = false
                        var maxSample = 0
                        for (i in 0 until bytesRead step 2) {
                            if (i + 1 < bytesRead) {
                                // Read as signed int16
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val signedSample = if (sample > 32767) sample - 65536 else sample
                                val absSample = kotlin.math.abs(signedSample)
                                if (absSample > maxSample) maxSample = absSample
                                if (absSample > 10) { // Small threshold to ignore noise floor
                                    hasNonZero = true
                                }
                            }
                        }
                        if (hasNonZero) recordNonZeroCount++

                        // Log periodically with max amplitude
                        if (recordFrameCount % 100L == 1L) {
                            Log.d(TAG, "📞 Recording: frame #$recordFrameCount, nonzero=$recordNonZeroCount, bytes=$bytesRead, maxAmp=$maxSample")
                        }

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
     * Returns silence (zeros) when microphone is muted.
     *
     * @param numSamples Number of samples to read (ignored, returns one frame)
     * @return Audio data as byte array, or null if no data available
     */
    private var readAudioCount = 0L
    private var lastMuteState = false

    @Suppress("UnusedParameter")
    fun readAudio(numSamples: Int): ByteArray? {
        if (!isRecording.get()) {
            return null
        }

        return try {
            // Wait up to 50ms for data (allows for ~20ms frames)
            val data = recordBuffer.poll(50, TimeUnit.MILLISECONDS)

            readAudioCount++

            // Log on mute state change or every 100 frames
            val muteStateChanged = microphoneMuted != lastMuteState
            if (muteStateChanged) {
                Log.i(TAG, "📞 readAudio: MUTE STATE CHANGED from $lastMuteState to $microphoneMuted at frame #$readAudioCount")
                lastMuteState = microphoneMuted
            }

            if (readAudioCount % 100L == 1L || muteStateChanged) {
                val size = data?.size ?: 0
                val queueSize = recordBuffer.size
                Log.d(TAG, "📞 readAudio #$readAudioCount: ${if (data != null) "$size bytes" else "null"}, queue=$queueSize, muted=$microphoneMuted")
            }

            // NOTE: We do NOT do software mute here anymore.
            // LXST's transmit_mixer.mute() handles muting at the codec level,
            // which properly stops packet transmission without causing
            // "No interfaces could process the outbound packet" errors.
            data
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
     * Uses setCommunicationDevice() on Android 12+ (deprecated setSpeakerphoneOn on older).
     *
     * @param enabled True for speaker, false for earpiece
     */
    @Suppress("DEPRECATION")
    fun setSpeakerphoneOn(enabled: Boolean) {
        speakerphoneOn = enabled
        Log.i(TAG, "📞 setSpeakerphoneOn($enabled)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Use modern setCommunicationDevice API
            setCommunicationDeviceByType(enabled)
        } else {
            // Legacy API for older Android versions
            audioManager.isSpeakerphoneOn = enabled
            Log.d(TAG, "📞 Legacy speakerphone set: $enabled")
        }
    }

    /**
     * Set communication device by type (Android 12+).
     *
     * @param useSpeaker True for speaker, false for earpiece
     */
    @android.annotation.SuppressLint("NewApi")
    private fun setCommunicationDeviceByType(useSpeaker: Boolean) {
        try {
            val availableDevices = audioManager.availableCommunicationDevices
            Log.i(TAG, "📞 Available communication devices: ${availableDevices.map { "${it.type}:${it.productName}" }}")

            val targetType =
                if (useSpeaker) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }

            val targetDevice = availableDevices.find { it.type == targetType }

            if (targetDevice != null) {
                val success = audioManager.setCommunicationDevice(targetDevice)
                Log.i(TAG, "📞 setCommunicationDevice(${targetDevice.productName}, type=$targetType): success=$success")

                // Verify the change
                val currentDevice = audioManager.communicationDevice
                Log.i(TAG, "📞 Current communication device: ${currentDevice?.productName} (type=${currentDevice?.type})")
            } else {
                Log.w(TAG, "📞 Target device type $targetType not found in available devices")
                // Fall back to legacy API
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = useSpeaker
                Log.d(TAG, "📞 Fallback to legacy speakerphone: $useSpeaker")
            }
        } catch (e: Exception) {
            Log.e(TAG, "📞 Error setting communication device", e)
            // Fall back to legacy API
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = useSpeaker
        }
    }

    /**
     * Mute or unmute the microphone.
     *
     * Uses software mute (returning silence in readAudio) for reliability,
     * plus system-level mute as backup.
     *
     * @param muted True to mute, false to unmute
     */
    fun setMicrophoneMute(muted: Boolean) {
        val previousState = microphoneMuted
        Log.i(TAG, "📞 setMicrophoneMute: $previousState -> $muted")
        microphoneMuted = muted
        Log.i(TAG, "📞 Microphone mute now: $microphoneMuted (software mute active)")
        // Also try system-level mute (may not work on all devices)
        try {
            audioManager.isMicrophoneMute = muted
            Log.d(TAG, "📞 System mic mute set to: ${audioManager.isMicrophoneMute}")
        } catch (e: Exception) {
            Log.w(TAG, "System microphone mute not available: ${e.message}")
        }
    }

    /**
     * Check if speakerphone is enabled.
     */
    @Suppress("DEPRECATION")
    fun isSpeakerphoneOn(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            audioManager.isSpeakerphoneOn
        }
    }

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
