package tech.torlando.lxst.telephone

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import tech.torlando.lxst.bridge.KotlinAudioBridge
import tech.torlando.lxst.bridge.NetworkPacketBridge
import tech.torlando.lxst.audio.LinkSource
import tech.torlando.lxst.audio.LineSource
import tech.torlando.lxst.audio.LineSink
import tech.torlando.lxst.audio.LocalSink
import tech.torlando.lxst.audio.Mixer
import tech.torlando.lxst.audio.Packetizer
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.audio.Source
import tech.torlando.lxst.audio.ToneSource
import tech.torlando.lxst.bridge.CallBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Telephone - Main call lifecycle controller for LXST telephony.
 *
 * Matches Python LXST Telephony.py Telephone class (lines 114-732).
 * Kotlin owns the state machine; Python handles Reticulum network transport.
 *
 * **Architecture (from CONTEXT.md):**
 * - Kotlin drives state transitions
 * - Profiles defined in Kotlin only (single source of truth)
 * - NetworkTransport interface abstracts Python bridge
 * - Telephone does NOT import PyObject directly
 *
 * **State Machine (matches Python exactly):**
 * - STATUS_AVAILABLE (0x03) - Idle, ready for calls
 * - STATUS_CALLING (0x02) - Initiating outgoing call
 * - STATUS_RINGING (0x04) - Remote is ringing / incoming call
 * - STATUS_CONNECTING (0x05) - Setting up audio pipelines
 * - STATUS_ESTABLISHED (0x06) - Call active, audio flowing
 * - STATUS_BUSY (0x00) - Remote busy
 * - STATUS_REJECTED (0x01) - Call rejected
 *
 * @param context Application context for system services (RingtoneManager)
 * @param networkTransport NetworkTransport implementation for network ops
 * @param audioBridge KotlinAudioBridge for audio capture/playback
 * @param networkPacketBridge NetworkPacketBridge for packet transfer
 * @param callBridge CallBridge for UI state synchronization
 * @param ringTime Maximum ring time in milliseconds (default 60s)
 * @param waitTime Maximum wait time for outgoing calls in milliseconds (default 70s)
 */
class Telephone(
    private val context: Context,
    private val networkTransport: NetworkTransport,
    private val audioBridge: KotlinAudioBridge,
    private val networkPacketBridge: NetworkPacketBridge,
    private val callBridge: CallBridge,
    private val ringTime: Long = RING_TIME_MS,
    private val waitTime: Long = WAIT_TIME_MS
) {
    companion object {
        private const val TAG = "Columba:Telephone"

        // Timing constants (match Python LXST Telephony.py lines 115-117)
        const val RING_TIME_MS = 60_000L
        const val WAIT_TIME_MS = 70_000L
        const val CONNECT_TIME_MS = 5_000L

        // Dial tone parameters (match Python LXST Telephony.py lines 118-119)
        const val DIAL_TONE_FREQUENCY = 382f
        const val DIAL_TONE_EASE_MS = 3.14159f
        const val DIAL_TONE_GAIN = 0.04f
        const val BUSY_TONE_SECONDS = 4.25f
    }

    // ===== State (matches Python Telephony.py lines 159-180) =====

    /** Current call status (Signalling.STATUS_*) */
    @Volatile
    var callStatus: Int = Signalling.STATUS_AVAILABLE
        private set

    /** Active quality profile */
    @Volatile
    var activeProfile: Profile = Profile.DEFAULT
        private set

    /** Transmit mute state (persists across profile switches - CONTEXT.md) */
    @Volatile
    private var transmitMuted = false

    /** Receive mute state */
    @Volatile
    private var receiveMuted = false

    /** True if current call is incoming */
    @Volatile
    private var isIncomingCall = false

    /** Remote identity hash (hex string) for current call */
    @Volatile
    private var remoteIdentityHash: String? = null

    // ===== Audio Pipeline Components (null when no call) =====

    private var receiveMixer: Mixer? = null
    private var transmitMixer: Mixer? = null
    private var receiveMixerAsSink: MixerSinkAdapter? = null  // Adapter for sources to push to Mixer
    private var transmitMixerAsSink: MixerSinkAdapter? = null // Adapter for sources to push to Mixer
    private var audioInput: LineSource? = null
    private var audioOutput: LineSink? = null
    private var linkSource: LinkSource? = null
    private var packetizer: Packetizer? = null
    private var dialTone: ToneSource? = null

    // ===== Ringtone Components =====

    /** Use system ringtone (default true) or ToneSource pattern (false) */
    private var useSystemRingtone: Boolean = true

    /** Custom ringtone path (null = use default system ringtone) */
    private var customRingtonePath: String? = null

    /** Android Ringtone for system ringtone playback */
    private var systemRingtone: Ringtone? = null

    /** Separate mixer for ringtone (independent of call pipeline) */
    private var ringerMixer: Mixer? = null

    /** Separate sink for ringtone output */
    private var ringerSink: LineSink? = null

    /** ToneSource for ringtone when not using system ringtone */
    private var ringTone: ToneSource? = null

    /** Job controlling ring tone pattern loop */
    private var ringToneJob: Job? = null

    // ===== Coroutine Management =====

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var dialToneJob: Job? = null
    private var timeoutJob: Job? = null

    init {
        // Wire up signal callback to handle incoming signals
        networkTransport.setSignalCallback { signal ->
            onSignalReceived(signal)
        }
    }

    // ===== Public API =====

    /**
     * Initiate an outgoing call.
     *
     * Matches Python Telephony.py call() (lines 648-673).
     *
     * @param destinationHash 16-byte Reticulum destination hash
     * @param profile Quality profile for the call (default: Profile.MQ)
     */
    suspend fun call(destinationHash: ByteArray, profile: Profile = Profile.DEFAULT) {
        if (isCallActive()) {
            Log.w(TAG, "Already in call, ignoring")
            return
        }

        Log.i(TAG, "Initiating call to ${destinationHash.toHexString().take(16)}...")

        callStatus = Signalling.STATUS_CALLING
        activeProfile = profile
        isIncomingCall = false
        remoteIdentityHash = destinationHash.toHexString()

        // Notify UI
        callBridge.setConnecting(remoteIdentityHash!!)

        // Start outgoing call timeout
        timeoutJob = scope.launch {
            delay(waitTime)
            if (callStatus < Signalling.STATUS_ESTABLISHED) {
                Log.w(TAG, "Outgoing call timeout after ${waitTime}ms")
                hangup()
            }
        }

        // Establish link via NetworkTransport
        val linkEstablished = withTimeoutOrNull(waitTime) {
            networkTransport.establishLink(destinationHash)
        }

        if (linkEstablished != true) {
            Log.w(TAG, "Link establishment failed or timed out")
            hangup()
            return
        }

        // Link established, wait for signalling to proceed
        // Python will send STATUS_AVAILABLE, then we identify, etc.
    }

    /**
     * Answer an incoming call.
     *
     * Matches Python Telephony.py answer() (lines 404-423).
     */
    fun answer(): Boolean {
        if (!isIncomingCall || callStatus != Signalling.STATUS_RINGING) {
            Log.w(TAG, "Cannot answer: no incoming call or not ringing " +
                "(isIncoming=$isIncomingCall, status=$callStatus)")
            return false
        }

        Log.i(TAG, "Answering call from ${remoteIdentityHash?.take(16)}...")

        // Open and start pipelines
        openPipelines()
        startPipelines()

        // Disable ring tone / dial tone
        stopRingTone()
        disableDialTone()

        // Confirm our profile to caller so both sides agree
        sendProfilePreference()

        // Signal established to remote
        callStatus = Signalling.STATUS_ESTABLISHED
        networkTransport.sendSignal(Signalling.STATUS_ESTABLISHED)

        // Notify UI
        remoteIdentityHash?.let { callBridge.onCallEstablished(it) }
        return true
    }

    /**
     * Hang up the current call.
     *
     * Matches Python Telephony.py hangup() (lines 425-456).
     *
     * @param reason Optional reason code (STATUS_BUSY, STATUS_REJECTED)
     */
    fun hangup(reason: Int? = null) {
        Log.i(TAG, "Hanging up call (reason=$reason)")

        // Cancel timeout
        timeoutJob?.cancel()
        timeoutJob = null

        // If incoming and not answered, signal rejection
        if (isIncomingCall && callStatus == Signalling.STATUS_RINGING && reason == null) {
            networkTransport.sendSignal(Signalling.STATUS_REJECTED)
        }

        // Tear down link
        networkTransport.teardownLink()

        // Stop all pipelines
        stopPipelines()

        // Stop all tones
        stopRingTone()
        disableDialTone()

        // Clear pipeline references
        receiveMixer = null
        transmitMixer = null
        receiveMixerAsSink = null
        transmitMixerAsSink = null
        audioInput = null
        audioOutput = null
        linkSource = null
        packetizer = null
        dialTone = null
        dialToneJob?.cancel()
        dialToneJob = null

        // Clear ringtone references
        ringerMixer = null
        ringerSink = null
        ringTone = null

        // Reset state
        val previousIdentity = remoteIdentityHash
        callStatus = Signalling.STATUS_AVAILABLE
        activeProfile = Profile.DEFAULT
        transmitMuted = false
        receiveMuted = false
        isIncomingCall = false
        remoteIdentityHash = null

        // Notify UI based on reason
        when (reason) {
            Signalling.STATUS_BUSY -> callBridge.onCallBusy()
            Signalling.STATUS_REJECTED -> callBridge.onCallRejected()
            else -> callBridge.onCallEnded(previousIdentity)
        }
    }

    /**
     * Switch quality profile during active call.
     *
     * Matches Python Telephony.py switch_profile() (lines 482-491).
     * Mute state persists across profile switches (CONTEXT.md requirement).
     *
     * @param profile New profile to switch to
     */
    fun switchProfile(profile: Profile) {
        if (activeProfile == profile) {
            Log.d(TAG, "Already using profile ${profile.abbreviation}, ignoring")
            return
        }

        if (callStatus != Signalling.STATUS_ESTABLISHED) {
            Log.w(TAG, "Cannot switch profile: call not established (status=$callStatus)")
            return
        }

        Log.i(TAG, "Switching profile from ${activeProfile.abbreviation} to ${profile.abbreviation}")

        activeProfile = profile

        // Signal profile change to remote
        val profileSignal = Signalling.PREFERRED_PROFILE + profile.id
        networkTransport.sendSignal(profileSignal)

        // Reconfigure transmit pipeline with new codec
        reconfigureTransmitPipeline()
    }

    /**
     * Mute or unmute transmit (microphone).
     *
     * Matches Python Telephony.py mute_transmit() (lines 466-468).
     *
     * @param mute True to mute, false to unmute
     */
    fun muteTransmit(mute: Boolean = true) {
        Log.d(TAG, "Transmit mute: $mute")
        transmitMuted = mute
        transmitMixer?.mute(mute)
    }

    /**
     * Mute or unmute receive (speaker).
     *
     * Matches Python Telephony.py mute_receive() (lines 458-460).
     *
     * @param mute True to mute, false to unmute
     */
    fun muteReceive(mute: Boolean = true) {
        Log.d(TAG, "Receive mute: $mute")
        receiveMuted = mute
        receiveMixer?.mute(mute)
    }

    /**
     * Check if transmit is muted.
     */
    fun isTransmitMuted(): Boolean = transmitMuted

    /**
     * Check if receive is muted.
     */
    fun isReceiveMuted(): Boolean = receiveMuted

    /**
     * Check if there's an active or pending call.
     */
    fun isCallActive(): Boolean {
        return callStatus != Signalling.STATUS_AVAILABLE
    }

    // ===== Ringtone Configuration =====

    /**
     * Configure ringtone to use for incoming calls.
     *
     * @param path Path to custom ringtone, or null to use system default
     */
    fun setRingtone(path: String?) {
        customRingtonePath = path
        if (path == null) {
            Log.d(TAG, "Using system default ringtone")
        } else {
            Log.d(TAG, "Using custom ringtone: $path")
        }
    }

    /**
     * Toggle between system ringtone and ToneSource pattern.
     *
     * @param use True to use system ringtone (default), false for ToneSource pattern
     */
    fun setUseSystemRingtone(use: Boolean) {
        useSystemRingtone = use
        Log.d(TAG, "Use system ringtone: $use")
    }

    // ===== Signal Handling (matches Python Telephony.py signalling_received lines 683-729) =====

    /**
     * Handle signal received from remote peer.
     *
     * Parses signal type and updates state machine accordingly.
     *
     * IMPORTANT: @Synchronized because Python sends signals from Chaquopy
     * threads. CONNECTING (0x05) and ESTABLISHED (0x06) can arrive 4ms apart
     * on different threads. Without synchronization, startPipelines() races
     * ahead of openPipelines(), leaving pipeline components created but never
     * started — no audio flows.
     */
    @Synchronized
    private fun onSignalReceived(signal: Int) {
        Log.d(TAG, "Signal received: 0x${signal.toString(16)} (status=$callStatus)")

        when {
            signal == Signalling.STATUS_BUSY -> {
                Log.d(TAG, "Remote is busy")
                scope.launch {
                    playBusyTone()
                    disableDialTone()
                    hangup(reason = Signalling.STATUS_BUSY)
                }
            }

            signal == Signalling.STATUS_REJECTED -> {
                Log.d(TAG, "Remote rejected call")
                scope.launch {
                    playBusyTone()
                    disableDialTone()
                    hangup(reason = Signalling.STATUS_REJECTED)
                }
            }

            signal == Signalling.STATUS_AVAILABLE -> {
                // Matches Python __available(): if in active call, treat as hangup
                if (callStatus > Signalling.STATUS_AVAILABLE) {
                    Log.d(TAG, "Line available during active call, ending call")
                    hangup()
                } else {
                    Log.d(TAG, "Line available")
                    callStatus = signal
                }
            }

            signal == Signalling.STATUS_RINGING -> {
                Log.d(TAG, "Remote is ringing")
                callStatus = signal

                if (!isIncomingCall) {
                    // Outgoing call: remote is ringing
                    prepareDiallingPipelines()
                    sendProfilePreference()
                    activateDialTone()
                    // Notify UI
                    remoteIdentityHash?.let { callBridge.onCallRinging(it) }
                } else {
                    // Incoming call: we are ringing
                    prepareDiallingPipelines()
                    activateRingTone()
                }
            }

            signal == Signalling.STATUS_CONNECTING -> {
                Log.d(TAG, "Call answered, remote setting up")
                callStatus = signal
                resetDiallingPipelines()
                openPipelines()
            }

            signal == Signalling.STATUS_ESTABLISHED -> {
                if (!isIncomingCall) {
                    // Outgoing call: remote setup complete
                    Log.d(TAG, "Call established")
                    timeoutJob?.cancel()
                    timeoutJob = null
                    disableDialTone()
                    openPipelines()  // Ensure pipelines created (idempotent)
                    startPipelines()
                    callStatus = signal
                    remoteIdentityHash?.let { callBridge.onCallEstablished(it) }
                }
            }

            signal >= Signalling.PREFERRED_PROFILE -> {
                // Profile change from remote (matches Python line 726 comparison)
                val profileId = signal - Signalling.PREFERRED_PROFILE
                Log.d(TAG, "Profile change signal: 0x${profileId.toString(16)}")

                Profile.fromId(profileId)?.let { profile ->
                    if (callStatus == Signalling.STATUS_ESTABLISHED) {
                        // Mid-call profile switch
                        switchProfileFromRemote(profile)
                    } else {
                        // Pre-established: select profile for call setup
                        selectCallProfile(profile)
                    }
                }
            }
        }
    }

    /**
     * Handle incoming call notification.
     *
     * Called by external code (e.g., PythonNetworkTransport wrapper) when
     * Python LXST detects an incoming call.
     *
     * @param identityHash Hex string of caller's identity hash
     */
    fun onIncomingCall(identityHash: String) {
        Log.i(TAG, "Incoming call from ${identityHash.take(16)}...")

        if (isCallActive()) {
            Log.w(TAG, "Already in call, signalling busy")
            networkTransport.sendSignal(Signalling.STATUS_BUSY)
            return
        }

        isIncomingCall = true
        remoteIdentityHash = identityHash
        callStatus = Signalling.STATUS_RINGING

        // Prepare for ringing
        prepareDiallingPipelines()

        // Note: Python call_manager already sends STATUS_RINGING to remote
        // before calling this method. No need to send it again.

        // Activate ring tone
        activateRingTone()

        // Notify UI
        callBridge.onIncomingCall(identityHash)

        // Start ring timeout
        timeoutJob = scope.launch {
            delay(ringTime)
            if (callStatus == Signalling.STATUS_RINGING) {
                Log.w(TAG, "Ring timeout after ${ringTime}ms")
                hangup()
            }
        }
    }

    /**
     * Set up minimal state so answer() can succeed.
     *
     * Used by the service binder as a fallback when the Python callback
     * (_notify_kotlin) fails to reach onIncomingCall(). Unlike onIncomingCall(),
     * this does NOT activate ringtone, notify CallBridge, or start the ring
     * timeout — those have already happened through Python's direct CallBridge call.
     */
    fun prepareForAnswer(identityHash: String) {
        if (isCallActive()) {
            Log.w(TAG, "Cannot prepareForAnswer: already in call")
            return
        }
        Log.i(TAG, "prepareForAnswer: setting up state for ${identityHash.take(16)}...")
        isIncomingCall = true
        remoteIdentityHash = identityHash
        callStatus = Signalling.STATUS_RINGING
    }

    // ===== Pipeline Management (matches Python Telephony.py) =====

    /**
     * Prepare dialling pipelines (dial tone playback).
     *
     * Matches Python __prepare_dialling_pipelines() (lines 519-524).
     */
    private fun prepareDiallingPipelines() {
        Log.d(TAG, "Preparing dialling pipelines")

        if (audioOutput == null) {
            audioOutput = LineSink(bridge = audioBridge)
        }

        if (receiveMixer == null) {
            receiveMixer = Mixer(
                targetFrameMs = activeProfile.frameTimeMs,
                sink = audioOutput
            )
            receiveMixerAsSink = MixerSinkAdapter(receiveMixer!!)
        }

        if (dialTone == null) {
            dialTone = ToneSource(
                frequency = DIAL_TONE_FREQUENCY,
                targetGain = 0f, // Start silent
                ease = true,
                easeTimeMs = DIAL_TONE_EASE_MS,
                targetFrameMs = activeProfile.frameTimeMs
            ).apply {
                sink = receiveMixerAsSink
            }
        }
    }

    /**
     * Reset dialling pipelines (stop and recreate).
     *
     * Matches Python __reset_dialling_pipelines() (lines 507-517).
     */
    private fun resetDiallingPipelines() {
        Log.d(TAG, "Resetting dialling pipelines")

        audioOutput?.release()  // release() prevents auto-restart from stale mixer frames
        dialTone?.stop()
        receiveMixer?.stop()

        audioOutput = null
        dialTone = null
        receiveMixer = null
        receiveMixerAsSink = null

        prepareDiallingPipelines()
    }

    /**
     * Open full audio pipelines for call.
     *
     * Matches Python __open_pipelines() (lines 596-624).
     */
    private fun openPipelines() {
        Log.d(TAG, "Opening audio pipelines")

        // Ensure dialling pipelines exist
        prepareDiallingPipelines()

        // Create packetizer (transmit to network)
        if (packetizer == null) {
            packetizer = Packetizer(
                bridge = networkPacketBridge,
                failureCallback = { onPacketizerFailure() }
            ).apply {
                codec = activeProfile.createCodec()
            }
        }

        // Create transmit mixer
        if (transmitMixer == null) {
            transmitMixer = Mixer(
                targetFrameMs = activeProfile.frameTimeMs,
                codec = activeProfile.createCodec(),
                sink = packetizer
            )
            transmitMixerAsSink = MixerSinkAdapter(transmitMixer!!)
        }

        // Create audio input (microphone)
        if (audioInput == null) {
            audioInput = LineSource(
                bridge = audioBridge,
                codec = activeProfile.createCodec(),
                targetFrameMs = activeProfile.frameTimeMs
            ).apply {
                sink = transmitMixerAsSink
            }
        }

        // Create link source (receive from network)
        if (linkSource == null) {
            val decodeCodec = activeProfile.createDecodeCodec()
            val decodeRate = decodeCodec.preferredSamplerate ?: 48000
            val decodeChannels = decodeCodec.codecChannels

            linkSource = LinkSource(
                bridge = networkPacketBridge,
                sink = receiveMixerAsSink
            ).apply {
                codec = decodeCodec
                sampleRate = decodeRate
                channels = decodeChannels
            }

            // Reconfigure audio output for decode rate and channels. The dial tone
            // may have set LineSink to 48kHz mono; decoded Opus audio may be at a
            // different rate or stereo (e.g., SHQ profile). Stop and reconfigure so
            // AudioTrack is created with the correct config on auto-start.
            audioOutput?.let { sink ->
                if (sink.isRunning()) sink.stop()
                sink.configure(decodeRate, decodeChannels)
            }
        }

        // Signal connecting to remote (for incoming calls)
        if (isIncomingCall) {
            networkTransport.sendSignal(Signalling.STATUS_CONNECTING)
        }
    }

    /**
     * Start all audio pipelines.
     *
     * Matches Python __start_pipelines() (lines 630-637).
     */
    private fun startPipelines() {
        Log.d(TAG, "Starting audio pipelines")

        receiveMixer?.start()
        transmitMixer?.start()
        audioInput?.start()
        linkSource?.start()
        packetizer?.start()

        Log.i(TAG, "Audio pipelines started")
    }

    /**
     * Stop all audio pipelines.
     *
     * Matches Python __stop_pipelines() (lines 639-646).
     */
    private fun stopPipelines() {
        Log.d(TAG, "Stopping audio pipelines")

        receiveMixer?.stop()
        transmitMixer?.stop()
        audioInput?.stop()
        linkSource?.stop()
        packetizer?.stop()
        dialTone?.stop()

        Log.d(TAG, "Audio pipelines stopped")
    }

    /**
     * Reconfigure transmit pipeline with new profile codec.
     *
     * Matches Python __reconfigure_transmit_pipeline() (lines 579-594).
     * Preserves mute state across reconfiguration (CONTEXT.md requirement).
     */
    private fun reconfigureTransmitPipeline() {
        Log.d(TAG, "Reconfiguring transmit pipeline for ${activeProfile.abbreviation}")

        val wasMuted = transmitMuted

        // Stop current transmit path
        audioInput?.stop()
        transmitMixer?.stop()

        // Create new transmit mixer with new codec
        transmitMixer = Mixer(
            targetFrameMs = activeProfile.frameTimeMs,
            codec = activeProfile.createCodec(),
            sink = packetizer
        )
        transmitMixerAsSink = MixerSinkAdapter(transmitMixer!!)

        // Create new audio input with new codec
        audioInput = LineSource(
            bridge = audioBridge,
            codec = activeProfile.createCodec(),
            targetFrameMs = activeProfile.frameTimeMs
        ).apply {
            sink = transmitMixerAsSink
        }

        // Update packetizer codec
        packetizer?.codec = activeProfile.createCodec()

        // Restore mute state (CONTEXT.md: mute persists across profile switches)
        transmitMixer?.mute(wasMuted)

        // Start new pipeline
        transmitMixer?.start()
        audioInput?.start()

        Log.d(TAG, "Transmit pipeline reconfigured, mute=$wasMuted")
    }

    // ===== Dial Tone / Ring Tone =====

    /**
     * Activate dial tone for outgoing call.
     *
     * Matches Python __activate_dial_tone() (lines 553-563).
     * Pattern: 2 seconds on, 5 seconds off (7 second window).
     */
    private fun activateDialTone() {
        Log.d(TAG, "Activating dial tone")

        dialToneJob?.cancel()
        dialToneJob = scope.launch {
            val windowMs = 7000L
            val started = System.currentTimeMillis()

            while (callStatus == Signalling.STATUS_RINGING && !isIncomingCall) {
                val elapsed = (System.currentTimeMillis() - started) % windowMs
                if (elapsed > 50 && elapsed < 2050) {
                    enableDialTone()
                } else {
                    muteDialTone()
                }
                delay(200)
            }
        }
    }

    /**
     * Activate ring tone for incoming call.
     *
     * Uses system ringtone by default, falls back to ToneSource pattern.
     * Matches Python Telephony.py lines 526-539.
     */
    private fun activateRingTone() {
        Log.d(TAG, "Activating ring tone (useSystemRingtone=$useSystemRingtone)")

        if (useSystemRingtone) {
            // Use Android RingtoneManager
            scope.launch(Dispatchers.Main) {
                try {
                    val uri = if (customRingtonePath != null) {
                        android.net.Uri.parse(customRingtonePath)
                    } else {
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    }
                    systemRingtone = RingtoneManager.getRingtone(context, uri)
                    systemRingtone?.play()
                    Log.d(TAG, "System ringtone started")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to play system ringtone, using fallback tone", e)
                    playToneRingPattern()
                }
            }
        } else {
            // Use ToneSource pattern
            playToneRingPattern()
        }
    }

    /**
     * Play ring tone pattern using ToneSource.
     *
     * Pattern: 2 seconds on, 4 seconds off (standard telephone ring).
     * Used as fallback when system ringtone unavailable.
     */
    private fun playToneRingPattern() {
        Log.d(TAG, "Playing tone ring pattern")

        ringToneJob?.cancel()
        ringToneJob = scope.launch {
            // Create ringer pipeline if needed (separate from main call pipeline)
            if (ringerSink == null) {
                ringerSink = LineSink(bridge = audioBridge)
            }
            if (ringerMixer == null) {
                ringerMixer = Mixer(
                    targetFrameMs = 60,
                    sink = ringerSink
                )
            }
            if (ringTone == null) {
                val ringerMixerAsSink = MixerSinkAdapter(ringerMixer!!)
                ringTone = ToneSource(
                    frequency = DIAL_TONE_FREQUENCY,
                    targetGain = 0.1f,  // Louder than dial tone for ringtone
                    ease = true,
                    easeTimeMs = DIAL_TONE_EASE_MS
                ).apply {
                    sink = ringerMixerAsSink
                }
            }

            // Ring pattern: 2s on, 4s off (standard telephone ring)
            while (isIncomingCall && callStatus == Signalling.STATUS_RINGING) {
                ringerMixer?.start()
                ringTone?.start()
                delay(2000)
                ringTone?.stop()
                delay(4000)
            }

            // Cleanup when loop exits
            ringTone?.stop()
            ringerMixer?.stop()
        }
    }

    /**
     * Stop ring tone (both system ringtone and ToneSource pattern).
     *
     * Called on answer() or hangup().
     */
    private fun stopRingTone() {
        Log.d(TAG, "Stopping ring tone")

        // Stop system ringtone
        systemRingtone?.stop()
        systemRingtone = null

        // Stop ToneSource ring pattern
        ringToneJob?.cancel()
        ringToneJob = null
        ringTone?.stop()
        ringerMixer?.stop()
    }

    /**
     * Enable dial tone (audible).
     *
     * Matches Python __enable_dial_tone() (lines 565-568).
     */
    private fun enableDialTone() {
        receiveMixer?.let { mixer ->
            if (!mixer.isRunning()) mixer.start()
        }
        dialTone?.let { tone ->
            tone.setGain(DIAL_TONE_GAIN)
            if (!tone.isRunning()) tone.start()
        }
    }

    /**
     * Mute dial tone (silent but still running).
     *
     * Matches Python __mute_dial_tone() (lines 570-573).
     */
    private fun muteDialTone() {
        receiveMixer?.let { mixer ->
            if (!mixer.isRunning()) mixer.start()
        }
        dialTone?.let { tone ->
            if (tone.isRunning()) tone.setGain(0f)
            if (!tone.isRunning()) tone.start()
        }
    }

    /**
     * Disable dial tone completely.
     *
     * Matches Python __disable_dial_tone() (lines 575-577).
     */
    private fun disableDialTone() {
        Log.d(TAG, "Disabling dial tone")
        dialToneJob?.cancel()
        dialToneJob = null
        dialTone?.stop()
    }

    /**
     * Play busy tone pattern.
     *
     * Matches Python __play_busy_tone() (lines 541-551).
     * Pattern: 0.25s on, 0.25s off for BUSY_TONE_SECONDS.
     */
    private suspend fun playBusyTone() {
        if (BUSY_TONE_SECONDS <= 0) return

        Log.d(TAG, "Playing busy tone")

        // Ensure dialling pipelines exist
        if (audioOutput == null || receiveMixer == null || dialTone == null) {
            resetDiallingPipelines()
        }

        val windowMs = 500L
        val started = System.currentTimeMillis()
        val durationMs = (BUSY_TONE_SECONDS * 1000).toLong()

        while (System.currentTimeMillis() - started < durationMs) {
            val elapsed = (System.currentTimeMillis() - started) % windowMs
            if (elapsed > 250) {
                enableDialTone()
            } else {
                muteDialTone()
            }
            delay(5)
        }

        delay(500)
    }

    // ===== Profile Management =====

    /**
     * Send profile preference to remote.
     */
    private fun sendProfilePreference() {
        val profileSignal = Signalling.PREFERRED_PROFILE + activeProfile.id
        networkTransport.sendSignal(profileSignal)
    }

    /**
     * Select profile for call setup (before established).
     */
    private fun selectCallProfile(profile: Profile) {
        Log.d(TAG, "Selecting call profile: ${profile.abbreviation}")
        activeProfile = profile
    }

    /**
     * Handle profile switch from remote peer.
     */
    private fun switchProfileFromRemote(profile: Profile) {
        Log.i(TAG, "Remote requested profile switch to ${profile.abbreviation}")

        if (activeProfile == profile) return

        activeProfile = profile
        reconfigureTransmitPipeline()

        // Update receive decoder for new profile
        val decodeCodec = profile.createDecodeCodec()
        val decodeRate = decodeCodec.preferredSamplerate ?: 48000
        linkSource?.codec = decodeCodec
        linkSource?.sampleRate = decodeRate

        // Reconfigure audio output for new decode rate
        audioOutput?.let { sink ->
            if (sink.isRunning()) sink.stop()
            sink.configure(decodeRate, 1)
        }
    }

    // ===== Error Handling =====

    /**
     * Handle packetizer failure (link broken).
     */
    private fun onPacketizerFailure() {
        Log.e(TAG, "Packetizer failure, terminating call")
        scope.launch {
            hangup()
        }
    }

    // ===== Cleanup =====

    /**
     * Shutdown telephone and release resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down telephone")
        hangup()
        dialToneJob?.cancel()
        ringToneJob?.cancel()
        timeoutJob?.cancel()
    }
}

/**
 * Convert ByteArray to hex string for logging.
 */
private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Adapter that wraps Mixer to provide Sink interface.
 *
 * Kotlin Mixer extends LocalSource (single inheritance) but also has
 * Sink-like methods (handleFrame, canReceive). This adapter allows
 * sources (ToneSource, LineSource, LinkSource) to push frames to Mixer
 * through the standard Sink interface.
 *
 * This bridges the architectural gap where Python Mixer inherits from
 * both Source and Sink, but Kotlin only allows single inheritance.
 *
 * @param mixer The Mixer instance to wrap
 */
class MixerSinkAdapter(private val mixer: Mixer) : LocalSink() {

    /**
     * Check if mixer can accept more frames.
     *
     * Delegates to Mixer.canReceive().
     */
    override fun canReceive(fromSource: Source?): Boolean {
        return mixer.canReceive(fromSource)
    }

    /**
     * Handle an incoming audio frame.
     *
     * Delegates to Mixer.handleFrame().
     *
     * @param frame Float32 audio samples
     * @param source Optional source reference
     */
    override fun handleFrame(frame: FloatArray, source: Source?) {
        mixer.handleFrame(frame, source)
    }

    /**
     * Start the mixer.
     *
     * Delegates to Mixer.start().
     */
    override fun start() {
        mixer.start()
    }

    /**
     * Stop the mixer.
     *
     * Delegates to Mixer.stop().
     */
    override fun stop() {
        mixer.stop()
    }

    /**
     * Check if mixer is running.
     *
     * Delegates to Mixer.isRunning().
     */
    override fun isRunning(): Boolean {
        return mixer.isRunning()
    }
}
